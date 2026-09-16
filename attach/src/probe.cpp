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
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <pthread.h>
#include <time.h>
#include <unistd.h>
#include <vector>

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

// Runtime-resolved offsets (fallback when the getLocalPlayer code signature
// misses — e.g. builds whose binary predates the sig, like 26.30 vs 26.40).
// Discovered live by scanning ClientInstance's own fields for a pointer whose
// target *shaped like* a player (see tryResolveRuntime). Signatures never
// involved — version-agnostic by construction.
std::atomic<std::size_t> g_playerFieldOff{0}; // ClientInstance + off → Player*
std::atomic<std::size_t> g_svcOff{0};         // Player + off → StateVectorComponent*
std::atomic<std::size_t> g_rotOff{0};         // Player + off → rotation component*

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

// One-line debug relay: anything we can't reach logcat from the user with
// goes to the Eclient app's DiagLog instead (NativeFeedServer parses EA0).
void feedDebug(const char* fmt, ...) {
    char buf[256];
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    const int n = vsnprintf(buf, sizeof buf, fmt, ap);
    __builtin_va_end(ap);
    if (n <= 0) return;
    if (g_feedFd < 0) { feedEnsure(); if (g_feedFd < 0) return; }
    char line[280];
    const int m = snprintf(line, sizeof line, "EA0 %.*s\n", n, buf);
    if (m <= 0) return;
    if (send(g_feedFd, line, static_cast<size_t>(m), MSG_NOSIGNAL) <= 0) {
        close(g_feedFd); g_feedFd = -1;
    }
}

// ── /proc/self/maps-backed read guards ─────────────────────────────────────
struct Mapping { uintptr_t lo, hi; };
std::vector<Mapping> g_maps;

void refreshMaps() {
    g_maps.clear();
    FILE* f = fopen("/proc/self/maps", "r");
    if (f == nullptr) return;
    char line[512];
    while (fgets(line, sizeof line, f) != nullptr) {
        unsigned long long lo = 0, hi = 0;
        char perms[8] = {};
        if (sscanf(line, "%llx-%llx %7s", &lo, &hi, perms) == 3 && perms[0] == 'r')
            g_maps.push_back({static_cast<uintptr_t>(lo), static_cast<uintptr_t>(hi)});
    }
    fclose(f);
}

bool rangeReadable(uintptr_t p, std::size_t len) {
    if (p < 0x10000 || (p & 7) != 0) return false;
    for (const auto& m : g_maps)
        if (p >= m.lo && p + len <= m.hi) return true;
    return false;
}

bool plausiblePos(const void* svc) {
    float v[3];
    std::memcpy(v, svc, sizeof v);
    for (float f : v) if (!std::isfinite(f)) return false;
    if (v[0] == 0.f && v[1] == 0.f && v[2] == 0.f) return false;
    if (std::fabs(v[0]) > 3.0e7f || std::fabs(v[2]) > 3.0e7f) return false; // world border
    if (v[1] < -512.f || v[1] > 8192.f) return false;                       // bedrock..sky
    return true;
}

bool plausibleRot(const void* rc) {
    float v[2];
    std::memcpy(v, rc, sizeof v);
    for (float f : v) if (!std::isfinite(f)) return false;
    if (std::fabs(v[0]) > 400.f || std::fabs(v[1]) > 1000.f) return false;
    return true;
}

