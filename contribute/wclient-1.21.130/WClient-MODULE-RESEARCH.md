# WClient — Module Research (target: 1.21.130 / protocol 898)

**Repo:** `Alextheplayer919/WClient` (fork of `RetrivedMods/WClient`)
**Fork status:** identical to upstream — both at `f7b1a263` "cleanup", 2026-08-21. Upstream has not moved.
**Source analysed:** local clone at `/home/user/incoming/WClient`, commit `f7b1a26`
**Modules:** 62 registered + 1 orphaned = **63 files**, 6,965 LOC, 310 user-facing settings
**Method:** static read of every module file + the framework it plugs into. Nothing was executed (no JDK/Android SDK in this environment).

---

## 1. Anatomy of the module system

Everything a module does flows through four pieces:

```
WRelay (Netty/RakNet proxy :19132)
   └─ WRelaySession ── listeners: [GameSession, VersionTrackingListener]
        │                  beforeClientBound(pkt) / beforeServerBound(pkt) -> Boolean (true = CANCEL)
        │
        └─ GameSession (ComposedPacketHandler + InterruptiblePacketHandler)
             ├─ tracks LatomicPlayer / Level (entity & player maps)
             ├─ fans every packet out to all 62 modules
             └─ if any module called intercept() -> packet is dropped, loop stops
```

### The base class

```kotlin
abstract class Module(
    val name: String,                  // used as the config key AND chat status label
    val category: ModuleCategory,      // Combat | Motion | Visual | World | Misc | Config
    defaultEnabled: Boolean = false,
    val private: Boolean = false       // if true: excluded from config save/export
) : InterruptiblePacketHandler, AutoConfiguration
```

* **Only one module ships enabled:** `CommandHandlerModule` (`Module("command_handler", ModuleCategory.Misc, true)`).
* **No module is marked `private`** — every module is written to `UserConfig.json`.

### The hook surface (all a module gets)

| Hook | Signature | Purpose |
|---|---|---|
| `beforePacketBound` | `(InterceptablePacket)` | the only interception point; call `.intercept()` to cancel |
| `afterPacketBound` | `(BedrockPacket)` | post-pass (used by `NetworkInfoModule` for counting) |
| `onDisconnect` | `(reason: String)` | session teardown |
| `onEnabled` / `onDisabled` | — | overridable; also emits the `§l§c[WClient] … enabled/disabled` chat line |

`InterceptablePacket` is a thin wrapper: `val packet` + `var isIntercepted` (private set) + `fun intercept()`.

### ⚠️ Direction is invisible to modules

`ComposedPacketHandler` funnels **both** directions into the same method:

```kotlin
override fun beforeClientBound(packet) = beforePacketBound(packet)   // server → client
override fun beforeServerBound(packet) = beforePacketBound(packet)   // client → server
```

A module therefore cannot tell client-bound from server-bound without extra state. In practice this "works" because modules gate on packet types that only ever travel one way (`PlayerAuthInputPacket` is server-bound; `SetEntityMotionPacket` is client-bound). Any new 1.21.130 packet that travels both ways will need explicit direction tracking — this is the single biggest architectural constraint for module work.

### Values / config system (`ModuleValues.kt`)

| Factory | Backing type | Serialised as | Notes |
|---|---|---|---|
| `boolValue(name, default)` | `BoolValue` | JSON bool | |
| `floatValue(name, default, range)` | `FloatValue` | JSON float | range enforced **only in the UI slider** — `fromJson` accepts any float |
| `intValue(name, default, range)` | `IntValue` | JSON int | same caveat |
| `listValue(name, default, choices)` | `ListValue` | name string | |
| `enumValue(name, default, Class)` | `EnumValue` | enum name | |
| `stringValue(name, default, list)` | `StringValue` | JSON string | ⚠️ the third `listOf` argument is **ignored** (stored, never used) |

