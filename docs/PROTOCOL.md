# CloudburstMC protocol usage inventory ("cres broke it" playbook)

**Purpose.** When a dependency update (CloudburstMC protocol/codec/NBT, or any
library bump via Dependabot) breaks or changes behaviour, start here. This file
lists every Cloudburst class the client touches, which files consume it, and the
exact fields/enums/semantic invariants the code physically relies on. Only
classes actually used in the tree are documented.

**Pinned today** (`app/build.gradle.kts`, 2026-09-19):
- `org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta6-SNAPSHOT`
- `org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta6-SNAPSHOT`
- `org.cloudburstmc:nbt:3.0.0.Final`
- `resolutionStrategy.force("org.cloudburstmc.fastutil:core:8.5.15")` — SNAPSHOT artifacts already move under us; the force is deliberate.

Companion: `MODULES.md` (per-module function detail), `CORE-ARCHITECTURE.md`.

## 0. How to use this doc when a bump lands

1. In the diff of the new artifact (or its release notes), find renamed/removed classes and field-setter changes. Cloudburst packets are Lombok `@Data` (setters!), so renames surface as **compile errors**, which is good; semantic changes (enum order, frame semantics, serializer version gates) are silent — those are the dangerous ones, and they have an "Invariant" entry below.
2. For each hit, open the consumer list in §2 and the invariants in §3.
3. Re-verify on a live server with **FlightProbe** (movement/auth traffic) and **PacketCollector** (per-class dumps) — both modules exist for exactly this job; see `MODULES.md#MISC`.
4. Smoke path: NoLagback stable under `CorrectPlayerMovePredictionPacket` → CrystalAura places/breaks → Schematica Auto Build places 5+ blocks → fly modules engage abilities.

---

## 1. Blast-radius ranking (consumers per class, import-level)

| Rank | Class | Files | Why it hurts first |
|---|---|---|---|
| 1 | `Vector3f` | 37 | position/motion everywhere |
| 2 | `PlayerAuthInputPacket` | 34 | the motion+input backbone: auras, fly, sprint, traps… |
| 3 | `SetEntityMotionPacket` | 18 | fly modules, jetpack, combat utility |
| 4 | `PlayerAuthInputData` (flags enum) | 16 | input flag bits 34/35/36 used for transaction piggybacking |
| 5 | `Vector3i` | 15 | block positions: placement pipeline, traps, miners |
| 6 | `ItemData` | 12 | inventory model: offhand, armor, placement, trackers |
| 7 | `MovePlayerPacket` | 11 | teleports, auras, fall-reset, freecam |
| 8 | `BedrockPacket` | 13 (via events) | the event bus base type |
| 9 | `UpdateAbilitiesPacket` + `Ability`/`AbilityLayer`/`CommandPermission`/`PlayerPermission` | 7–8 | every ability-fly module (Speed/CreativeFly/BypassFly/LifeboatFly/FreeCam/NoClip/ElytraFly…) |
| 10 | `TextPacket` | 9 | `announce()`/chat features everywhere |

## 2. Class → consumer map (only classes present in the tree)

### Network plumbing (`core/relay/…`)
- `BedrockClientSession`, `BedrockServerSession`, `BedrockPeer`, `BedrockChannelInitializer`, `BedrockPacketWrapper`, `RakChannelFactory`, `RakChannelOption`, `RakDisconnectReason`, `PacketDirection`, `BedrockPong` — `RubidiumRelay.kt` (listens/answers LAN) + `RubidiumRelaySession.kt` (bridges the two session objects; **this is where `serverBound()`/`clientBound()` live — everything the client sends ultimately calls one of them**).
- `BedrockCodec`, `EncodingSettings` (compression), `PacketCompressionAlgorithm` — `CodecRegistry.kt`, `AutoCodecListener.kt`, `RubidiumRelay.kt`. `AutoCodecListener` re-negotiates the codec version against the server and swaps two **custom serializers** (`InventoryContentSerializer_v729`, `InventorySlotSerializer_v729`).

### Login (`core/relay/listener/LoginPacketListener.kt`)
`AuthPayload`, `AuthType`, `CertificateChainPayload`, `TokenPayload` — Xbox/device-code chain extraction (`auth/` package drives it). Changing payload structures breaks first-contact login only; in-game play is unaffected.

