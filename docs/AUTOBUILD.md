# Auto-build (block printer) — design & status

Server-authoritative placement printer for the Schematica ghost. **Status:
v1 is LIVE behind the `Auto Build (v1)` toggle in the Schematica module
(default OFF)** — orientation-free classes (SIMPLE / AXIS / AUTO_CONNECT)
**plus, since v2a, single slabs in both halves** (half chosen by click height
0.25/0.75 — no yaw assist), one placement per tick, 4.6 reach, claim-verified
+ circuit-breakered. Stairs/trapdoors/`type=double` slabs are still by-hand
(facing determinism — Phase 2b). This doc is the single source of truth for
how it works, what is verified, and what still needs captures.

**Field-validated (2026-09-19):** user ran Auto Build v1 on a real server and
completed a hut with zero placement issues — confirms the reused combat-aura
ITEM_USE wire shape (ITx `actionType=0 CLICK_BLOCK`, consumption action
record, in-block-relative click position), the bottom-up build order under the
no-support-miss tolerance, world-state verification, and the circuit-breaker
safety stack end-to-end.

**v2a (2026-09-19, same-day):** single slabs admitted (both halves via click
height only); rich per-cell verification added — sent cells are matched
against the `UpdateBlockPacket` definition (name + states). Wrong-half slabs
cannot be silently accepted, and any pre-existing slab whose half the engine
cannot determine is routed to the player (marked by-hand) instead of being
compounded into a double slab. Stairs/trapdoors still excluded (facing needs
players-yaw determinism — Phase 2b).

**v2b (2026-09-20) — external fix series applied verbatim** (reviewed
`BuildBlocks.kt`, `AutoBuilder.kt`, `Schematica.kt` replaced from the author's
upload, tagged BB-1..7 / AB-1..15 / SC-1..6 in code): SIMPLE is default-deny
(benign-prop table; chests/levers/rails → by-hand), wall torches classed
SPECIAL, horizontal AXIS columns, fence/pane AUTO_CONNECT, door/bed/single-slab
resource counts fixed, in-flight-capped sends (max 3) with every timeout
counted as a failure, **circuit breaker now resets only on server confirmation
(never on a bare send)**, reach measured to the planned click point, safe-
support filter (never GUI-opening blocks; strict mode rejects partial blocks —
no slab-merge clicks), player-hitbox cells skipped until you step away,
occupied cells fail-fast without retry/breaker cost, dimension-change pause,
`dataLayer≠0` UpdateBlocks ignored (liquid layer), replaceable blocks mean
"no answer yet", live Auto Build toggle (no module restart), reconnect drops
the stale engine, ghost no longer draws confirmed cells, tick floor raised to
100 ms (default 150). Deferred upstream notes kept: PlacementUtil always
reverts hotbar after each block (optimization pending), waterlogged blocks
placed dry, `upside_down_bit` (stairs/trapdoors) dormant until Phase 2b.
Awaiting field test (suggested smoke: `Dirt_Hut.litematic` — expect the anchor
cell skipped while you stand in it, then a finish message).