Access is delegated Kotlin properties: `private var cps by intValue("cps", 20, 5..30)`.
Config lives at `filesDir/configs/UserConfig.json`; import/export also writes to `Documents/WClient/configs`.
Malformed values are caught per-key and `reset()` to default.

---

## 2. What modules operate on — the shared runtime

Modules never touch raw packets for *state*; they read a live model built by `GameSession`:

| Object | Contents | Fed by |
|---|---|---|
| `session.localPlayer: LocalPlayer` | runtime/unique entity id, uuid, position, motion, rotation, attributes, inventories, authority flags | `StartGamePacket`, `MovePlayerPacket`, attribute/metadata packets |
| `session.level.entityMap: ConcurrentHashMap<Long, Entity>` | all tracked entities | `AddEntityPacket`, `AddPlayerPacket`, `AddItemEntityPacket`, `RemoveEntityPacket` |
| `session.level.playerMap: ConcurrentHashMap<UUID, PlayerListPacket.Entry>` | name, xuid, skin per UUID | `PlayerListPacket` |
| `session.protocolVersion` | from the negotiated server codec | `WRelaySession.server.codec` |

`Entity` exposes `vec3Position`, `vec3Rotation`, `motionX/Y/Z`, `tickExists`, `metadata` (`EntityDataMap`), `effects`, `attributes`, plus `isSneaking/isSprinting/isSwimming/isGliding`.
Subtypes: `LocalPlayer`, `Player`, `EntityUnknown` (mobs), `Item`.
`MobList.mobTypes` is a hardcoded **name set** (`minecraft:armadillo`, `minecraft:bogged`, …) — this is a name-matching list, not protocol data, so it survives version bumps but silently misses any mob added after it was written.

### Injection primitives modules use

| Call | Meaning |
|---|---|
| `session.clientBound(packet)` | **inject toward the local game client** (fake effects, fake death, injected positions) |
| `session.serverBound(packet)` | inject toward the real server |
| `session.displayClientMessage(text)` | local-only chat line, never hits the wire |
| `session.localPlayer.attack(entity)` | builds an `InventoryTransactionPacket` attack |
| `session.localPlayer.swing()` | `AnimatePacket` + `LevelSoundEventPacket` |
| `interceptablePacket.intercept()` | **cancel** the original packet |

---

## 3. Full module catalogue

Legend: **Priv** = `private` flag (none set) · **IPC** = calls `intercept()`

### 3.1 Combat — 15 modules, 1,345 LOC