### Motion / input
- `PlayerAuthInputPacket`, `PlayerAuthInputData` — 34/16 files: nearly all combat + movement modules read it; `EntityTracker` derives self position/rotation frames from it; NoLagback reconciles against `CorrectPlayerMovePredictionPacket` (FlightProbe also logs `ClientMovementPredictionSyncPacket`).
- `MovePlayerPacket` — modes **NORMAL / TELEPORT** (see `PacketUtil.kt` constructors); consumers: ACA, AutoHVH, FreeCam, NoFallDamage, NoLagback, TPAura, KillAura, FlightProbe…
- `MoveEntityAbsolutePacket` / `MoveEntityDeltaPacket` — AntiBed, AntiPiston, AirJump, PacketCollector.
- `SetEntityMotionPacket` — the velocity-write primitive for fly/jetpack/anti-bed/anti-piston/etc. (18 files). Also spoofed client-bound by FlightProbe.

### Inventory / placement (foundations of the auras + Auto Build)
- `InventoryTransactionPacket`, `InventoryTransactionType`, `ItemUseTransaction`, `InventoryActionData`, `InventorySource`, `ContainerId`, `ContainerSlotType`, `FullContainerName` — `utils/InventoryUtil.kt`, `utils/PlacementUtil.kt`, `utils/PacketUtil.kt`, and readers: Criticals, Timer, ElytraFly, ArmorHide, AutoArmor, AutoTotem, ShulkerDupe.
- `MobEquipmentPacket` — **constructed only inside `InventoryUtil.sendHotbarSelect/sendEquip`**; every placement module goes through that (incl. AutoBuilder via PlacementUtil).
- `ItemStackRequest(Packet)`, `ItemStackRequestAction` (`TakeAction`/`PlaceAction`/`SwapAction`/`DropAction`), `ItemStackRequestSlotData`, `ItemStackResponsePacket`, `ItemStackResponseStatus` — `InventoryUtil.kt` (move-from-inventory restock path) + ShulkerDupe/ShulkerPreview (unregistered).
- `InventoryContentPacket`, `InventorySlotPacket` (+ their v729 serializers) — inventory model: EntityTracker snapshot, ElytraFly durability, ArmorHide, AutoTotem.
- `ItemData`, `ItemDefinition` — full-stack model; `ItemData.AIR` and the `ItemData.Builder` shape are hard dependencies (EntityTracker, GamingPacketListener, AutoArmor/AutoTotem/CTrap, PlacementUtil, InventoryUtil).

### Blocks / world
- `LevelChunkPacket`, `SubChunkPacket`, `UpdateBlockPacket`, `UpdateSubChunkBlocksPacket`, `ChangeDimensionPacket`, `ClientCacheStatusPacket` — `utils/WorldBlockTracker.kt` + `utils/ChunkParser.kt` + `utils/BlockTracker.kt`; AntiLag drops chunk extras. **AutoBuilder's verification depends on this pipeline** (name-level) plus direct `UpdateBlockPacket` definition capture in `AutoBuilder.onUpdateBlock` (state-level, v2a).
- `BlockDefinition`, `SimpleBlockDefinition`, `DefinitionRegistry`, `SimpleDefinitionRegistry`, `NamedDefinition`, `SimpleNamedDefinition` — `core/relay/Definitions.kt` (protocol→palette registries incl. the custom `NbtBlockDefinitionRegistry`) + `PlacementUtil.getBlockDefinition`.
- `BlockEventPacket` — AntiPiston. `BlockEntityDataPacket`-adjacent storage scanning lives in ESP/ChunkParser via `NbtMap`.
- `LevelEventPacket`, `LevelEvent`, `LevelSoundEvent2Packet`, `LevelSoundEventPacket`, `PlaySoundPacket`, `CameraShakePacket`, `AnimatePacket` — AntiBed, AntiLag, WeatherController (client-bound `LevelEventPacket` injection), CrystalAura, NoHurtCam, Timer, PacketUtil.

### Entities / data
- `AddEntityPacket` (+ `EntityLinkData`), `EntityDataMap`, `EntityDataTypes`, `EntityFlag`, `SetEntityDataPacket`, `AttributeData`, `UpdateAttributesPacket`, `EntityEventPacket`, `EntityEventType`, `MobEffectPacket`, `MobArmorEquipmentPacket`, `PlayerListPacket`, `SetTimePacket` — EntityTracker modelling; Hitbox (rewrites width/height client-bound), ElytraFly glide-spoof, NoFire/AntiBlind, GodMode, ChatSpammer/PopCounter, FullBright (`SetTimePacket` + night-vision `MobEffectPacket`), FOVChanger, ArmorHide.

