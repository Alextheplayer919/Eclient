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
// Scope of phase 1 (go/no-go gate — read-only):
//   1. Wait until libminecraftpe.so is mapped in this process.
//   2. ARM64-wildcard pattern-scan for ClientInstance::update +
//      ClientInstance::getLocalPlayer (patterns adapted from the open-source
//      BedrockTools mod, Apache-2.0 — github.com/QYCottage/BedrockTools).
//   3. DobbyHook the update fn, capture ClientInstance*.
//   4. Every ~10 s read the local player's position/rotation from
//      StateVectorComponent / ActorRotationComponent and log it.
// If any scan fails: log it loudly and do nothing — game runs stock.
// ─────────────────────────────────────────────────────────────────────────────
#include <atomic>
#include <cstdint>
#include <cstring>
#include <pthread.h>
#include <unistd.h>

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

void* clientUpdateDetour(void* self, bool flag) {
    g_clientInstance.store(self, std::memory_order_release);
    const uint64_t tick = g_updateCount.fetch_add(1, std::memory_order_relaxed) + 1;

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