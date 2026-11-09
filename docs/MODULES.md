# Modules — complete reference

**Purpose.** This file documents every registered module: what it does, its
settings, the packets it reads/sends, and its most important functions. It
exists so that when a dependency bump (CloudburstMC protocol, codecs, NBT)
changes behaviour, you can answer "who consumes this packet/field and why"
in one grep-free place, and detect silent behaviour regressions by comparing
observed wire traffic against this spec.

**Facts are extracted from code** (2026-09-19, commit `001458c` era): declared
settings, `is XxxPacket` handlers, `serverBound(...)` call sites, and function
names. Only things actually used in the code are documented. Companion docs:
`PROTOCOL.md` (Cloudburst usage inventory), `CORE-ARCHITECTURE.md` (relay +
trackers + event bus), `README.md` (index).

Registry: `module/ModuleManager.kt` auto-registers **54 module classes /
55 runtime instances** (ComboShortcut twice) on first access. Two files
exist but are NOT registered (dead/experimental): `misc/ShulkerDupe.kt`,
`visual/ShulkerPreview.kt`; `social/FriendManager.kt` is a plain helper, not
a module.

Name drift (filename vs registered class) to keep greps sane:
- `misc/Disconnect.kt` → class **AutoDisconnect**
- `misc/AutoBaseFinder.kt` → module **AutoStashHunter**
- `misc/AutoMine.kt` → module **AutoMining**
- `movement/FreeCam.kt` → **FreeCamera**

---

## 0. The module framework (`module/BaseModule.kt`, `ModuleManager.kt`)

- **Identity**: constructor `name`, `category` (`ModuleCategory.COMBAT|MOVEMENT|VISUAL|PLAYER|WORLD|MISC`), `description`. Category decides the UI section; it is independent from the source directory (e.g. `combat/AntiPiston.kt` declares MOVEMENT).
- **Settings DSL** (registered in constructor, editable in the module menu):
  - `bool("label", default)`
  - `int("label", default, min, max)`
  - `float("label", default, min, max)`
  - `enum("label", defaultEnumValue)`
  - `string("label", default)`
  Settings read at `onEnable()` time; changing most of them requires re-toggling the module (documented per-module where relevant).
- **Lifecycle**: `onEnable()` / `onDisable()` — calling `super` registers/unregisters the module on the **PacketEventBus**, so `onPacket(event)` receives traffic only while enabled.
- **Tick driver**: `launchTickLoop(intervalMs) { ... }` launches a coroutine loop stopped automatically on disable; convert settings with `.value.toLong()`.
- **Packets**: `override fun onPacket(event: PacketEvent)`; check `event.direction` (`CLIENT_TO_SERVER` on the wire from the game, `SERVER_TO_CLIENT` from the server) and `event.packet is XxxPacket`. Sessions come from `event.session`; send with `session.serverBound(packet)` (to the server) or `session.clientBound(packet)` (inject toward the game). Read "onPacket may be called while the relay is mid-handshake — use `playerState`/tracker guards".
- **Chat/util**: `announce(msg)` posts a local chat line; `DiagLog.log(tag, msg)` writes the diagnostic log.
- Cross-module helpers modules rely on: `EntityTracker` (world entity model + self position/yaw + inventory), `WorldBlockTracker` (chunk block names), `PlacementUtil`/`InventoryUtil` (aura-grade placement + hotbar machinery), `CollisionGuard`, `RotationUtil`, `OverlayService`/`OverlayState` (ESP rendering), `OreTracker`, `CritLock`, `MovementCompliance`.

---

# COMBAT (3)

### Criticals — `module/combat/Criticals.kt` (136 lines)
Forces every hit to crit by injecting the micro-position packets vanilla jump-crits imply. ULTRA-FAST variant in code.
- **Reads**: `InventoryTransactionPacket`, `MovePlayerPacket`, `MovePacket`.
- **Key functions**: `injectFast`, `injectUltraFast`, `injectVanilla`, `injectPacket`.