### Abilities family
`UpdateAbilitiesPacket`, `RequestAbilityPacket`, `Ability`, `AbilityLayer`, `CommandPermission`, `PlayerPermission`, `AdventureSettingsPacket` — Speed, CreativeFly, BypassFly, LifeboatFly, FreeCam, NoClip, ElytraFly, TPAura; FlightProbe logs all of them.

### Commands / chat
`CommandRequestPacket`, `CommandOriginData`, `CommandOriginType` — AutoBaseFinder/AutoTravel (`/sethome` injection), ChatAdvertiser. `TextPacket` — announce paths (AnchorAura, AutoMine, AutoTravel, ChatAdvertiser, ChatSpammer, CommandHelper, PopCounter, CP… ).

### Schematics subsystem
`NbtMap`, `NbtType`, `NbtUtils`, `NBTInputStream` (+ fastutil) — `core/schem/SchematicModel.kt` (all three loaders), `core/schem/BuildBlocks.kt`, `utils/ChunkParser.kt`, ShulkerPreview. `UnknownPacket` + `VarInts` + `SpawnParticleEffectPacket` — `core/schem/DebugDrawerBoxes.kt` hand-encodes DebugDrawer (packet id **328**) — see `SCHEMATICA.md`.

---

## 3. Invariants the code physically relies on

Movement & frames
1. **`PlayerAuthInputPacket.position` is eye-frame** (y ≈ +1.62 vs feet): `EntityTracker.selfYFrameIsEye` toggles frame handling; `MovePlayerPacket.Position` is **feet-frame**. Anything touching `CollisionGuard.feetY()`/AutoBuilder reach depends on the distinction staying accurate.
2. Auth-input flag bits are `EnumSet` ordinals: **34 = PERFORM_ITEM_INTERACTION, 35 = PERFORM_BLOCK_ACTIONS, 36 = PERFORM_ITEM_STACK_REQUEST** (pinned in `AUTOBUILD.md` against PMMP). If Cloudburst reorders the enum, bit semantics silently shift.
3. `MovePlayerPacket.Mode.TELEPORT` vs `NORMAL` choice is load-bearing for lagback behaviour (`PacketUtil.kt`, ACA, NoLagback micro-resyncs).
4. `SetEntityMotionPacket` = `(runtimeEntityId, motion: Vector3f)` — sender-side modules assume it applies unclamped (motion flies).

Placement wire (verified, see `AUTOBUILD.md` verification table)
5. `InventoryTransactionPacket` with `transactionType = ITEM_USE` and **`actionType = 0` (CLICK_BLOCK)** is the placement path; `clickPosition` is **block-relative 0..1**; `blockPosition` is the **clicked support block**; `blockDefinition` = the clicked block's definition from the palette registry.
6. The consumption action record (`actions += InventoryActionData(InventorySource.fromContainerWindowId(ContainerId.INVENTORY), slot, fromItem, toItem)`) is **required** — servers silently reject placement transactions without it (documented bug-fix history in `PlacementUtil.sendPlacementUseRaw`).
7. `MobEquipmentPacket.item` must be the slot's real item, never `ItemData.AIR` (same silent-rejection history in `InventoryUtil.sendHotbarSelect`).
8. Item stack moves use `ItemStackRequestPacket` (`Take`/`Place`/`Swap`/`Drop` + `ItemStackRequestSlotData` with `FullContainerName`/`ContainerSlotType`) and are acknowledged via `ItemStackResponsePacket` status — the v712+ container naming model is assumed by the custom `_v729` serializers in `AutoCodecListener`.

World data
9. `LevelChunkPacket` / `SubChunkPacket` / `UpdateSubChunkBlocksPacket` / `UpdateBlockPacket` feed `WorldBlockTracker`; it caches `runtimeId → identifier` via the session's own registry (`codecHelper.blockDefinitions`) and falls back to Definitions. Palette hashing (hashed runtime ids) exists as a variant registry and is handled there.
10. `Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition` carries `(runtimeId, tag)`; name lookups go through `findByName` — PlacementUtil relies on `runtimeId` being palette-correct for the click transaction's `blockDefinition` field.
11. Schematic loaders treat `NbtMap` string keys literally (`Name`/`Properties`, `states[]`, `BlockStates`, `palette`) — any NBT-map API change (generic value types, `getCompound` behaviour on null) is silent breakage in Schematica.

Cross-cutting
12. Packets are Lombok `@Data`: **setter-based construction is the house style** (`XxxPacket().apply { … }`). If a class becomes immutable/builder-only, that is the compile error you want to see early.
13. `Vector3f.from(x,y,z)` / `Vector3i.from(x,y,z)` static factories are used everywhere (37/15 files).
14. `UnknownPacket` + `VarInts` hand-encoding (DebugDrawerBoxes) bakes protocol ids **328** and the drawer varint layout — codec refactors that change `BedrockPacketHelper`/VarInts signatures hit exactly one file.
15. `TextPacket().apply { type = TextPacket.Type.RAW; message = … }` is the local-chat announce shape everywhere.

