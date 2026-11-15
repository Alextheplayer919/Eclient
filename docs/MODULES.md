# Modules — complete reference

**Purpose.** This file documents every registered module: what it does, its
settings, the packets it reads/sends, and its most important functions. It
exists so that when a dependency bump (CloudburstMC protocol, codecs, NBT)
changes behaviour, you can answer "who consumes this packet/field and why"
in one grep-free place, and detect silent behaviour regressions by comparing
observed wire traffic against this spec.

**Facts are extracted from code** (2026-09-19, commit `001458c` era; registry
counts and the COMBAT roster re-verified 2026-11-15 against
`module/ModuleManager.kt`): declared
settings, `is XxxPacket` handlers, `serverBound(...)` call sites, and function
names. Only things actually used in the code are documented. Companion docs:
`PROTOCOL.md` (Cloudburst usage inventory), `CORE-ARCHITECTURE.md` (relay +
trackers + event bus), `README.md` (index).

Registry: `module/ModuleManager.kt` auto-registers **57 module classes /
58 runtime instances** (ComboShortcut twice) on first access. Two files
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
- **Chat/util**: `DiagLog.log(tag, msg)` writes the diagnostic log. There is **no** `announce()` on `BaseModule` — a module that wants a local-only chat line builds its own S2C `TextPacket` (`Type.SYSTEM`, `isNeedsTranslation = false`, `setMessage(msg)`) and sends it through `session.clientBound(...)`. Working copies: `misc/Schematica.kt#announce`, `misc/AutoTravel.kt#announceLocal`, `combat/Surround.kt#announce`, and the replies in `core/commands/ChatCommands.kt`.
- **Shortcut convention**: every module declares `private val shortcut = bool("Shortcut", false)`. It is picked up generically by `ModuleManager.shortcutModules()` (filters any `BoolSetting` named `"Shortcut"` whose value is true) and consumed only by `ui/overlay/OverlayService.kt:370`, which adds/removes a floating per-module button at `OverlayPositions.shortcuts[name]`. Nothing else reads it, so adding the bool is all a module needs to become shortcut-able — no registry or UI change. **A new module must include it.**
- Cross-module helpers modules rely on: `EntityTracker` (world entity model + self position/yaw + inventory), `WorldBlockTracker` (chunk block names), `PlacementUtil`/`InventoryUtil` (aura-grade placement + hotbar machinery), `CollisionGuard`, `RotationUtil`, `OverlayService`/`OverlayState` (ESP rendering), `OreTracker`, `CritLock`, `MovementCompliance`.

---

# COMBAT (6)

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

### RotsAura — `module/combat/RotsAura.kt` (499 lines)
KillAura ported from KiwiMods with every Lynx/Kiwi rotation engine attached. Per
`PlayerAuthInputPacket` it selects targets in range, rewrites the rotation the
server sees, and attacks at the configured CPS.
- **Settings**: Range (6.0, 1–20), CPS (20, 5–30), Packets (1, 1–10), Players Only,
  Anti Bot, Ignore Friends, Rot Mode (enum, labelled: Abysm / Circle / Square /
  Zigzag / Spiral / Predictive / the Kiwi + Lynx sets), Randomize, Smooth Speed
  (15, 1–45), Strafe Amp (8, 0–30), Vertical Offset (0.3, −1–1), Silent Rotation,
  Pred Time/Strength, Rot Smoothing/Max Speed/Accel, Camera Sync, Shortcut.
- **Reads**: `PlayerAuthInputPacket` (rotation is rewritten in place).
- **Creates**: swing + `ITEM_USE_ON_ENTITY` attack via `PacketUtil`.
- **Key functions**: `searchForTargets`, `isTarget`, `isBot`, `applyRotation`,
  `computeLynxRotation`, `applyKiwiRotation`, `predictiveSettings`, `chestPos`.
- **Rotation engines** live in `module/combat/rots/`: `AbysmRot`, `GeoRot`,
  `HvHGod`, `KillauraRots`, `PredictiveAim`, `PredictiveRotator`, `RotsSupport`.

### NeonAura — `module/combat/NeonAura.kt` (615 lines)
Second killaura implementation (Neon source) sharing the same rotation engines;
adds orbit/strafe positioning on top of the aim.
- **Settings**: Range (7, 2–10), CPS (20, 5–30), Packets (1, 1–10), Players Only,
  Mobs Only, Anti Bot, Rotations, Prediction, Prediction Ticks (2, 0–10),
  Rotation Mode (enum), Vertical Offset, Smooth Speed, Strafe Amp, Pred Strength,
  Rot Smoothing/Max Speed/Accel, Keep Distance (1.2, 0.5–10), Strafe Radius
  (2.5, 1–6), Spin Direction (LEFT/RIGHT).
- **Reads**: `PlayerAuthInputPacket`; also watches `CorrectPlayerMovePredictionPacket`
  and `MovePlayerPacket` for the correction/orbit state.
- **Creates**: `PacketUtil.sendSwingAndAttack`, `PacketUtil.sendMove` (with
  `mirrorToClient` so the local physics engine follows instead of rubber-banding).
- **Key functions**: `searchForTargets`, `isTarget`, `localFrame`,
  `applyRotations`, `applyKiwiRotation`, `kiwiChestPosition`, `resetRotations`.