| Module (name key) | LOC | Settings | Packets | Mechanism |
|---|---|---|---|---|
| **KillauraModule** `killaura` | 183 | range 7 (2–10), cps 20 (5–30), packets 1 (1–10), players_only, mobs_only, anti_bot, tp_aura, tp_behind, tp_speed 100, tp_y_offset 1, keep_distance 1.2, strafe, strafe_speed 2.5, strafe_radius 2.5 | `PlayerAuthInput`, `MovePlayer` | On every auth-input tick, scans `entityMap`, filters by range/target type, skips `FriendManager` friends, optionally teleports behind (`sin/cos` of target yaw) then `attack()` ×packets. Bot check = missing `playerMap` entry, blank name, or empty/`"0"` xuid. |
| **ACAModule** `ACA` | 104 | players_only, mobs_only, tp_aura, range 7, cps 12, tp_speed 150, yOffset, keep_distance 2 | `PlayerAuthInput`, `MovePlayer` | Same family: orbit/teleport aura that injects `MovePlayerPacket` toward the target and attacks. |
| **AutoFightModule** `AutoFight` | 157 | Max Range 5, CPS 10, Packets 1, Critical Hits, Multi-Target, Max Targets 2, Strike Distance 1.5, Return Delay 100, Humanize Movement, Randomize Timing | `MovePlayer`, `PlayerAuthInput`, `SetEntityMotion` | Stores `originalPosition`, lunges to strike distance, attacks, then returns — a "hit and run back" fighter. |
| **AutoHvHModule** `Auto HVH` | 130 | Range 500, Speed 2.5, Jitter, StrafeRadius 2, CPS 20, Packets 3, YOffset, NoClip, VerticalRange 12, VerticalSpeed 2, AutoHVH Motion | `MovePlayer`, `PlayerAuthInput` | Full-teleport HvH: closes distance by injected `MovePlayer`, adds jitter, orbits with strafe radius. |
| **EnemyHunterModule** `EnemyHunter` | 144 | Range 500, Speed 1.5, Jitter, Radius, CPS 20, Packets 3, YOffset, NoClip | `MovePlayer`, `PlayerAuthInput` | Hunts the nearest **enemy** (via `playerMap` lookup) and teleports onto it. |
| **InfiniteAuraModule** `InfiniteAura` | 110 | CPS 12, Behind Offset 2, Players Only, Silent Lagbacks | `PlayerAuthInput` | Teleports behind the target by mutating the outgoing auth-input `position`, with optional silent lagback suppression. |
| **WAuraModule** `WAura` | 106 | players_only, mobs_only, range 50 (2–50), cps 25, packets 2, Target Mode 0–2, Switch Delay 100 | `PlayerAuthInput` | Long-range aura; filters `FriendManager` friends; target-switching delay. |
| **TriggerBotModule** `trigger_bot` | 115 | cps 12, players_only, mobs_only, range 4 | `PlayerAuthInput` | Attacks whatever the crosshair is on (uses `localPlayer.vec3Rotation` + entity positions). |
| **HitAndRunModule** `hit_and_run` | 80 | range 4, hit_speed 200, jump_height 0.42, circle_radius 1.5, players_only | `PlayerAuthInput`, `SetEntityMotion` | Hits the target then injects an upward motion and circles away. |
| **CrystalSmashModule** `crystal_smash` | 50 | range 4, delay 5, cps 10, packets 1 | `PlayerAuthInput` | Scans `entityMap` for end-crystal entities and attacks them. |
| **AutoTotemModule** `Auto Totem` | 71 | Delay 100, Only When Low Health, Health Threshold 10, Replace Offhand | `PlayerAuthInput` | Reads `attributes[Attribute.HEALTH]` and `inventory.offhand`, finds `minecraft:totem_of_undying`, moves it to offhand (inventory transaction). |
| **HitboxModule** `hitbox` | 131 | width 1.5, height 1.5, players_only, include_mobs, particles 8, visualize | `SetEntityData`, `EntityEvent`, `PlayerAuthInput` | Expands other entities' hitboxes by overwriting `EntityDataMap` scale/box metadata; gated to every 40 ticks; emits particles to visualise. |
| **AntiKnockbackModule** `anti_knockback` | 22 | — | `SetEntityMotion` | **IPC.** Cancels `SetEntityMotionPacket` when `runtimeEntityId == localPlayer`. |
| **HotbarSwitcherModule** `switcher` | 74 | start_slot 0, end_slot 8, switch_delay 100, loop, reverse | `PlayerHotbar` | Re-emits `PlayerHotbarPacket` rotating through slots (`containerId=0`, `selectHotbarSlot=true`). |
| **AntiCrystalModule** `anti_crystal` | 24 | ylevel 0.4 (0.1–1.61) | `PlayerAuthInput` | **Broken — see §4.1.** |

### 3.2 Motion — 13 modules, 824 LOC