### Hitbox — `module/combat/HitboxModule.kt` (144 lines)
Enlarges targets' hitboxes only in the local entity data stream (manual hits land easier).
- **Settings**: Width/Height (1.5f, 0.5–12), Range (12f), Shortcut.
- **Creates**: rewritten `SetEntityDataPacket` toward client. **Key functions**: `applyToTargets`, `sendHitbox`, `resetAll` (restore on disable).

### AutoTotem — `module/combat/AutoTotem.kt` (PLAYER category, 242 lines)
Keeps a totem equipped in offhand at all times.
- **Tick loop**: `SAFETY_TICK_MS`.
- **Reads**: `EntityEventPacket`, `InventoryContentPacket`, `InventorySlotPacket`.
- **Key functions**: `refreshFromSnapshot`, `tickCheck`, `equipTotem` (→ `InventoryUtil.sendOffhandEquip`).

---

# MOVEMENT (19)

### Speed — `movement/Speed.kt` (145 lines)
Faster walking — either Ability (UpdateAbilities) or Motion mode.
- **Settings**: Multiplier (1.5f), Walk Speed (0.2f), Motion Delay (55).
- **Key functions**: `applyAbilitySpeed`/`resetAbilities`, `applyMotionSpeed`. **Creates**: `SetEntityMotionPacket`, `UpdateAbilitiesPacket`.

### MotionFly — `movement/MotionFly.kt` (144 lines)
LeHu-style motion fly (Testfly port), blocks-per-second profile.
- **Settings**: H/Up/Down Speed BPS (46/19.8/46), Glide (-0.02f), Up/Down H Factor (0.55f).
- **Reads**: `PlayerAuthInputPacket`, `SetEntityMotionPacket`.

### NoLagback — `movement/NoLagback.kt` (138 lines)
Adaptive anti-lagback: governor throttles flight under correction pressure, smart micro-resyncs clear drift. The most protocol-sensitive movement module.
- **Settings**: Mode (enum `NoLagMode.ADAPTIVE`), Smart Resync, Resync Distance (1.2f), Climb Budget BPS (4f), Collision Guard, Drop Resets/Corrections.
- **Reads**: `CorrectPlayerMovePredictionPacket`, `MovePlayerPacket`, `PlayerAuthInputPacket`.
- Deep docs: `NO_LAGBACK_V3.md`, `NO_LAGBACK_V3_VERIFICATION.md`.

### CreativeFly — `movement/CreativeFly.kt` (138 lines)
Ability-based flight: vertical motion only (Jump/Sneak), horizontal stays vanilla WASD.
- **Reads**: `PlayerAuthInputPacket`, `RequestAbilityPacket`. **Creates**: `UpdateAbilitiesPacket`, `SetEntityMotionPacket`.

### BypassFly — `movement/BypassFly.kt` (297 lines)
Environment-aware flight profiles (Water/Ground/Air/Auto) with noise.
- **Settings**: Horizontal Speed, Air/Water Vertical Speed, Water Bob, Air Jitter, Max Step.
- **Key functions**: `resolveMode`, `noise`, `applyAbilities`.

### LifeboatFly — `movement/LifeboatFly.kt` (171 lines)
Ramped-acceleration motion fly (no instant max speed).
- **Settings**: Max H/V Speed, Acceleration/Deceleration, Idle Jitter.

### Jetpack — `movement/Jetpack.kt` (70 lines)
Hold jump → thrust in look direction via motion packets.
- **Settings**: Thrust Speed (1.2f). **Creates**: `SetEntityMotionPacket`.

### AirJump — `movement/AirJump.kt` (88 lines)
Jump while airborne → extra upward boost.
- **Creates**: `SetEntityMotionPacket`. **Reads**: `MoveEntityAbsolutePacket`, `MovePlayerPacket`, `PlayerAuthInputPacket`.

### NoFallDamage — `movement/NoFallDamage.kt` (90 lines)
Periodically resets server-side fall distance.
- **Settings**: Trigger Distance (3f).

### NoSlowdown — `movement/NoSlowdown.kt` (86 lines)
Hides sprint slowdown via position compensation.
- **Settings**: Expected Speed (0.28f), Deficit Ratio (0.8f).

