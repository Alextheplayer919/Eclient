# Melody V2 → Eclient Proxy: module feasibility map

Source: Melody V2 (MIT, © tonytranrp) — Windows 1.20.51 injected C++ client, 58 modules.
Reference copy lives in the workspace (`melody-survey/x/Melody`), full zip in the
`Zips-ig` repo. This map scores every module for porting to the **proxy/relay
line** (Kotlin, packet layer). C++ is deliberately NOT used inside the proxy:
combat logic is protocol-bound, not CPU-bound.

## Tiers

- **A — portable now.** Everything needed flows through packets the relay can
  already read or craft (entity spawn/move, rotations via `PlayerAuthInputPacket`,
  attacks/transactions, equipment switches).
- **B — portable with more protocol work.** Needs the relay to cache state it
  currently doesn't (chunk blocks from `LevelChunkPacket`, full inventory
  mirror, world simulation).
- **C — in-game process only.** Requires reading native memory or rendering
  inside the game view → parked `attach-experiment` territory, resume only.

## Combat

| Module | Tier | Notes |
|---|---|---|
| Killaura | **A** | Flagship port. Spec below. |
| Reach | C | Server validates distance; reach is a native hit-registration game. |
| GravityAura | C | Movement exploit, native. |
| AutoCrystal / Anticrystal | B | Needs chunk-block cache + inventory txn crafting. |
| AutoAnvil / AutoMine / AutoTntSpammer | B | Block world-state + placement transactions. |

## Player

| Module | Tier | Notes |
|---|---|---|
| AutoArmor, AutoEat, AutoOffhand, AutoXp, ChestStealer, InventoryCleaner (Misc) | **A** | Inventory/transaction packets are fully visible to the relay. |
| AutoRespawn, AutoEmote, AutoSprint | **A** | Simple packet sends. |
| Scaffold | B | Placement txns + local-position tracking from AuthInput echo. |
| PacketMine, FastEat, Swing, Regen | B | Timing/transaction exploits — need careful packet-state machine. |
| BlockReach, Surround, AntiAnvil, OffhandExploit | B–C | Position/world knowledge heavy. |
| AntiInvis | A (as warning) | Relay sees invisible entities; can't force-render them, but CAN alert. |

## Movement

| Module | Tier | Notes |
|---|---|---|
| AutoSprint | **A** | Sprint flag in movement packets. |
| Fly, Speed, Phase, Clip, ElytraFly, Velocity, NoSlowDown | C | All modify client-side movement computation — native by definition. Packet-fly variants (B) exist but only survive on zero-anti-cheat servers. |
| Timer | C | Client tick-rate manipulation, native. |

## Misc

| Module | Tier | Notes |
|---|---|---|
| AutoTools | **A** | Same best-slot scoring as Killaura weapons, keys off block type from mining context. |
| NoPacket, Disabler | B | "Don't send X" is trivially proxy-doable; full anti-AC disablers are server-specific research. |

## Render (ALL tier C — attach territory)

ESP, Tracer, NameTags, Fullbright, CustomFov, CustomSky, NoHurtcam, NoRender,
CameraNoClip, HurtColor, ConicalHat, AutoEmote(render) — all need in-process
rendering or native state. One future proxy-side approximation: Android
system-overlay window drawing boxes from packet-tracked positions using an
estimated view matrix (janky, no occlusion) — noted, not planned.

## Killaura port spec (Tier A, from `Combat/Killaura.cpp`)

Settings schema (mirror these in the Eclient menu; slider ranges included):
- Target Range 3–12 (default 7), Wall Range 0–12 (default 5; wall checks need
  blocks → **v1: skip, treat as Target Range**)
- Mode: Single / Multi
- Rotation: None / **Silent** / Strafe — Silent = write yaw/pitch into
  `PlayerAuthInputPacket` without touching the visual camera (matches the
  standing "silent rotations" requirement exactly)
- Switch: None / Full / **Silent** — Silent = send `MobEquipmentPacket` to the
  best slot, send attack, restore slot (packet sandwich, invisible)
- Attack Mob (bool), Hurttime check (bool), Attack delay 0–20 ticks
- Visuals (range ring, target highlight): proxy-external menu display only.

Target pipeline (relay side):
1. Track entities from `AddPlayer/AddActor` + `MoveActor*` packets; own
   position from the game's echoed `PlayerAuthInputPacket` (+ StartGame spawn).
2. Filter (alive, not self, players unless Attack Mob), range-check 3-D dist.
3. Sort by distance; tick scheduler = attackDelay; hurttime via `ActorEventPacket`.
4. Compute rotations: `yaw = atan2(dz,dx)*180/π − 90`, `pitch = −atan2(dy,√(dx²+dz²))*180/π`.
5. Best weapon slot: `attackDamage(item) + 1.25 × sharpnessLevel` per hotbar
   slot (needs a static MC item-damage table in Kotlin + inventory mirror from
   `InventoryContentPacket`).
6. Craft: silent-switch sandwich → `InventoryTransactionPacket` (attack) →
   rotations in next `PlayerAuthInputPacket`.

Melody extras worth stealing later: Strafe rotation mode, target visualization
colors, "seenPercent" wall-check once a chunk cache exists (Tier B upgrade).