| Module | LOC | Settings | Packets | Mechanism |
|---|---|---|---|---|
| **FlyModule** `fly` | 116 | flySpeed 0.15 (0.1–1.5) | `RequestAbility`, `UpdateAbilities`, `SetEntityMotion`, `PlayerAuthInput` | **IPC.** Grants flight via injected ability packets using `uniqueEntityId`, then drives `SetEntityMotion` per tick. Restores abilities on disable. |
| **MotionFlyModule** `MotionFly` | 138 | Horizontal 3.5, Vertical 1.5, Glide 0.1, Lifeboat Bypass, Delay 50 | `SetEntityMotion`, `UpdateAbilities`, `PlayerAuthInput` | Ability packet toggling keyed on `uniqueEntityId` + per-tick motion; "Lifeboat Bypass" and a configurable delay suggest anti-cheat pacing. |
| **NoClipModule** `no_clip` | 118 | speed 0.15 | `RequestAbility`, `UpdateAbilities`, `SetEntityMotion`, `PlayerAuthInput` | **IPC.** Enables a no-clip ability set; vertical motion from `JUMPING`/`SNEAKING` input flags. **Contains a bug — §4.2.** |
| **PlayerTPModule** `PlayerTP` | 110 | Range 500, Speed 8, YOffset 1, Jitter Movement, Derp | `MovePlayer`, `PlayerAuthInput` | Teleport-to-player with jitter/"derp" rotation obfuscation. |
| **BhopModule** `bhop` | 56 | jumpHeight 0.42, motionInterval 120, times 1–20 | `SetEntityMotion`, `PlayerAuthInput` | Repeats jump motion on an interval. |
| **AntiAFKModule** `anti_afk` | 55 | interval 200, intensity 0.5 | `SetEntityMotion`, `PlayerAuthInput` | Periodic micro-motion + reset packet. |
| **AutoWalkModule** `auto_walk` | 45 | walk_speed 0.7, fall_speed −0.125 | `SetEntityMotion`, `PlayerAuthInput` | Constant forward motion. |
| **JetPackModule** `jetpack` | 41 | speed 2.5 | `SetEntityMotion`, `PlayerAuthInput` | Upward motion injection. |
| **SpeedModule** `speed` | 41 | speed 1.3 (0.1–5) | `SetEntityMotion`, `PlayerAuthInput` | Multiplies current `motionX/Z` by the speed factor. |
| **HighJumpModule** `high_jump` | 38 | jumpHeight 0.85 | `SetEntityMotion`, `PlayerAuthInput` | One-shot vertical boost. |
| **SpiderModule** `spider` | 33 | Climb Speed 0.5 | `SetEntityMotion`, `PlayerAuthInput` | Climb motion while pressing into a wall. |
| **AirJumpModule** `air_jump` | 33 | — | `SetEntityMotion`, `PlayerAuthInput` | On `PlayerAuthInputData.JUMP_DOWN`, injects `0.42` vertical motion. |
| **SprintModule** `sprint` | 20 | — | `PlayerAuthInput` | Adds `SPRINTING`/`START_SPRINTING` input flags (and `STOP_SPRINTING` when disabled). |

### 3.3 Visual — 13 modules, 1,922 LOC

