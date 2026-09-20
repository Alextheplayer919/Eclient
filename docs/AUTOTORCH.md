## AutoTorch — `module/world/AutoTorch.kt`

Places torches on dark, mob-spawnable floors near the player.

### Bedrock spawn mechanics (researched)

- Overworld hostiles: spawn requires **block light == 0** (and effectively
  sky light < 7). Any block light at all denies the spawn. Nether hostiles
  tolerate block light ≤ 11 (out of scope for v1 — see limits).
- A torch emits light 14; block light decays by exactly 1 per taxicab step —
  one torch spawn-proofs a 13-block radius on flat unobstructed ground.
- Mobs never spawn within 24 blocks of any player, so AutoTorch's purpose is
  a **lit trail**: secure the areas you pass through *before you leave
  them*, and your base. It is not a combat-time defensive pop.

### The light-data problem — and the chosen solution

**Bedrock sends NO light arrays on the wire.** Unlike Java chunk packets,
`LevelChunkPacket` contains subchunk block storages + biomes only (verified
against gophertunnel/dragonfly network decoders) — Bedrock clients relight
locally in the render pipeline. Client-side light from packets therefore
doesn't exist.

So AutoTorch computes block light **itself** from `WorldBlockTracker`'s
real block-id map (already maintained from `LevelChunk`/`SubChunk`/
`UpdateBlock` packets — same source every other module trusts):

1. one tick (default 600 ms) computes a **multi-source BFS** flood over the
   27×27×27 region around the player: seeds = every emissive block
   (`EMITTERS` table: torch=14, lantern=15, lava=15, …), −1 per taxicab step,
   flood stops at opaque ids;
2. candidate cells = non-solid + headroom + solid spawnable floor below +
   within reach (default 4.2, max 4.6) + **simulated light == 0** + not the
   player's own cell + not placed in the last 5 s;
3. one torch placed per tick (nearest candidate), through the proven
   `PlacementUtil` chain (hotbar prepare → ITEM_USE air-gesture on the
   floor's top face, blockFace=1 → revert). Prefers `minecraft:torch`, falls
   back to `minecraft:soul_torch`.

### Conservative bias (documented approximation)

Ambiguous blocks (slabs, stairs, fences, doors…) are treated as **opaque** —
the flood leaks *less* than reality. Uncertain emitters use their **minimum**
emission (candle=3, not 12). Both choices bias toward placing **more**
torches, never too few. Worst case is a prettier base; never a spawned mob
caused by the module. Full-cube-glass corners are the known under-light case
(glass passes light correctly, but wall-shading around glass overhangs can
over-torch slightly).

- Also counts as already-lit: any `WorldBlockTracker`-known emitter — so if
  the player or teammates place light sources, AutoTorch skips the area on
  its next flood (5 s per-position cooldown covers placement ACK latency).

### Settings

`Tick ms` (300–2000, default 600) · `Reach` (2.5–4.6, default 4.2) ·
`Vertical scan` (1–6, default 3). Changes apply on next module toggle
(the loop period is read at enable).

### Standing limits (v1)

- Nether spawns (light ≤ 11) not targeted — module floods for `light == 0`.
- Sky light unknown (no world-time/light on wire) — dark at night on the
  surface trivially works; daytime-surface "false dark" simply costs a torch.
- Emission table covers common sources; exotic modded sources count as dark
  (safe direction).