// Finds LocalPlayer by recognizing a client's own player object shape.
// Runs ONLY while unresolved, throttled by the caller, and becomes a no-op
// the moment a hit latches. Safe: every deref is guarded by maps ranges.
bool tryResolveRuntime(void* ci) {
    refreshMaps();
    const char* base = static_cast<const char*>(ci);
    for (std::size_t q = 0x40; q < 0x900; q += 8) {
        const uintptr_t cand = *reinterpret_cast<const uintptr_t*>(base + q);
        if (!rangeReadable(cand, 0x348)) continue;
        for (std::size_t sv = 0x1E0; sv <= 0x2A0; sv += 8) {
            const uintptr_t svc = *reinterpret_cast<const uintptr_t*>(cand + sv);
            if (!rangeReadable(svc, 0x20) || !plausiblePos(reinterpret_cast<void*>(svc))) continue;
            for (std::size_t r = 0x1E0; r <= 0x340; r += 8) {
                if (r == sv) continue;
                const uintptr_t rot = *reinterpret_cast<const uintptr_t*>(cand + r);
                if (!rangeReadable(rot, 0x10) || !plausibleRot(reinterpret_cast<void*>(rot))) continue;
                g_playerFieldOff.store(q, std::memory_order_release);
                g_svcOff.store(sv, std::memory_order_release);
                g_rotOff.store(r, std::memory_order_release);
                float px = 0, py = 0, pz = 0;
                std::memcpy(&px, reinterpret_cast<void*>(svc), 4);
                std::memcpy(&py, reinterpret_cast<const char*>(svc) + 4, 4);
                std::memcpy(&pz, reinterpret_cast<const char*>(svc) + 8, 4);
                logI("runtime resolver HIT: player=ci+0x%zx svc=+0x%zx rot=+0x%zx", q, sv, r);
                feedDebug("resolver HIT ci+0x%zx svc+0x%zx rot+0x%zx pos=(%.1f, %.1f, %.1f)",
                          q, sv, r, px, py, pz);
                return true;
            }
        }
    }
    return false;
}

void* clientUpdateDetour(void* self, bool flag) {
    g_clientInstance.store(self, std::memory_order_release);
    const uint64_t tick = g_updateCount.fetch_add(1, std::memory_order_relaxed) + 1;

    // Hybrid feed: cheap component reads every frame; the feed itself
    // throttles to ~33 Hz and stays silent while the relay isn't up.
    // Player source: the getLocalPlayer function when its sig hit, else the
    // runtime-resolved ClientInstance field (armed on the fly).
    void* player = nullptr;
    if (g_getLocalPlayer != nullptr) {
        player = g_getLocalPlayer(self);
    } else {
        if (g_playerFieldOff.load(std::memory_order_acquire) == 0 && tick % 30 == 0)
            tryResolveRuntime(self);
        const std::size_t pfo = g_playerFieldOff.load(std::memory_order_acquire);
        if (pfo != 0)
            player = *reinterpret_cast<void**>(static_cast<char*>(self) + pfo);
    }
    const std::size_t svcOff = g_svcOff.load(std::memory_order_acquire);
    const std::size_t rotOff = g_rotOff.load(std::memory_order_acquire);
    const std::size_t useSvc = svcOff != 0 ? svcOff : OFF_STATE_VECTOR_COMPONENT;
    const std::size_t useRot = rotOff != 0 ? rotOff : OFF_ROTATION_COMPONENT;
    if (player != nullptr) {
        {
            auto* bytes    = static_cast<char*>(player);
            void* stateVec = *reinterpret_cast<void**>(bytes + useSvc);
            void* rotComp  = *reinterpret_cast<void**>(bytes + useRot);
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
        if (player != nullptr) {
            auto* bytes    = static_cast<char*>(player);
            void* stateVec = *reinterpret_cast<void**>(bytes + useSvc);
            void* rotComp  = *reinterpret_cast<void**>(bytes + useRot);
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
            logI("no local player yet (menu? resolver=%s) tick=%llu",
                 g_playerFieldOff.load(std::memory_order_acquire) != 0 ? "latched" : "scanning",
                 static_cast<unsigned long long>(tick));
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

    // Hard requirement: ClientInstance::update (frame hook — the eyes open
    // from there). getLocalPlayer is a nice-to-have now: if its code sig
    // misses (older builds like 26.30), the detour discovers the player
    // pointer live instead.
    if (updateAddr == 0) {
        logE("signature miss on ClientInstance::update — game untouched");
        pthread_exit(nullptr);
    }
    if (playerAddr == 0) {
        logE("getLocalPlayer sig MISS — runtime resolver armed (version drift tolerated)");
    }

    g_getLocalPlayer = reinterpret_cast<GetLocalPlayerFn>(playerAddr); // may be nullptr
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