### AntiKnockback — `movement/AntiKnockback.kt` (23 lines)
Cancels all incoming knockback. **Reads**: `SetEntityMotionPacket`.

### AntiPiston — `combat/AntiPiston.kt` (63 lines)
Cancels nearby piston pushes inside a protection window.
- **Settings**: Detect Range (4f), Protection Window (600 ms), Cancel Motion/Absolute Move.
- **Reads**: `BlockEventPacket`, `MoveEntityAbsolutePacket`, `SetEntityMotionPacket`.

### NoClip — `movement/NoClipModule.kt` (151 lines)
`Ability.NO_CLIP` through blocks + free vertical movement.
- **Creates**: `UpdateAbilitiesPacket`, `SetEntityMotionPacket`. **Key functions**: `enableAbilities/disableAbilities`.

### FreeCam — `movement/FreeCam.kt` (VISUAL category, 199 lines)
Local free camera; the server stream is kept frozen.
- **Creates**: `MovePlayerPacket`, `SetEntityMotionPacket`, `UpdateAbilitiesPacket`.
- **Key functions**: `grantAbilities`, `restoreAbilities`.

### Timer — `movement/Timer.kt` (163 lines)
Simulates faster game time by duplicating real movement/attack packets.
- **Settings**: Motion/Attack Multiplier, Min Move Threshold, PvP Max Step.
- **Reads**: `AnimatePacket`, `InventoryTransactionPacket`, `MovePlayerPacket`, `PlayerAuthInputPacket`.
- **Key functions**: `handleMotion`, `handleAttack`, `handleSwing`.

### Spider — `movement/SpiderModule.kt` (62 lines)
Touches a wall → climbs it.
- **Creates**: `SetEntityMotionPacket`.

### FreeLook — `movement/FreeLook.kt` (92 lines)
Decouples the yaw the server sees from your real view.
- **Settings**: Fixed Yaw, Keep Real Pitch. **Reads**: `PlayerAuthInputPacket`.

### Scaffold — `movement/Scaffold.kt` (138 lines)
God-bridge/Telly: auto-bridges in the move direction while sprinting.
- **Settings**: Predict Distance (1.1f). **Key functions**: `findScaffoldBlock` (placement via shared utils).

---

# VISUAL (11)

### BaseFinderESP (`ESP`) — `visual/ESP.kt` (426 lines)
Tracers/boxes on chests/shulkers/spawners/storage blocks found in chunk data.
- **Settings**: Scan Range (64f, up to 512), Manual FOV (110f), Tracer Width, Radar Margin, Smooth Factor.
- **Keyed functions**: `updateTick`, `rebuildRenderList`, `render` (overlay draw), `drawRadarArrow`, `drawSummaryPanel`, `isTypeEnabled`.

### TargetESP — `visual/TargetESP.kt` (496 lines)
Tracers + surrounding box + health indicator on enemy players.
- Settings analogous to ESP. **Key functions**: `drawFlatBox`, `drawHealthIndicator`, `drawWireBox`, `colorFor`/`healthColorFor`.

### Xray — `visual/Xray.kt` (484 lines)
Shows ores (normal + deepslate) as tracers/boxes client-side (data from `OreTracker` chunk parsing, NOT a resource-pack xray).
- **Key functions**: `scanLoop`/`rebuildLoop`, `enabledTypes`, `render`, `drawScanStats`.

### FullBright — `visual/FullBright.kt` (98 lines)
Night-like-day via self-granted night vision + time pin.
- **Creates**: `MobEffectPacket`, `SetTimePacket` (client-bound injections).
- **Key functions**: `injectNightVision`, `removeNightVision`. **Reads**: `StartGamePacket`.

### AntiBlind — `visual/AntiBlind.kt` (30 lines)
Neutralizes blindness darkening. **Reads**: `MobEffectPacket`.

### NoFire — `visual/NoFire.kt` (37 lines)
Hides fire overlay/effect. **Reads**: `SetEntityDataPacket`.

