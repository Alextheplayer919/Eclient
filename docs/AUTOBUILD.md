# Auto-build (block printer) — design & status

Server-authoritative placement printer for the Schematica ghost. **Client-side
visuals only exist today; the auto-builder itself is not live yet.** This doc
is the single source of truth for how it will work, what is verified, and
what must be captured before any placement code ships.

Companions: `core/schem/BlockIdMap.kt` (java→bedrock conversion),
`core/schem/BuildBlocks.kt` (planner: classes, click points, tracking),
loader `states` support in `core/schem/SchematicModel.kt`.

Reference build: Modern Office Building Shell (59×68×36, 16,703 blocks,
44 block names / ~100+ full states).

## 1. Phase plan (unchanged)

1. **Phase 1 — hotbar only, simple blocks (92.6% of the reference build).**
   SIMPLE + AXIS + AUTO_CONNECT: plain blocks, pillars, froglights, walls, iron bars.
2. **Phase 2 — oriented blocks.** Stairs, slabs, trapdoors (correct click
   height; for stairs also player yaw). Only after Phase 1 works and the
   state keys are confirmed in captures.
3. **By hand.** Torches, plants, beacons, sculk shriekers, potted plants.
   The ghost should light those in a different colour (future ghost mode).
4. **Phase 3 — restocking** from the main inventory (`ItemStackRequestPacket`
   take/place/swap), pattern copied from real client captures.

Anti-mess rules (unchanged): **one placement per tick and at most one in
flight per cell** (`PlacementTracker`), 4.5-block reach gate, safe supports
only (no air/liquid/plants/screen-opening blocks), bottom-up serpentine
build order, verify against `UpdateBlockPacket` with 3 retries, then
FAILED-mark, 3-fail circuit breaker, never guess the held item (tracker-fed),
and check the target server’s rules first — many ban printers.

## 2. Fact verification table

| Claim | Status | Evidence |
|---|---|---|
| `PlayerAuthInputPacket` can carry item-use transactions, block actions, item-stack requests | ✅ verified | Cloudburst `PlayerAuthInputData` enum; bits are ordinal positions: `PERFORM_ITEM_INTERACTION = 34`, `PERFORM_BLOCK_ACTIONS = 35`, `PERFORM_ITEM_STACK_REQUEST = 36` (checked in-source, 3.0 branch, matches the cheat-sheet) |
| `InventoryTransactionPacket` ITEM_USE is the alternative placement path | ✅ verified (both real) | Standard bedrock flow; which one a session uses is negotiated (`StartGame`: server-authoritative inventories) — hence dual capture below |
| Server-authoritative inventory handshake (`ItemStackRequest/Response`) | ✅ verified | `StartGame` advertises; server answers `ItemStackResponsePacket`, rejected requests revert |
| `MobEquipmentPacket` = slot switch with runtime id + item + slots + windowId | ✅ verified | pmmp/Cloudburst class docs; the tracker (below) is mandatory — the item field must match what the server thinks the slot holds |
| **Action-type constants for click-block ("0")** | ⚠ corrected | Do **not** trust from memory: PocketMine currently uses different constants across versions and Cloudburst encodes a *legacy action type* whose numbering is version-dependent. Phase 0 captures the real client’s values and the builder replays those byte-shapes verbatim, not documentation |
| Bedrock state-key names in `BlockMapper.matches` (`pillar_axis`, `minecraft:vertical_half`, `upside_down_bit`) | ⚠ not yet verified | Confirmed from captured `UpdateBlock` data during Phase 0, then filled in |
| Java id set size | ✅ 1,168 ids | minecraft-data `data/pc/26.1` |
| Edition difference list in `BlockIdMap` | ✅ data-driven | Curated against GeyserMC’s live mapping (`blocks.nbt` — what their translator physically sends to Bedrock clients), not recollection |

## 3. The converter — `BlockIdMap`

Resolution order: air variants → `RENAME` → `UNSUPPORTED` (null target) →
identity. Bedrock item ids via `ITEM_RENAME` with identity default.

- **Coverage:** any Java block id a schematic can contain; most map
  identically (editions share the vast majority of names).