Companions: `core/schem/BlockIdMap.kt` (java→bedrock conversion),
`core/schem/BuildBlocks.kt` (planner: classes, click points, tracking),
`core/schem/AutoBuilder.kt` (v1 placement engine),
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
| **Action-type constants for item-use transactions** | ✅ verified | `0 = CLICK_BLOCK`, `1 = CLICK_AIR`, `2 = BREAK_BLOCK` — consistent across sel-utils bedrock protocol references (1.6.0 era through current), sandertv/gophertunnel, and PocketMine-MP `UseItemTransactionData`. Placement sends **0** (click on the support block). Matches the cheat-sheet AND the working combat-aura wire shape |
| Java serverbound placement (`use_item_on`) | ✅ verified | minecraft.wiki Java protocol: Hand enum (0 main / 1 off), Location Position, Face varint (`0=down…5=east`), Cursor Position X/Y/Z floats **0..1 in-block relative**, Inside Block bool, World Border Hit bool (1.21+), Sequence varint (server echoes it in Acknowledge Block Change). Same click-point model as Bedrock — the planner's `clickPoint` triples are valid for both editions |
| `PlayerAuthInputFlags` bit numbers | ✅ verified (independent) | PocketMine-MP `PlayerAuthInputFlags.php`: `PERFORM_ITEM_INTERACTION = 34`, `PERFORM_BLOCK_ACTIONS = 35`, `PERFORM_ITEM_STACK_REQUEST = 36` — matches the Cloudburst ordinal enumeration and the cheat-sheet numbers |
| `InventoryTransactionPacket` still legitimate on modern versions | ✅ verified | minecraft.wiki Bedrock protocol + PMMP 4.18 changelog: since 1.16 `ItemStackRequest` replaces it **only for inventory-internal actions** (move/craft/drop flows); ITx ITEM_USE remains the client path for *placing blocks / using items / interacting with entities*. PMMP also accepts the same transaction embedded in `PlayerAuthInputPacket` (flag 34) — both shapes accepted server-side |
| Item-in-hand `netId` inside placement transactions | ⚠ observed quirk (Geyser) | Geyser PR #3083: the vanilla client sends **netId = 0** in `InventoryTransactionPacket` (not the server-assigned stack id). Auras work today sending the tracked netId; if a strict server rejects placements, mirror vanilla with `itemInHand.toBuilder().netId(0).usingNetId(false)` |
| Bedrock state-key names in `BlockMapper.matches` | ✅ data-resolved (generated) | `BlockStateMap.kt` is generated from GeyserMC `blocks.nbt` (feat/26.1, aligned to pc/26.1's 29,873 global blockstate ids): `pillar_axis`, `minecraft:vertical_half`, `upside_down_bit`, `weirdo_direction` etc. are no longer guesses — they come out of the translator data itself. `matches` checks every emitted key; live `UpdateBlockPacket` captures (v2a) supply the actual states. Hand keys remain only as fallback |
| Java id set size | ✅ 1,168 ids | minecraft-data `data/pc/26.1` |
| Edition difference list in `BlockIdMap` | ✅ data-driven | Curated against GeyserMC’s live mapping (`blocks.nbt` — what their translator physically sends to Bedrock clients), not recollection |

## 2b. The state map — `BlockStateMap` (generated, machine-sourced)

Every Java block id **and its state data** is now convertible. Instead of
hand-written tables, `tools/gen_block_statemap.py` distils GeyserMC's
translator mapping (`mappings` repo, `feat/26.1` branch `blocks.nbt` — a
sparse list indexed by the Java global blockstate id) crossed with
minecraft-data pc/26.1's registry (which fixes each block's state ids). Two
anchors validate the alignment: `defaultState` of `smooth_stone_slab`
(min+3 with bools ordered true-first) and `sea_pickle` (default at min).

- **NAME_OVERRIDES (177)** — uniform java→bedrock id renames
  (`bricks→brick_block`, wall torches→`torch` etc.). State-dependent
  ids (slab→double_slab, furnace→lit_furnace, inverted daylight
  sensor) are deliberately NOT here — they live in COMPLEX_STATES keyed by
  java state, so a single slab never collapses into its double id.
- **PROP_RULES (545 blocks)** — per-block separable mappings
  (`oak_stairs.facing→weirdo_direction{n=3,e=0,s=2,w=1}`,
  `half→upside_down_bit`, logs `axis→pillar_axis`, sea_pickle
  `waterlogged→dead_bit` — the dry-pickle-is-dead rule literally falls
  out of the data).
- **COMPLEX_STATES (127 blocks / 2,766 states)** — full keyed tables where
  separation fails (slabs incl. `minecraft:vertical_half`, beds/banners,
  water still/flowing).
- Absence semantics: a missing rule/state means *unconstrained*, not default
  (bedrock derives e.g. stair `shape` at runtime).

Delivery: the tables are emitted as a gzipped NBT asset
(`app/src/main/assets/schem/blocks_statemap.nbt`, ~27 KB) and loaded lazily by
`core/schem/BlockStateMap.kt` — a 600 KB Kotlin `mapOf` literal made the
compiler crawl (19 min debug builds), so the data ships as DATA, not source.
Regenerate: `python3 tools/gen_block_statemap.py` (network + `pip install
nbtlib`; self-tests bricks/oak_stairs/sea_pickle/slabs on the written asset).
Deterministic inputs are pinned in the script header.

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