### NoHurtCam — `visual/NoHurtCam.kt` (38 lines)
Blocks hurt-damage head shake. **Reads**: `AnimatePacket`, `EntityEventPacket`.

### FOVChanger — `visual/FOVChanger.kt` (83 lines)
Widens FOV client-side without telling the server.
- **Creates**: client-bound `MobEffectPacket`. **Key functions**: `injectFov`, `removeFov`. **Reads**: `StartGamePacket`.

### ChunkFinder — `visual/ChunkFinder.kt` (190 lines)
Renders 16×16 chunk-boundary grid + corner poles in 3D.
- **Settings**: Pole Height (24f), Line Width (1.5f), Manual FOV (110f).
- **Tick loop**: `updateRateMs`. **Key functions**: `rebuildChunkList`, `drawGridSquare`, `drawCornerPoles`.

### ArrayList — `visual/ArrayListModule.kt` (226 lines)
Animated top-right list of active modules.
- **Key functions**: `trackActivations`, `baseHueFor`/`chaseAccentFor` (color), `render`.

### ArmorHide — `visual/ArmorHide.kt` (58 lines)
Presents server armor data as empty to the client → 3D armor model hidden.
- **Reads**: `InventoryContentPacket`, `InventorySlotPacket`, `MobArmorEquipmentPacket`.

---

# PLAYER (3)

### GodMode — `module/player/GodModeModule.kt` (78 lines)
LAN/no-anticheat only: hides damage/death on the local screen; the server's own health tracking is untouched (documented in description).
- **Settings**: HideDamage, HideHurtAnimation. **Reads**: `EntityEventPacket`, `UpdateAttributesPacket`.

### AntiAFK — `module/player/AntiAfkModule.kt` (74 lines)
Periodic micro-movement to defeat AFK kicks. **Key function**: `tickLoop`.

### AntiLag — `module/player/AntiLagModule.kt` (83 lines)
Drops visual-only traffic locally: particles/sounds/chunk extras (reduces render load client-side).
- **Settings**: Drop Particles/Sounds/Chunk Extra.
- **Reads**: `LevelChunkPacket` (token refill), `LevelEventPacket`, `LevelSoundEvent2Packet`, `PlaySoundPacket`, `SpawnParticleEffectPacket`.

---

# WORLD (4)

### AutoSign — `module/world/AutoSign.kt` (v3, ~110 lines)
Fills your name + date onto empty sign lines when you save a sign (docs/AUTOSIGN.md).
- **Settings**: Name line (2, 1–4), Date line (3, 1–4), Also fill back side (off), Date format ("dd MMM yyyy").
- **Reads**: C2S `BlockEntityDataPacket` (id `Sign`). **Creates**: replacement packet via `cancelAndReplace` (rewrites `FrontText`/`BackText.Text` — only blank lines, so hand-written lines win). Never injects packets.

### AutoTorch — `module/world/AutoTorch.kt` (v3, ~240 lines)
Spawn-proofs dark floors near you with torches from your hotbar (docs/AUTOTORCH.md).
- **Settings**: Tick ms (600, 300–2000), Reach (4.2f, 2.5–4.6), Vertical scan (3, 1–6).
- **Reads**: `WorldBlockTracker` block ids (no light data exists on the Bedrock wire — light is simulated client-side via a multi-source BFS flood with a conservative emission/opacity table). **Creates**: `MobEquipmentPacket`-based hotbar prep + `InventoryTransactionPacket` (ITEM_USE, top face) via `PlacementUtil`; one torch per tick, 5 s per-position cooldown.
- **Key functions**: `computeLight`, `placeTorch`, `isFloor/isTranslucent`.


### WeatherController — `module/world/WeatherControllerModule.kt` (140 lines)
Pins local-screen weather regardless of the server's.
- **Settings**: Weather (enum `WeatherType.CLEAR`), ResyncInterval (5000).
- **Creates**: client-bound `LevelEventPacket`. **Key functions**: `applyWeather`, `clearWeather`.