| Module | LOC | Settings | Packets | Mechanism |
|---|---|---|---|---|
| **ESPModule** `esp` | **663** | fov 110, stroke_width 2.5, RGB colour, show_all_entities, ignore_bots, box_mode (`Box3D`), tracers, tracer_position, nametags, show_distance, show_armor | `MobArmorEquipmentPacket`, `MobEquipmentPacket` | The largest module in the client: 3D box/tracer/nametag rendering driven off `entityMap` + `playerMap` (skin, armour). |
| **CoordinatesModule** `Coordinates` | 186 | Show Coordinates/Direction/Dimension/Speed/Nether Coords, Position, Font Size, Color Mode, Background, BG Opacity, Border, Compact, Decimal Places, Update Rate | `PlayerAuthInput` | HUD text overlay incl. nether-coordinate conversion. |
| **MinimapModule** `minimap` | 168 | Show Players/Mobs/Items, Range 100, Size 120, Zoom, Dot Size, Show Names/Distance, Follow Player, Show Coordinates, Transparent BG | `MoveEntityAbsolute`, `PlayerAuthInput` | 2D radar built from relative `dx/dz` of `entityMap`. |
| **TargetHudModule** `targethud` | 157 | Player Only, Mobs Only, Range 20, Max Distance 50, Position X/Y, Scale, Show Distance/Status, Background Opacity | — (state-driven) | Panel for the nearest valid target, includes skin from `playerMap`. |
| **CrosshairModule** `Crosshair` | 115 | Type, Size, Thickness, Gap, Color Mode, Static Color, Rainbow Speed, Outline + Thickness, Dynamic Color, Hit Marker, Hit Duration, Pulsing, Pulse Speed | — | Custom crosshair with hit-marker state and pulsing animation. |
| **WorldStateModule** `world_state` | 91 | show_entities/players/time/chunks, colored_text, update_interval 500 | — | HUD counters straight off `entityMap.size` / `playerMap.size`. |
| **NetworkInfoModule** `network_info` | 85 | colored_text, show_packets | `BedrockPacket` (all) | **Only module using `afterPacketBound` too** — counts packets in both directions. |
| **ZoomModule** `zoom` | 77 | — | `UpdateAbilities`, `PlayerAuthInput` | Toggles zoom by injecting ability changes keyed on `uniqueEntityId`. |
| **SpeedDisplayModule** `speed_display` | 70 | colored_text, speed_smoothing | `PlayerAuthInput` | Derives speed from `motionX/Z`. |
| **PlayerJoinModule** `PlayerJoin` | 47 | — | `PlayerList` | Join/leave chat notices, skipping the local player's own UUID. |
| **FullbrightModule** `fullbright` | 44 | amplifier 1–5 | `MobEffect`, `PlayerAuthInput` | Re-applies a night-vision `MobEffectPacket` every 20 ticks. |
| **DamageTextModule** `DamageText` | 41 | — | `EntityEvent` | Floating damage text; skips the local player's own entity id. |
| **NoHurtCameraModule** `no_hurt_camera` | 26 | — | `EntityEvent` | **IPC.** Cancels the hurt event for the local player. |

### 3.4 World — 6 modules, 452 LOC

| Module | LOC | Settings | Packets | Mechanism |
|---|---|---|---|---|
| **FreeCameraModule** `free_camera` | 155 | speed 0.15 | `UpdateAbilities`, `SetEntityMotion`, `TextPacket`, `PlayerAuthInput` | **IPC.** Enables fly+no-clip abilities, moves the camera freely, restores position/abilities on disable. |
| **EffectsModule** `effects` | 121 | 26 effect toggles (Absorption → Wither, Night Vision, Water Breathing…) + Amplifier 1–5 | `MobEffect`, `PlayerAuthInput` | Re-sends `MobEffectPacket`s every 20 ticks so the **client** shows chosen effects. |
| **ParticlesModule** `particles` | 111 | Breeze Wind Explosion, Bubble, Dust, Explosion, Eye of Ender Death, Fizz, Heart + Interval, Height Offset, Size, Count, Random Offset, Offset Radius | `LevelEvent`, `EntityEvent`, `PlayerAuthInput` | Spawns local particle effects around the player. |
| **WeatherControllerModule** `weather_controller` | 89 | clear / rain / thunderstorm | `LevelEvent`, `PlayerAuthInput` | Client-side weather override via `LevelEventPacket`. |
| **AntiDebuffModule** `anti_debuff` | 44 | — | `MobEffect` | **IPC.** Swallows debuff `ADD` effects for the local player, re-emitting a filtered packet. |
| **TimeShiftModule** `time_shift` | 42 | time 6000 (0–24000) | `SetTime`, `PlayerAuthInput` | Client-side time override. |

### 3.5 Misc — 15 registered (+1 orphan), 1,871 LOC (registered)

