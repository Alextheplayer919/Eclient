# Core architecture — relay, event bus, trackers, shared utils

How the client physically sits on the wire and who owns which state. Companion
docs: `MODULES.md` (all 76 module instances), `PROTOCOL.md` (Cloudburst usage
inventory), `README.md` (index).

## 1. The big picture

```
  Minecraft client (game)                      upstream server
          │ Bedrock UDP/raknet                       ▲
          ▼                                           │
 ┌───────────────────── RubidiumRelaySession ─────────────────────┐
 │  BedrockClientSession (game-facing)   BedrockServerSession     │
 │            │  GamingPacketListener ──► PacketEventBus          │
 │            │        feeds trackers (EntityTracker,             │
 │            │        WorldBlockTracker, ItemData caches)        │
 │            ▼                                                   │
 │     RubidiumPacketListener  ──► PacketEventBus  (both ways)    │
 └────────────────────────────────────────────────────────────────┘
```

- `core/relay/RubidiumRelay.kt` — binds the local listener (`BedrockChannelInitializer`, `RakChannelFactory/Option`), answers LAN pings (`BedrockPong`), owns the codec handshake with `core/relay/codec/CodecRegistry.kt` + `LanServerScanner`/`LanBroadcaster` + `ConnectionManager` + `RealmsApi`.
- `core/relay/RubidiumRelaySession.kt` — one per connection; bridges the two Cloudburst sessions. **Everything modules send goes through `session.serverBound(packet)` (to server) or `session.clientBound(packet)` (inject to the local game).** Also handles `UnknownPacket` passthrough and `RakDisconnectReason` reporting.
- `core/relay/listener/`:
  - `LoginPacketListener` — Xbox/device-code auth chain (`auth/` package supplies tokens): `AuthPayload`/`CertificateChainPayload`/`TokenPayload`.
  - `AutoCodecListener` — negotiates the codec version vs the server; swaps in two **custom** inventory serializers (`InventoryContentSerializer_v729`, `InventorySlotSerializer_v729`) so v729-era container packets parse on our chosen codec. **Watch this file on any Cloudburst bump.**
  - `GamingPacketListener` — the big S2C dispatcher; `handleUpdateBlock`/`handleSubChunk` etc. feed the world trackers and cache `ItemData` by netId (`cacheByNetId` in EntityTracker).

## 2. Event system (`events/`)

- `PacketEvent(packet, direction, session)`; `Direction.CLIENT_TO_SERVER` = traffic the local game emits, `SERVER_TO_CLIENT` = server traffic. **`cancel()` drops the packet (it never crosses the relay)**; **`cancelAndReplace(pkt)` swaps it** — this is how Hitbox, ArmorHide, AntiBlind, AntiBed, WeatherController, FreeCam, FreeLook, AutoSprint etc. implement their effects without ever building packets on the *sending* side.
- `PacketEventBus.publish(event)` walks a volatile listener snapshot (`post` = alias); `currentSession` mirrors the active relay session (`WorldBlockTracker.resolveIdentifier` and others read it). BaseModule registers itself on enable, unregisters on disable — so module `onPacket` code runs only while enabled.
- Threading: events publish on **netty worker threads**; module tick loops run on a coroutine dispatcher. That is why shared state in trackers uses `@Volatile`/`ConcurrentHashMap`, and why module code tolerates races with plain volatile counters. **Don't add locks casually across these two worlds — publish side must never block.**

## 3. State trackers

### `core/proxy/EntityTracker.kt` (object)
The client-side world model:
- **Self**: `selfX/selfY/selfZ`, `selfYaw/selfPitch`, `selfRuntimeId/selfUniqueId`, `selfHotbarSlot`, plus **`selfYFrameIsEye`** — PlayerAuthInput positions are eye-frame (+1.62), `MovePlayerPacket.Position` is feet-frame; the flag tracks which frame `selfY` currently expresses. Everything that measures distance (`AutoBuilder` reach, aura range gates) must respect the frame.
- **Entities**: players/mobs by runtime id, with names (PlayerList), attributes, links (`EntityLinkData`).
- **Inventory**: hotbar snapshot (`getInventoryItem(slot)`), `getHeldItem()`, netId caches from `AddItemEntityPacket` — the source `PlacementUtil`/`AutoTotem` trust.

### `utils/WorldBlockTracker.kt`
Chunk-digested world blocks:
- `getBlockIdentifier(x,y,z): String?`, `isBlock(...)`, `hasAnyTerrainData()`.
- Fed by `LevelChunkPacket`/`SubChunkPacket`/`UpdateSubChunkBlocksPacket`/`UpdateBlockPacket` (+ `ChangeDimensionPacket`/`ClientCacheStatusPacket` resets), decoded with `utils/ChunkParser.kt`.
- `resolveIdentifier(runtimeId)` reads the **session's own** `codecHelper.blockDefinitions` first, falling back to `core/relay/Definitions.kt` (which also carries the hashed-id variant registry for weird palettes). `SimpleBlockDefinition.identifier` and `NbtBlockDefinition.tag.getString("name")` are the two extraction shapes.
- **AutoBuilder verification rides entirely on this pipeline.**

