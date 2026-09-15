// ─────────────────────────────────────────────────────────────────────────────
// Eclient Attach Probe — PHASE 1 implementation (go/no-go gate).
// See EclientAttachMod.h for the scope: read-only, log-only.
//
// Signature patterns and struct offsets are ARM64 AAPT wildcards ("?") taken
// from the open-source BedrockTools native mod by RadiantByte
// (github.com/QYCottage/BedrockTools, Apache-2.0) as the reference point; they
// may need refreshing per Minecraft version — that fragility is exactly what
// this experiment exists to measure.
// ─────────────────────────────────────────────────────────────────────────────
#include "EclientAttachMod.h"

#include <atomic>
#include <cstdint>
#include <cstring>

#include <pl/Logger.hpp>
#include <pl/memory/Hook.hpp>
#include <pl/memory/Signature.hpp>

namespace {

// ── ARM64 byte signatures for libminecraftpe.so ("?" = any byte) ────────────
// ClientInstance::update() — fires every frame while the game runs.
constexpr const char* SIG_CLIENT_INSTANCE_UPDATE =
    "? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 FD 03 00 91 "
    "? ? ? D1 59 D0 3B D5 F3 03 00 AA F4 03 01 2A ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9";

// ClientInstance::getLocalPlayer() — returns Player* (BedrockTools ClientInstanceGetLocalPlayer).
constexpr const char* SIG_GET_LOCAL_PLAYER =
    "? ? ? D1 ? ? ? A9 ? ? ? F9 ? ? ? 91 53 D0 3B D5 E8 03 00 AA ? ? ? 91 "
    "? ? ? F9 ? ? ? 91 ? ? ? F8 ? ? ? 95 ? ? ? 91 ? ? ? 95 ? ? ? 36 ? ? ? 91 ? ? ? 52 ? ? ? 94";

constexpr const char* MC_LIB = "libminecraftpe.so";

using ClientUpdateFn = void* (*)(void*, bool);
using GetLocalPlayerFn = void* (*)(void*);

ClientUpdateFn   g_originalClientUpdate = nullptr;
GetLocalPlayerFn g_getLocalPlayer       = nullptr;
uintptr_t        g_clientUpdateAddr     = 0;

std::atomic<void*>    g_clientInstance{nullptr};
std::atomic<uint64_t> g_updateCount{0};

// Offsets inside Actor (bedrocktools::sdk::offsets, for 26.x builds).
constexpr std::size_t OFF_STATE_VECTOR_COMPONENT = 0x208; // -> ptr { Vec3 pos }
constexpr std::size_t OFF_ROTATION_COMPONENT     = 0x218; // -> ptr { Vec2 rot } (x=pitch? y=yaw as stored)

void* clientUpdateDetour(void* self, bool flag) {
    g_clientInstance.store(self, std::memory_order_release);
    const uint64_t tick = g_updateCount.fetch_add(1, std::memory_order_relaxed) + 1;

    // ~60 fps → every 600th frame ≈ once per 10 s. Read-only logging.
    if (tick % 600 == 1 || tick == 1) {
        if (g_getLocalPlayer != nullptr) {
            void* player = g_getLocalPlayer(self);
            if (player != nullptr) {
                auto* stateVec = *reinterpret_cast<void**>(
                    reinterpret_cast<char*>(player) + OFF_STATE_VECTOR_COMPONENT);
                auto* rotComp = *reinterpret_cast<void**>(
                    reinterpret_cast<char*>(player) + OFF_ROTATION_COMPONENT);
                if (stateVec != nullptr && rotComp != nullptr) {
                    struct Vec3 { float x, y, z; } pos{};
                    struct Vec2 { float a, b; } rot{};
                    std::memcpy(&pos, stateVec, sizeof(pos));
                    std::memcpy(&rot, rotComp, sizeof(rot));
                    pl::log::Logger::getOrCreate("EclientAttachProbe")
                        .info("pos=({:.2f}, {:.2f}, {:.2f}) rot=({:.2f}, {:.2f}) tick={}",
                              pos.x, pos.y, pos.z, rot.a, rot.b, tick);
                } else {
                    pl::log::Logger::getOrCreate("EclientAttachProbe")
                        .info("player found but component ptrs null (loading?) tick={}", tick);
                }
            } else {
                pl::log::Logger::getOrCreate("EclientAttachProbe")
                    .info("no local player yet (menu?) tick={}", tick);
            }
        }
    }

    if (g_originalClientUpdate != nullptr) {
        return g_originalClientUpdate(self, flag);
    }
    return nullptr;
}

} // namespace

EclientAttachMod& EclientAttachMod::instance() {
    static EclientAttachMod mod;
    return mod;
}

bool EclientAttachMod::load(pl::mod::ModContext& context) {
    context.logger().info("Eclient Attach Probe loading (phase 1: read-only feasibility gate)");
    return true;
}

bool EclientAttachMod::enable(pl::mod::ModContext& context) {
    auto& log = context.logger();
    log.info("Enabling: resolving signatures in {}", MC_LIB);

    const uintptr_t updateAddr = pl::memory::resolveSignature(SIG_CLIENT_INSTANCE_UPDATE, MC_LIB);
    const uintptr_t playerAddr = pl::memory::resolveSignature(SIG_GET_LOCAL_PLAYER,  MC_LIB);
    log.info("ClientInstance::update       @ 0x{:x}", updateAddr);
    log.info("ClientInstance::getLocalPlayer @ 0x{:x}", playerAddr);

    if (updateAddr != 0 && playerAddr != 0) {
        g_getLocalPlayer = reinterpret_cast<GetLocalPlayerFn>(playerAddr);
        const int rc = pl::memory::hook(
            reinterpret_cast<pl::memory::FuncPtr>(updateAddr),
            reinterpret_cast<pl::memory::FuncPtr>(clientUpdateDetour),
            reinterpret_cast<pl::memory::FuncPtr*>(&g_originalClientUpdate));
        if (rc == 0) {
            log.info("Hook installed — ClientInstance::update is being observed; player position will print every ~10s.");
        } else {
            log.error("Hook install FAILED (rc={})", rc);
        }
    } else {
        log.error("Signature resolution failed (update={}, player={}) — version mismatch likely. Game is untouched.",
                  updateAddr, playerAddr);
    }
    return true;
}

bool EclientAttachMod::disable(pl::mod::ModContext& context) {
    // unhook() takes (original target address, detour) — the target is the
    // ClientInstance::update address we resolved in enable(), NOT the
    // ClientInstance* pointer the detour captures.
    if (g_clientUpdateAddr != 0 && g_originalClientUpdate != nullptr) {
        pl::memory::unhook(reinterpret_cast<pl::memory::FuncPtr>(g_clientUpdateAddr),
                           reinterpret_cast<pl::memory::FuncPtr>(clientUpdateDetour));
        g_originalClientUpdate = nullptr;
        g_clientUpdateAddr = 0;
    }
    context.logger().info("Eclient Attach Probe disabled");
    return true;
}

bool EclientAttachMod::unload(pl::mod::ModContext& context) {
    context.logger().info("Eclient Attach Probe unloading");
    return true;
}