- **Data sources (no guessing):** full java registry for the id set;
  GeyserMC `blocks.nbt` extract for rename evidence
  (`brick_block→bricks`, `oak_door→wooden_door`, `grass→short_grass`,
  `wall_torch→torch`, `<wood>_sign→<wood>_standing_sign` + oak/dark-oak
  special cases, `<color>_bed→bed`, `<color>_banner→standing_banner`,
  heads→one `skull` id, coral wall fans→`coral_fan_hang{,2,3}`,
  `stone_stairs→normal_stone_stairs`, `daylight`/`lit_*` legacy list for
  the lit-state families, `frogspawn→frog_spawn`, `dirt_path→grass_path`…).
- **VERIFY-flagged** (confirmed in Phase-0 captures before autobuild relies
  on them): wall-corals hang order, `waxed_copper` naming, candle/coral item
  ids, banner item ids, wall-hanging-sign identity, blackstone wall identity,
  `trapdoor` naming for oak, tall-plant double-plant handling, and every
  bedrock state key used in `BlockMapper.matches`.
- **Truly unsupported** (routed to by-hand): `fire_coral_wall_fan`,
  portal/gateway/end blocks, `moving_piston`, `test_block`, `structure_void`,
  plus legacy combined ids whose color data the legacy loader drops
  (`wool`, `stained_glass(_pane)` … → conservative alias, documented gap).

**Known phase-2 gaps (deliberate):**
- **State-level translation is its own table-to-table problem** (java
  `facing=north` ↔ bedrock `minecraft:cardinal_direction` / `facing_direction`,
  double slabs becoming `*_double_slab` ids, lit furnaces/lamps becoming
  `lit_*` ids, repeater/comparator power becoming separate ids, upper-half
  of `double_plant`). Geyser’s `blocks.nbt` *is* that table — it will be
  converted to a compact embedded table at Phase 2, after Phase-0 captures
  validate the wire values.
- **Legacy `.schematic` `Data` byte is still ignored** by the loader (colour
  states collapse to a representative block) — same as today for ghosts.
- Legacy `.schematic` colour states keep the combined-id caveat: `wool`,
  `stained_glass`, `stained_hardened_clay` place as the mapped default
  (white wool / terracotta / glass). Documented, accepted for now.

## 4. Phase 0 — calibration captures (do this first)

The auto-builder must replay the **real client’s wire shapes**, only changing
position/face/click fields. Procedure (uses the existing MISC →
**PacketCollector** module — no new infra):

1. Join a test world (stay grounded at spawn).
2. Enable **PacketCollector** (settings: Detailed Log ON, Exclude Spam ON).
3. Place *by hand*: one plain block, one pillar, one wall, one top slab,
   one bottom slab, one stair facing each direction (4×), one top and one
   bottom trapdoor, break them all.
4. Also: switch hotbar slots once, and over-fill one stack to trigger one
   `ItemStackResponsePacket`.
5. Disable PacketCollector. The session files land in the queue-log files dir
   (module description says where; separate files per packet class).
6. In the captures, confirm & record per §the verifier:
   - which placement path the session used (`PlayerAuthInput.flag 34` +
     `itemUseTransaction`, vs `InventoryTransactionPacket` ITEM_USE)
   - the real field values of the item-use transaction for a plain block
   - `MobEquipmentPacket` slot-switch shape + the 1-tick between switch/click
   - bedrock orientation keys in the returned `UpdateBlockPacket`
     (fills every `// VERIFY` in `BlockMapper.matches`)
7. Only then does placement code get enabled — replaying captured shapes.

## 5. What this turn delivered

- `core/schem/BlockIdMap.kt` — the complete converter (+ `// VERIFY` audit trail).
- `core/schem/BuildBlocks.kt` — planner (classes, faces, click heights,
  serpentine build order, `PlacementTracker`, materials list).
- Loader `states`: `SchematicModel` now carries full `JavaState`s
  (name + properties) alongside the name-only palette; `.litematic` reads
  palette `Properties`, `.schem` parses `name[prop=val]` keys, `.mcstructure`
  and legacy get identity states. Ghost rendering is unchanged.

## 6. Server-rules caution

Printers are banned on many servers and the tps/flag cost of doing this
fast is real. Phase-1 pacing stays conservative: one placement per tick,
verify-every-placement with a 3-strike circuit breaker, and run-ins only on
servers where the user has confirmed printers are tolerated (own SMP /
creative-anarchy style).
