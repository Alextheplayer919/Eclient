// ─────────────────────────────────────────────────────────────────────────────
// Eclient Attach Probe — PHASE 1 (self-contained build, NO LeviLaunchroid).
//
// This .so is loaded straight by the GAME itself: the patcher script injects a
// single System.loadLibrary("eclient_attach") smali call into the Minecraft
// APK (plus this .so into lib/arm64-v8a/) and re-signs it. From the game's
// perspective, our code is just another native library it owns.
//
// Everything below is self-contained on purpose:
//   - signature scanning  → src/sigscan.cpp (our own), ~120 lines
//   - function hooking    → And64InlineHook (github.com/Rprop/And64InlineHook,
//     MIT, vendored in src/hook/ — CMake-built with us, no external download)
//   - logging             → __android_log_print, tag "EclientAttach"
// No LeviLaunchroid, no preloader runtime, no loader-side plugins.
//
// Scope of phase A (HYBRID experiment — native eyes, relay brain):
//   1. Wait until libminecraftpe.so is mapped in this process.
//   2. ARM64-wildcard pattern-scan for ClientInstance::update +
//      ClientInstance::getLocalPlayer (patterns adapted from the open-source
//      BedrockTools mod, Apache-2.0 — github.com/QYCottage/BedrockTools).
//   3. And64Hook the update fn, capture ClientInstance*.
//   4. Every update frame read the local player's StateVectorComponent
//      (pos, velocity) + ActorRotationComponent (pitch/yaw) and STREAM it to
//      the Eclient relay app over 127.0.0.1:19137 (one line per frame,
//      see docs/HYBRID.md for the protocol). Every ~10 s the old logcat line
//      stays as the sanity-check.
// If any scan fails: log it loudly and do nothing — game runs stock.
// ─────────────────────────────────────────────────────────────────────────────
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <pthread.h>
#include <time.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <android/log.h>

#include "hook/And64InlineHook.hpp"
#include "sigscan.h"

namespace {

constexpr const char* TAG    = "EclientAttach";
constexpr const char* MC_LIB = "libminecraftpe.so";

// ── ARM64 byte signatures ("?" = any byte) ──────────────────────────────────
// ClientInstance::update(bool) — runs once per frame while the game plays.
constexpr const char* SIG_CLIENT_INSTANCE_UPDATE =
    "? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 FD 03 00 91 "
    "? ? ? D1 59 D0 3B D5 F3 03 00 AA F4 03 01 2A ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9";

// ClientInstance::getLocalPlayer() — Player* (ClientInstanceGetLocalPlayer in BedrockTools).
constexpr const char* SIG_GET_LOCAL_PLAYER =
    "? ? ? D1 ? ? ? A9 ? ? ? F9 ? ? ? 91 53 D0 3B D5 E8 03 00 AA ? ? ? 91 "
    "? ? ? F9 ? ? ? 91 ? ? ? F8 ? ? ? 95 ? ? ? 91 ? ? ? 95 ? ? ? 36 ? ? ? 91 ? ? ? 52 ? ? ? 94";

using ClientUpdateFn   = void* (*)(void*, bool);
using GetLocalPlayerFn = void* (*)(void*);

ClientUpdateFn   g_originalClientUpdate = nullptr;
GetLocalPlayerFn g_getLocalPlayer       = nullptr;
uintptr_t        g_hookTarget           = 0;

std::atomic<void*>    g_clientInstance{nullptr};
std::atomic<uint64_t> g_updateCount{0};

// Field offsets into Actor (bedrocktools::sdk::offsets, 26.x reference).
constexpr std::size_t OFF_STATE_VECTOR_COMPONENT = 0x208; // ptr → { Vec3 pos ; ... }
constexpr std::size_t OFF_ROTATION_COMPONENT     = 0x218; // ptr → { Vec2 rot ; ... }

void logI(const char* fmt, ...) {
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, ap);
    __builtin_va_end(ap);
}
void logE(const char* fmt, ...) {
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, TAG, fmt, ap);
    __builtin_va_end(ap);
}

// ── Loopback feed → Eclient relay (hybrid phase A) ─────────────────────────
// One localhost TCP socket, one text line per frame; the relay app
// (NativeFeedServer) consumes these into EntityTracker's self-state.
constexpr int FEED_PORT = 19137;

int  g_feedFd         = -1;
long g_feedLastTryMs  = 0;
long g_feedLastSentMs = 0;

long nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void feedEnsure() {
    if (g_feedFd >= 0) return;
    const long t = nowMs();
    if (t - g_feedLastTryMs < 3000) return; // don't hammer reconnect
    g_feedLastTryMs = t;

    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(FEED_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        close(fd);
        return;
    }
    g_feedFd = fd;
    logI("native feed connected to relay @127.0.0.1:%d", FEED_PORT);
}

void feedSelfState(float x, float y, float z,
                   float rotA, float rotB,
                   float vx, float vy, float vz,
                   uint64_t tick) {
    if (g_feedFd < 0) return;
    const long t = nowMs();
    if (t - g_feedLastSentMs < 30) return; // ~33 Hz cap, tick is 20-60 fps
    g_feedLastSentMs = t;

    char buf[160];
    const int n = snprintf(buf, sizeof buf,
        "EA1 %.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f %llu\n",
        x, y, z, rotA, rotB, vx, vy, vz,
        static_cast<unsigned long long>(tick));
    if (n <= 0) return;
    if (send(g_feedFd, buf, static_cast<size_t>(n), MSG_NOSIGNAL) <= 0) {
        close(g_feedFd);
        g_feedFd = -1;
        logI("native feed lost (relay closed?) — will retry");
    }
}