### Nuker — `module/world/NukerModule.kt` (121 lines)
Breaks blocks around you (anti-cheat-less servers only, per description).
- **Settings**: Range (3.5f, 1–6). **Creates**: `PlayerActionPacket`.
- **Key functions**: `breakBlock`, `findTarget`.

---

# MISC (16 registered instances)

### AutoStashHunter — `module/misc/AutoBaseFinder.kt` (156 lines)
Flies at a set Y with elytra-glide spoofing, scans for storage blocks, auto-`/sethome`.
- **Settings**: Target Y Level (120f), Fly Speed (1f), Scan Range (48f), Home Cooldown min (3f).
- **Creates**: `CommandRequestPacket` (home command), `SetEntityDataPacket`/`SetEntityMotionPacket`, `PlayerActionPacket` (glide).
- **Key functions**: `applyFlight`, `scanForBases`, `isWantedType`, `startGlide/stopGlide`, `buildCommandPacket`.

### AutoTravel — `module/misc/AutoTravel.kt` (331 lines)
Ascend to target Y → fly or WALK to X/Z → `/sethome` on arrival. Walk mode pre-scans obstacles from real block data, jumps/steers when stuck.
- **Creates**: `CommandRequestPacket`, `SetEntityMotionPacket`.
- **Key functions**: `tickAscend`, `tickTravel`, `tickTravelWalk`, `probeAhead`/`isPassable`, `tryJump`, `widenSteer`, `onArrived`/`sendMotion`, plus its own stash-hunt copy (`scanForBases`/`buildCommandPacket`).

### AutoMining — `module/misc/AutoMine.kt` (406 lines)
Walks to ores discovered by Xray, digs through obstacles, auto-equips the right pickaxe, mines, and un-mines on move-away (`cancelBreak`).
- **Creates**: `PlayerActionPacket`. **Key functions**: `pickNextTarget`, `walkToward`, `mineBlock`/`cancelBreak`, `ensurePickaxe`/`hasPickaxeEquipped`, `faceFor`.

### ChatSpammer — `module/misc/ChatSpammer.kt` (272 lines)
Chat prefix + kill/logout spam with junk-string anti-filter.
- **Settings**: KillSpammer, Chat Suffix, Kill/Logout Message templates (`{name}`, `{junk}`).
- **Reads**: `EntityEventPacket` (deaths), `PlayerListPacket` (join/leave). **Key functions**: `refreshPlayerSnapshots`, `handleDeath`, `handleLogout`, `buildTextPacket`, `randomJunk`.

### ChatAdvertiser — `module/misc/ChatAdvertiser.kt` (225 lines)
Spam bot: TPA / TpaHere / PVP / Ads modes with the same junk-message machinery.
- **Creates**: `CommandRequestPacket` (+ TextPackets). **Key functions**: `sendTpa*`, `sendPvpMessage`, `sendAdsMessage`.

### PopCounter — `module/misc/PopCounter.kt` (234 lines)
Totem-pop counter via offhand polling.
- **Settings**: Send Chat (false → local overlay only). **Reads**: `EntityEventPacket`.
- **Key functions**: `pollTotems`, `flushQueue`/`enqueue`/`buildTextPacket`.

### ArmorHud — `module/visual/ArmorHudModule.kt` (MISC-registered, 109 lines)
Own armor + health in a screen corner (overlay `render`).

### AutoSprint — `module/movement/AutoSprintModule.kt` (MISC-registered, 51 lines)
Keeps sprint permanently on while moving. **Settings**: Motion Threshold (0.05f). **Reads**: `PlayerAuthInputPacket`.

### ComboShortcut ×2 — `module/misc/ComboShortcut.kt` (37 lines)
Toggles a preset group of modules with one shortcut; two instances (`ComboShortcut(1)`, `ComboShortcut(2)`). **Key function**: `applyToTargets`.

### AutoDisconnect — `module/misc/Disconnect.kt` (PLAYER category, 88 lines)
Disconnects automatically on void fall, high ping, or low health+totem condition.
- **Settings**: Void Y (-32f), Health Threshold (6f). **Key functions**: `check`, `hasTotem`, `trigger`.