---

## 4. Version-gate / serialisation hooks worth watching in a bump

- `AutoCodecListener` — codec negotiation point; hosts the two custom `_v729` inventory serializers. A bump that ships its own fixes for those packets may make the customs redundant or conflicting.
- `session.activeCodec.protocolVersion` — the runtime gate used by Schematica (drawer availability) and Definitions selection.
- Packet id **328** (DebugDrawer), the `Spawning`/`Unknown` paths in Schematica's marker renderer, and `UnknownPacket` passthrough in `RubidiumRelaySession`.
- Login payload set (`AuthPayload` types) — version-gated chain parsing in LoginPacketListener.

--- 

## Chat & commands on the wire — audited 2026-09 (sources: gophertunnel packet docs, minecraft.wiki bedrock protocol table, bedrock-protocol relay API docs)

### TextPacket (id 0x09, BOTH directions)

Fields: `TextType byte`, `NeedsTranslation bool`, `SourceName string`,
`Message string`, `Parameters []string` (translation-style types only),
`XUID string`, `PlatformChatId string`, `FilteredMessage string` (newer,
"always empty, usage unknown").

Type enum: 0 Raw, 1 Chat, 2 Translation, 3 Popup, 4 JukeboxPopup, 5 Tip,
6 System, 7 Whisper, 8 Announcement, 9 ObjectWhisper, 10 Object,
11 ObjectAnnouncement.

Three load-bearing quotes (gophertunnel protocol docs):
- "When a client sends this to the server, **it should always be
  TextTypeChat**." → our interception guard (`p.type == Type.CHAT`) is
  correct and complete: typed in-game text cannot arrive serverbound with a
  different type.
- "the player will only be shown the chat message if a player with this
  XUID is present in the player list and not muted, **or if the XUID is
  empty**" → our local-only replies send `XUID = ""` → client always renders
  them. ✓
- Type-System messages render in the chat feed with no sender → the right
  envelope for command replies. ✓

### CommandRequestPacket (id 0x4D, serverbound)

Fields: `Command string` (leading `/`), `CommandOriginData` =
`(type varint, uuid, requestId string, playerEntityId long)`,
`Internal bool`. The server replies **CommandOutputPacket (0x4F, clientbound)**
keyed by the same request id. The origin block is not optional: proxies and
BDS route/authorize by it. All three pre-existing command-sending modules
(AutoTravel/AutoBaseFinder/ChatAdvertiser) build it as
`CommandOriginData(OriginType.PLAYER, randomUUID, "", 0)` + `isInternal=false`
— that shape is now mirrored in FriendLibrary's `/w` whisper (origin was
missing there until the v3 audit; added in a fix commit).

### Our usage — audit results

| Feature | Wire shape | Our code | Verdict |
|---|---|---|---|
| Intercept typed chat | C2S TextPacket, type **1 Chat** | `ChatCommands.onPacket` gates `Type.CHAT` | ✓ correct & complete |
| Swallow command lines | cancel the C2S packet before relay | `PacketEvent.cancel()` at priority -900 | ✓ |
| Local replies | S2C TextPacket type 6 System, XUID="" | `say()` → `clientBound(...)` | ✓ cannot leak (no C2S bytes exist) |
| `/w` whispers | C2S CommandRequest + origin block | `FriendLibrary.whisper` (after v3 audit fix) | ✓ origin block added |
| Command answers | S2C CommandOutputPacket 0x4F | relay passes through (untouched) | ✓ server replies display natively |
| Filter Text 0xA3 (both dirs) | sign/book/book-edit filtering | untouched — AutoSign rewrites BlockEntityData, not FilterText | ✓ correct layer |
| Misc | `SetCommandsEnabled` 0x3B (client→enable/disable commands) | untouched | ok — we never fake client commands |

### The actual .help regression (was NOT protocol)

Protocol model matched reality; the failure was application-layer:
`PacketEventBus.clear()` (Dashboard/SessionManager teardown) wiped every
listener incl. ChatCommands — Kotlin objects init once, so it never
re-registered. Fixed by making `ModuleManager.registerToSession()` (called
on EVERY new relay session) the re-registration contract, per-session
`ChatCommands.init()` (idempotent). See CHAT-COMMANDS.md "Bus-clear
survival".