void* clientUpdateDetour(void* self, bool flag) {
    g_clientInstance.store(self, std::memory_order_release);
    const uint64_t tick = g_updateCount.fetch_add(1, std::memory_order_relaxed) + 1;

    // Hybrid feed: cheap component reads every frame; the feed itself
    // throttles to ~33 Hz and stays silent while the relay isn't up.
    if (g_getLocalPlayer != nullptr) {
        if (void* player = g_getLocalPlayer(self)) {
            auto* bytes    = static_cast<char*>(player);
            void* stateVec = *reinterpret_cast<void**>(bytes + OFF_STATE_VECTOR_COMPONENT);
            void* rotComp  = *reinterpret_cast<void**>(bytes + OFF_ROTATION_COMPONENT);
            if (stateVec != nullptr && rotComp != nullptr) {
                struct { float x, y, z; } pos{};
                float vel[3]; // stateVector layout: { Vec3 pos ; Vec3 posPrev ; Vec3 velocity }
                struct { float a, b; }  rot{};
                std::memcpy(&pos, stateVec, sizeof(pos));
                std::memcpy(vel, static_cast<char*>(stateVec) + 24, sizeof(vel));
                std::memcpy(&rot, rotComp,  sizeof(rot));
                // velocity offsets are heuristic — clamp wild reads to zero
                for (float& v : vel) { if (!(v > -64.f && v < 64.f)) v = 0.f; }
                feedEnsure();
                feedSelfState(pos.x, pos.y, pos.z, rot.a, rot.b, vel[0], vel[1], vel[2], tick);
            }
        }
    }

    // ~60 fps → frame 1 immediately, then every 600th frame ≈ once / 10 s.
    if (tick == 1 || tick % 600 == 0) {
        if (g_getLocalPlayer != nullptr) {
            void* player = g_getLocalPlayer(self);
            if (player != nullptr) {
                auto* bytes    = static_cast<char*>(player);
                void* stateVec = *reinterpret_cast<void**>(bytes + OFF_STATE_VECTOR_COMPONENT);
                void* rotComp  = *reinterpret_cast<void**>(bytes + OFF_ROTATION_COMPONENT);
                if (stateVec != nullptr && rotComp != nullptr) {
                    struct { float x, y, z; } pos{};
                    struct { float a, b; }    rot{};
                    std::memcpy(&pos, stateVec, sizeof(pos));
                    std::memcpy(&rot, rotComp,  sizeof(rot));
                    logI("pos=(%.2f, %.2f, %.2f) rot=(%.2f, %.2f) tick=%llu",
                         pos.x, pos.y, pos.z, rot.a, rot.b,
                         static_cast<unsigned long long>(tick));
                } else {
                    logI("player present, component ptrs null (loading?) tick=%llu",
                         static_cast<unsigned long long>(tick));
                }
            } else {
                logI("no local player yet (menu?) tick=%llu",
                     static_cast<unsigned long long>(tick));
            }
        }
    }

    return g_originalClientUpdate ? g_originalClientUpdate(self, flag) : nullptr;
}

// Runs on a detached pthread: main-thread free, unload hardly possible anyway.
[[noreturn]] void* probeThread(void*) {
    // Wait until the game has mapped its native library (up to ~60 s).
    int waitedMs = 0;
    while (!sigscan::isModuleLoaded(MC_LIB) && waitedMs < 60000) {
        usleep(200 * 1000);
        waitedMs += 200;
    }
    if (!sigscan::isModuleLoaded(MC_LIB)) {
        logE("libminecraftpe.so never appeared in /proc/self/maps — aborting probe");
        pthread_exit(nullptr);
    }
    logI("libminecraftpe.so mapped after %d ms — resolving signatures", waitedMs);
    usleep(500 * 1000); // let the loader finish placing all segments

    const uintptr_t updateAddr = sigscan::scan(SIG_CLIENT_INSTANCE_UPDATE, MC_LIB);
    const uintptr_t playerAddr = sigscan::scan(SIG_GET_LOCAL_PLAYER,  MC_LIB);
    logI("ClientInstance::update        @ 0x%llx", static_cast<unsigned long long>(updateAddr));
    logI("ClientInstance::getLocalPlayer @ 0x%llx", static_cast<unsigned long long>(playerAddr));

    if (updateAddr == 0 || playerAddr == 0) {
        logE("signature miss (update=%llx player=%llx) — version mismatch likely; game untouched",
             static_cast<unsigned long long>(updateAddr),
             static_cast<unsigned long long>(playerAddr));
        pthread_exit(nullptr);
    }

    g_getLocalPlayer = reinterpret_cast<GetLocalPlayerFn>(playerAddr);
    g_hookTarget     = updateAddr;
    // A64HookFunction is all-or-nothing: on a bad target it logs to logcat and
    // leaves the code page untouched, so a miss behaves like "game untouched".
    A64HookFunction(
        reinterpret_cast<void*>(updateAddr),
        reinterpret_cast<void*>(&clientUpdateDetour),
        reinterpret_cast<void**>(&g_originalClientUpdate));
    if (g_originalClientUpdate == nullptr) {
        logE("A64HookFunction left original unset — hook failed, game untouched");
        pthread_exit(nullptr);
    }
    logI("hook installed — player position will print every ~10 s while in a world");

    // Keep the thread parked (cheap); hook lifetime = process lifetime.
    for (;;) pause();
}

// Entry point: System.loadLibrary() dlopens this .so → attribute constructors
// run. This is the universal path every APK gadget-injection uses (frida-gadget
// etc.), no LeviLaunchroid/JNI-specific contract involved.
__attribute__((constructor)) void probeInit() {
    logI("loaded into the game process — starting probe thread");
    pthread_t thread{};
    pthread_create(&thread, nullptr, &probeThread, nullptr);
    pthread_detach(thread);
}

} // namespace