### Performance — `module/misc/Performance.kt` (VISUAL category, 19 lines)
Overlay FPS ceiling + idle render suppression.

### FlightProbe — `module/misc/FlightProbe.kt` (204 lines)
Passive wire logger written for NoLagback v3 verification flights (`baba.txt`, `VP|v1|` lines). The richest single packet in the codebase for MovePacket forensics.
- **Settings**: W/D Motion Probe, Motion Packet Probe.
- **Reads**: `AdventureSettingsPacket`, `ClientMovementPredictionSyncPacket`, `CorrectPlayerMovePredictionPacket`, `DisconnectPacket`, `MobEffectPacket`, `MovePlayerPacket`, `PlayStatusPacket`, `PlayerAuthInputPacket`, `SetEntityDataPacket`, `StartGamePacket`, `UnknownPacket`, `UpdateAbilitiesPacket`, `UpdateAttributesPacket`.
- **Creates**: `SetEntityMotionPacket`. **Key functions**: `handleS2C`, `handleC2S`, `vp`, `fmt`.

### Schematica — `module/misc/Schematica.kt` (279 lines)
Ghost-build renderer (`WIREFRAME` DebugDrawer boxes or particle markers) + **Auto Build v2b** (SIMPLE/AXIS/AUTO_CONNECT + single slabs, state-verified via `UpdateBlockPacket`; reviewed fix series BB-1..7/AB-1..15/SC-1..6 applied — ghost de-populates confirmed cells, live toggle, breaker resets only on confirmation.
- **Settings**: File (blank=newest), Render Style, Marker Particle, Layer, Max Points (350), Repaint ms (600), Nudge X/Y/Z (-64..64), Auto Build bool (live toggle), Auto Build tick ms (150, 100–500).
- **Tick loops**: `respawnMs` (repaint) and `abDelay` (auto-build, only when enabled).
- **Reads**: `DebugDrawerPacket` (remove-ack shapes), `UnknownPacket` (legacy marker path).
- **Creates**: client-bound `SpawnParticleEffectPacket` / hand-encoded DebugDrawer (type 328 — see `SCHEMATICA.md`).
- **Key functions**: `loadAsync` (`.mcstructure`/`.schem`/`.litematic` via `SchematicLoader`), `repaint`, `drawDrawer`, `spawnParticles`, `autoBuildTick` (drives `AutoBuilder`), plus `onPacket` forwarding of `UpdateBlockPacket` into `AutoBuilder.onUpdateBlock` for state verification.
- Deep docs: `SCHEMATICA.md`, `AUTOBUILD.md`.

### CommandHelper — `module/misc/CommandHelper.kt` (80 lines)
Saved one-tap commands/text lines injected into chat.
- **Settings**: Entries (string, delimited). **Key functions**: `addEntry/removeEntry/clearEntries`, `send`, `buildTextPacket`.

### PacketCollector — `module/misc/PacketCollector.kt` (193 lines)
Full-fidelity packet logger → per-class log files (`Detailed/Exclude Spam/Auto Flush` settings). THE Phase-0 capture tool for AUTOBUILD protocol work — enable, reproduce traffic by hand, read the session logs.
- **Reads**: broad set incl. `LevelChunkPacket`, movement packets, `UpdateBlockPacket`, `UpdateAttributesPacket`, `UpdateAbilitiesPacket`.
- **Key functions**: `writeToFile`, `buildDetailedSummary`, `getLogDirectory`.

---

## Not registered (kept for reference/parts)

| File | Class | Status |
|---|---|---|
| `combat/PistonAura.kt` | PistonAura | experimental piston-attack aura; NOT in `registerAll` |
| `misc/ShulkerDupe.kt` | ShulkerDupe | dupe experiment; NOT registered |
| `visual/ShulkerPreview.kt` | ShulkerPreview | shulker-contents preview; NOT registered |
| `social/FriendManager.kt` | FriendManager | plain helper object (friend list), not a BaseModule |