### `core/proxy/CollisionGuard.kt`
Feet-frame + solidity helpers (`feetY()`), used by Schematica placement origin, AutoTravel probeAhead, NoLagback collision guard. Chorus-plant collisions (the user's flag case) are handled here as collision-consistency, not combat.

### `core/proxy/MovementCompliance.kt`
Correction accounting for flight modules: `noteCorrection(x,y,z)`, `noteServerTeleport(...)` — NoLagback's governor consumes these to throttle under server correction pressure. Deep docs in `NO_LAGBACK_V3*.md`.

## 4. The god-utils modules funnel through

- **`utils/PlacementUtil.kt`** — the placement pipeline shared by CrystalAura, AnchorAura, BedAura, AutoTrap, SelfTrap, CTrap, Scaffold and **AutoBuilder**: `blockDefCache`, `findItemInInventory`, `prepareItemForUse` (hotbar find → hotbar select → inventory move via ItemStackRequest), `sendPlacementUseRaw` (the ITEM_USE CLICK_BLOCK transaction + mandatory consumption action record), `sendInteract`, `getBlockDefinition` (scan caches, `BLOCK_DEF_SCAN_CAP`/`MISS_LIMIT`), `posKey`, `findClickableNeighbor`, `revert`.
- **`utils/InventoryUtil.kt`** — slot math (hotbar 0–8, inventory 9–35, offhand 119), `sendHotbarSelect` (the "never AIR in MobEquipment" fix lives here), `sendOffhandEquip`, `isTotem`, `resolveIdentifier`, ItemStackRequest move builders (`TakeAction`/`PlaceAction`/`SwapAction`/`DropAction`), `ItemData` builder for offhand equips.
- **`utils/PacketUtil.kt`** — `MovePlayerPacket`/`AnimatePacket` constructors (modes NORMAL/TELEPORT), swing packets; used by Criticals + Timer.
- **`utils/MiningUtil.kt`** — break-speed/state helpers for ObsidianMiner + AutoMine; **`utils/CritLock.kt`** — crit-window bookkeeping for Criticals (shared with KillAura-style PePvP via `TimerPvP.kt`).
- **`utils/RubberbandGuard.kt`** — generic anti-rubberband helper (complements MovementCompliance).
- **`utils/RotationUtil.kt`, `MathUtil.kt`, `GameFov.kt`** — geometry/FOV math for ESP/Xray/TargetESP/ChunkFinder.
- **`utils/OreTracker.kt`** — ore index feeding Xray + AutoMining; **`utils/BlockTracker.kt`** — small per-module block index (separate from WorldBlockTracker; don't merge).
- **`utils/DiagLog.kt`** — session diagnostic log (modules tag themselves); **`utils/ItemIconProvider.kt`** — item icons for HUD.
- **`utils/EatingGuard.kt`** (core root) — gates auras/traps while the player is eating so placements don't fight the eat state.

## 5. Schematic subsystem (`core/schem/`)

Own docs: `SCHEMATICA.md` (renderer), `AUTOBUILD.md` (converter/planner/printer). Files: `SchematicModel.kt` (+`SchematicLoader`: `.mcstructure`/`.schem`/`.litematic` with full palette **states**), `DebugDrawerBoxes.kt` (hand-encoded packet 328), `BlockIdMap.kt` (java→bedrock id converter), `BuildBlocks.kt` (JavaState, Face, PlaceClass, BlockMapper plan/click/matches/materials/buildOrder, PlacementTracker), `AutoBuilder.kt` (v1 printer engine; driven from the Schematica module's `Auto Build (v1)` toggle).

## 6. Everything else (one-line roles)

- `auth/` — `AccountManager`, `MicrosoftAuthManager`, `DeviceCodeLoginActivity`, `AuthModels/AuthState`: device-code Xbox login feeding LoginPacketListener.
- `config/` — `Config`, `ServerConfig`: persisted settings + per-server bindings.
- `session/SessionManager.kt` — app-level session lifecycle for the dashboard.
- `ui/dashboard/DashboardActivity.kt` — module/settings UI; **`ui/overlay/`**: `OverlayService` + `OverlayState` + `ESPOverlayView` (+`OverlayNotifier`) are the drawing surface for all VISUAL modules and HUD modules (`render(...)` in each visual module paints via these).
- `ui/theme/` — app theme. `RibidiumClientApp.kt` — application object (`instance` — Schematica resolves its directory from it).
- `module/ModuleManager.kt` — the registry; `registerAll(...)` is the single source of truth for which classes are live; `registerToSession(session)` allows per-session wiring for the modules that need it.