| Module | LOC | Settings | Packets | Mechanism |
|---|---|---|---|---|
| **ChestStealerModule** `ChestStealer` | **476** | Auto Close, Min Delay 0, Max Delay 5, Start Delay 20, Auto Equip Armor, Priority Mode | `ContainerOpen`, `ContainerClose`, `InventoryContent`, `InventorySlot` | Second-largest module. Reacts to container opens, drains contents with randomised delays, optionally equips armour, all via `session.serverBound`. |
| **ReplayModule** `replay_mod` | 343 | interval 50, auto_save, playback_speed 1.0, record_inputs, smooth | `PlayerAuthInput` | **IPC.** Records movement frames and plays them back; blocks recording while playing and vice-versa. |
| **PieChartModule** `PieChart` | 229 | Chart Size 140, Update Rate, Show Percentages/Labels, Transparent BG, Animate Transitions, Highlight Largest, 3D Depth, Chart Tilt, Border Width, Legend Spacing/Font Size, Color Intensity, Position X/Y | 11 packet types incl. `AddEntity`, `AddItemEntity`, `AddPlayer`, `RemoveEntity`, `UpdateBlock`, `LevelSoundEvent`, `SetEntityData`, `MoveEntityAbsolute`, `MoveEntityDelta` | Entity-composition pie chart; the widest packet surface of any module. |
| **PositionLoggerModule** `position_logger` | 184 | closest_player, track_all, track_mobs, range 50 | `MoveEntityAbsolute`, `TextPacket`, `PlayerAuthInput` | Logs nearby entity positions to local chat. |
| **CommandHandlerModule** `command_handler` | 173 | **the only default-enabled module** | `TextPacket` | **IPC.** Prefix `.`; intercepts the chat packet, so command text never reaches the server. Routes `help` / `friend add|remove|list` / `replay …`, and **any other word is treated as a module name to toggle**. |
| **ArrayListModule** `arraylist` | 161 | Sort, Animation Speed, Background, Border + Style, Color Mode, Rainbow Speed, Font Size, Spacing, Fade/Slide Animation, Font Style, Gradient Intensity, Glow, Smooth Gradient | — | Animated enabled-module HUD list. |
| **KeyStrokesModule** `keystrokes` | 152 | Position X/Y, Key Size, Key Spacing, Show Sneak, Spacebar Width, Background Opacity, Animation Speed, Show Background, Rounded Corners | — | **ORPHANED — never registered in `ModuleManager`. Unreachable dead code (see §4.3).** |
| **SpammerModule** `Spammer` | 132 | Message "WClient is Best", Delay 1000, Mode (`REPEAT`), Randomize | `TextPacket` | Sends chat via `session.serverBound`. |
| **WorldStateModule** | — | *(listed under Visual)* | | |
| **WaterMarkModule** `watermark` | 81 | Text "WClient", Show Version, Position, Font Size, Mode (RGB), Font | — | Watermark HUD element. |
| **FakeDeathModule** `fake_death` | 78 | — | `EntityEvent`, `Respawn`, `PlayerAuthInput` | Sends a death `EntityEventPacket` + `RespawnPacket` at the current position — client-side fake death. |
| **NoChatModule** `no_chat` | 70 | block_all, block_player_chat, block_system_chat, block_whispers, block_announcements, block_join_leave | `TextPacket` | **IPC.** Filters incoming chat by category. |
| **DesyncModule** `desync` | 56 | — | `PlayerAuthInput` | **IPC.** Swallows outgoing `PlayerAuthInputPacket`s into a queue, then replays them with 100–300 ms jitter after disable (deliberate desync). |
| **AutoDisconnectModule** `auto_disconnect` | 54 | Health Threshold 4, Delay 100 | `Disconnect`, `PlayerAuthInput` | Fires a `DisconnectPacket` in **both** directions when health drops. |
| **FakeXPModule** `fake_xp` | 41 | interval 1000, levels 1–100 | `EntityEvent`, `PlayerAuthInput` | Fake XP-orb `EntityEventPacket`s. |
| **MinerModule** `fast_miner` | 44 | amplifier 1–5 | `MobEffect`, `PlayerAuthInput` | Re-applies a haste `MobEffectPacket` every 20 ticks (client-side). |
| **ToggleSoundModule** | 14 | — | — | Not a feature: a flag the base class consults to decide whether to play enable/disable sounds. `defaultEnabled = false` (named). |