### Surround — `module/combat/Surround.kt` (873 lines)
Denial placement: seal a 1x1 hole around the player's feet with blast-resistant
blocks so the opponent has nowhere to put a crystal. Built from the **real
occupied footprint** (a 0.6-wide box can span two or four cells), not one
assumed block.
- **Settings**: Block (OBSIDIAN / CRYING / ANY / CUSTOM), Custom id, Shape
  (**CARDINAL 4** default / FULL_RING 8), **Center** (true), **Extend** (true),
  **Extra Layer** (false), Trigger (ALWAYS / ON_TOGGLE / SMART), Rotation
  (SILENT / VISIBLE / NONE), Threat range (12, 4–24), Tick ms (80, 20–2000),
  Max per tick (1, 1–4), Support floats, Only on ground, Auto-disable when
  sealed, Shortcut, Debug.
- **Shape default is CARDINAL**: the four cardinal cells are what seal a 1x1
  hole. The corners stay open on purpose — filling them wastes blocks and
  restricts movement. `FULL_RING` is offered as the option, not the default.
- **Center** is what stops the classic "2-wide ring with a hole next to me":
  an off-centre player's 0.6-wide box straddles two cells, so the ring of its
  union is 2 cells wide on that axis and the far diagonal cell is never a
  neighbour of the ring, staying open. The module snaps the player to the centre
  of its block (feet-frame `PacketUtil.sendMove`, `mirrorToClient`) whenever it
  is further than 0.05 from centre, and **plans against the snapped position**,
  so the ring is computed for where the player will actually be. Rate-limited to
  one snap per 300 ms and written back to `EntityTracker.selfX/selfZ` (as
  `RubberbandGuard.send` and `NoLagback` do), otherwise `offCentre` stays true
  until the client's own auth input catches up and the snap re-fires every pass.
- **`EntityTracker.selfOnGround` must not be used for outgoing moves.** It is
  written only by `EntityTracker.handleMovePlayer` (:377) and the reset (:214);
  `handleAuthInput` never touches it, so on an auth-input-only server it holds
  whatever the last `MovePlayerPacket` claimed — and this module sends those, so
  the value latches. A mirrored move that claims `isOnGround = false` while the
  player stands on the floor makes the client apply gravity from the position it
  was just set to: it sinks into the ground. `standingOn(x, feetY, z)` reads the
  block under the feet instead, and is used by the `Center` snap, the camera
  mirror, and the `Only on ground` gate.
- **Extend** steps the pattern outward on an axis when a player or crystal
  occupies a surround spot — that spot gets a block (it is a crystal spot), and
  the ring extends one cell past them.
- **Extra Layer** is reactive, not a second static ring: it fires when a cell
  this module already filled reads empty again (mined through by Speed Mine /
  Auto Mine) **and** a hostile is within `Threat range`. Cells are tracked in
  `confirmed`, cleared when the anchor moves.
- **Instant re-placement** is the core guarantee, not a rate: a filled cell is
  `confirmed` and dropped from `pending`, so the moment its block disappears the
  next pass sees EMPTY and refills it immediately. `PENDING_TTL_MS` is 600 ms —
  it only bounds the pre-echo round trip and the rejection retry.
- **Rotation** rotates the player server-side while placing, since real
  anti-cheat validates aim against placement. `SILENT` rewrites the outgoing
  `PlayerAuthInputPacket` only; `VISIBLE` also mirrors a client-only move.
- **Driver**: runs from the outgoing `PlayerAuthInputPacket`, gated by `Tick ms`
  — not a coroutine timer. Driving from the packet is what keeps the aim ahead
  of the placement on the wire: the rotation is rewritten and the ITEM_USE is
  sent inside the same handler. Trade-off: no placements while the game sends no
  auth input (paused, container screen) — the same trade-off the auras make.
- **Reads**: `PlayerAuthInputPacket` (rotation rewritten in place), plus
  `WorldBlockTracker` cell ids.
- **Creates**: `ITEM_USE` (actionType 0) placements through
  `PlacementUtil.sendPlacementUseRaw` with the mandatory consumption action
  record; in VISIBLE mode and for the `Center` snap, a **clientBound-only**
  `MovePlayerPacket` to swing the local camera.
- **Key functions**: `footprint`, `ringCells`, `entityInCell`, `cellState`,
  `standingOn`, `centerPlayer`, `planFor` (falls back to a support block under floating
  cells), `lookRotation`, `applyRotation`, `swingClientCamera`,
  `clickPointFor`, `threatNearby`.
- **Rotation**: SILENT rewrites the outgoing auth input's rotation (server sees
  the aim, screen untouched) — the `RotsAura.applyRotation` mechanism. VISIBLE
  adds the camera mirror. Because one auth input carries one rotation, a rotating
  pass places a single cell (the nearest open one) rather than `Max per tick`.
- **Deliberately avoids** `RotationUtil.toPoint` and `PacketUtil.sendMoveAtSelf`:
  both add to / pass `EntityTracker.selfY` as if it were feet-frame, but it is
  eye-frame whenever `selfYFrameIsEye` is set (the normal case on auth-input-only
  servers). Eye Y here comes from `CollisionGuard.feetY() + 1.62`, which is
  frame-correct. See the class KDoc.
- **Notes**: first caller of `PlacementUtil.findClickableNeighbor`; face ids are
  the canonical `core/schem/BuildBlocks.Face` numbering. The `blockId` passed to
  `sendPlacementUseRaw` is the **clicked** block's identifier (it becomes the
  packet's `blockDefinition`), not the block being placed — same as `Scaffold`
  and `AutoBuilder`. `pauseWhileEating` is
  forced `false` — a placement does not cancel an eat, and a defensive module
  that drops mid-gapple is worse than none (same choice as AutoTotem).

---

# MOVEMENT (18)

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
| `misc/ShulkerDupe.kt` | ShulkerDupe | dupe experiment; NOT registered |
| `visual/ShulkerPreview.kt` | ShulkerPreview | shulker-contents preview; NOT registered |
| `social/FriendManager.kt` | FriendManager | plain helper object (friend list), not a BaseModule |
