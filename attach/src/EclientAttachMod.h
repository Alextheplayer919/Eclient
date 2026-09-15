// ─────────────────────────────────────────────────────────────────────────────
// Eclient Attach Probe — PHASE 1 (go/no-go gate, see docs/ATTACH_FEASIBILITY.md)
//
// A LeviLaunchroid "preload-native" module. It is injected into the Minecraft
// Bedrock process by LeviLaunchroid's preloader — the stock game APK is NOT
// modified. This phase does exactly one thing:
//
//   1. Resolve ClientInstance::update + ClientInstance::getLocalPlayer inside
//      libminecraftpe.so via ARM64 byte-pattern signature scan.
//   2. Detour ClientInstance::update to capture the live ClientInstance*.
//   3. Read the local player's position/rotation straight from the
//      StateVectorComponent / ActorRotationComponent fields in game memory
//      and print them to logcat + the LeviLauncher mod log every ~10 s.
//
// Nothing is written, nothing is patched persistently. If the signatures fail,
// the probe logs the failure and the game runs untouched — that IS the test:
// it tells us how fragile the attached path is before any module porting.
// ─────────────────────────────────────────────────────────────────────────────
#pragma once

#include <pl/Mod.hpp>

class EclientAttachMod {
public:
    static EclientAttachMod& instance();

    bool load(pl::mod::ModContext& context);
    bool enable(pl::mod::ModContext& context);
    bool disable(pl::mod::ModContext& context);
    bool unload(pl::mod::ModContext& context);

    EclientAttachMod(const EclientAttachMod&)            = delete;
    EclientAttachMod& operator=(const EclientAttachMod&) = delete;

private:
    EclientAttachMod() = default;
};