**Category totals:** Combat 15 · Motion 13 · Visual 13 · World 6 · Misc 15 = **62 registered**.

---

## 4. Findings (code-grounded)

### 4.1 `AntiCrystalModule` is a no-op (dead module)
```kotlin
if (packet is PlayerAuthInputPacket) {
    packet.position.add(0.0, -ylevel.toDouble(), 0.0)   // result discarded
}
```
`org.cloudburstmc.math.vector.Vector3f` is **immutable** — `add()` returns a new vector, which is thrown away. The setting `ylevel` therefore has no effect and the module does nothing. It needs `packet.position = packet.position.add(...)` (or `.sub(...)` within the vector API).

### 4.2 `NoClipModule` sends motion for the wrong entity id
```kotlin
val motionPacket = SetEntityMotionPacket().apply {
    runtimeEntityId = session.localPlayer.uniqueEntityId   // should be .runtimeEntityId
```
Every other module (12 of 13 that build `SetEntityMotionPacket`) uses `runtimeEntityId`. This one uses the **unique** id, so the client is told to move a different entity → vertical no-clip motion is silently ignored. (The ability packets above it correctly use `uniqueEntityId`.)

### 4.3 `KeyStrokesModule` is orphaned
The file exists (152 LOC, 10 settings) and its imports are absent from `ModuleManager.kt`, so `add(KeyStrokesModule())` never runs. It cannot be reached from the UI, config, or command handler. Either register it or delete it.

### 4.4 Block/item definition data stops seven versions short of the target
```
App codec support      : … v844 (1.21.111) → v859 → v860 → v898 (1.21.130)   ✅ codec present
mcpedata/blocks/index  : [388 … 786, 800]        ← max protocol 800 = 1.21.80
mcpedata/items/index   : [361 … 786, 800]        ← max protocol 800 = 1.21.80
```
`MappingProvider.craftMapping(protocolVersion)` picks `availableVersions.filter { it <= protocolVersion }.max()`, so **a 1.21.130 client silently resolves to 1.21.80 block and item runtime IDs.**

### 4.5 …and those mappings are never used anywhere
`GameSession` builds `blockMapping` / `itemMapping` on `StartGamePacket` (GZIP decompress + full NBT/JSON parse) but a repo-wide search finds **no consumer** — no module and no session code calls `getDefinition`. So §4.4 is currently *not* a functional bug, but the parse is pure startup cost, and any future schematic/chest/GUI feature that does use them would ship stale 1.21.80 IDs to 1.21.130 clients.

*(Note: live item definitions do reach the codec helper — `GameSession` copies `StartGamePacket.itemDefinitions` into `codecHelper.itemDefinitions` for both peers, which is the correct server-driven path.)*

### 4.6 Dead `StringValue` parameter
`stringValue(name, default, listOf)` accepts a suggestion list and stores it never reading it — the third argument in `SpammerModule` and `WaterMarkModule` is decorative.

### 4.7 Minor inconsistencies
* `VersionDetector` labels 898 as `"1.21.130"` while the UI advertises `RECOMMENDED_VERSION = "v1.21.132"` and `RealmsManager.CLIENT_VERSION = "1.21.120"` — three different version strings in one app.
* `MobList.mobTypes` is a name set; mobs added after it was written are invisible to `mobs_only` filters.

---

## 5. 1.21.130 readiness — what the code actually says

| Layer | State for protocol 898 (1.21.130) |
|---|---|
| Codec class | ✅ `v898/Bedrock_v898` present, `protocolVersion(898)`, `minecraftVersion("1.21.130")` |
| Registry | ✅ `registerCodec(898, "1.21.130", "org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898")` — class exists, so no silent fallback |
| Version detector | ✅ 898 → `1.21.130, 1.21.131, 1.21.132` |
| Relay advertisement | ✅ uses `getLatestCodec()` → v898 |
| Per-client negotiation | ✅ `VersionTrackingListener` reads `RequestNetworkSettingsPacket.protocolVersion` and the closest codec is applied |
| Block/item data | ❌ tops out at protocol 800 (1.21.80) — see §4.4 |
| Module layer | ⚠️ version-agnostic by design; packets used are stable ones (`PlayerAuthInput`, `SetEntityMotion`, `MovePlayer`, `MobEffect`, `LevelEvent`, `TextPacket`, container packets). Real risk is §4.1/§4.2-class field-level bugs, not version drift. |

**Bottom line:** WClient's public source *is* a genuine 1.21.130 client — the codec chain is correct and complete. The gaps are (a) stale definition data, (b) the two field-level bugs above, and (c) the orphaned module.

---

## 6. Suggested work plan for the 1.21.130 branch

**Phase 1 — correctness fixes (small, self-contained)**
1. `AntiCrystalModule` — assign the new position (or use `Vector3f` mutators properly).
2. `NoClipModule` — `runtimeEntityId` instead of `uniqueEntityId`.
3. Decide `KeyStrokesModule`: register it (one line in `ModuleManager`) or delete the file.
4. Unify the three version strings behind one constant.

**Phase 2 — 1.21.130 data**
5. Generate `runtime_block_states_898.dat` + `runtime_item_states_898.json` and add `898` to both `index.json` files (or make the mapping lazy/optional and delete the dead parse).
6. Extend `MobList.mobTypes` for mobs added through 1.21.130.

**Phase 3 — verification on device**
7. Build with `:app:assembleAssembleRelease` — ⚠️ note the root build declares `buildAll` / `buildDebugAll` depending on `:desktop:shadowJar`, but **there is no `desktop/` module**; use the `:app:` tasks directly.
8. Join a 1.21.130 server and confirm the log line `Client version: Minecraft 1.21.130 (Protocol 898)` plus `Registered codec: 1.21.130 (protocol 898)` — that proves exact-match codec resolution rather than a fallback.
9. Regression pass on the packet-heavy modules: `PieChart` (11 packet types), `ChestStealer`, `ESP`, `Hitbox`, `MotionFly`/`Fly`/`NoClip` (ability packets).

**Phase 4 — architecture (optional, higher risk)**
10. Give modules direction awareness (split `beforeClientBound`/`beforeServerBound` instead of the fused `beforePacketBound`), since 1.21.130+ adds packets that travel both ways.

---

## 7. Packet → module matrix (who reacts to what)

| Packet | Modules |
|---|---|
| `PlayerAuthInputPacket` | 34 modules (effectively the per-tick driver for the whole client) |
| `SetEntityMotionPacket` | 20 (AirJump, AntiAFK, AutoFight, AutoWalk, Bhop, Fly, FreeCamera, HighJump, HitAndRun, JetPack, MotionFly, NoClip, Speed, Spider, …) |
| `MovePlayerPacket` | ACAModule, AutoFight, AutoHvH, EnemyHunter, Killaura, PlayerTP |
| `EntityEventPacket` | DamageText, FakeDeath, FakeXP, Hitbox, NoHurtCamera, Particles |
| `MobEffectPacket` | AntiDebuff, AutoTotem*, Effects, Fullbright, Miner, PieChart |
| `LevelEventPacket` | Particles, WeatherController |
| `TextPacket` | AutoDisconnect*, CommandHandler, NoChat, PositionLogger, Spammer |
| `UpdateAbilitiesPacket` | Fly, FreeCamera, MotionFly, NoClip, Zoom |
| `PlayerListPacket` | Level (playerMap), PlayerJoin |
| `Container*` / `Inventory*` | ChestStealer |
| `AddEntity` / `AddPlayer` / `AddItemEntity` / `RemoveEntity` / `SetEntityData` / `MoveEntityAbsolute` / `MoveEntityDelta` / `UpdateBlock` / `LevelSoundEvent` | PieChart (and `Level` for tracking) |

\* indirectly — `AutoDisconnect` sends `Disconnect`, `AutoTotem` uses inventory transactions.
