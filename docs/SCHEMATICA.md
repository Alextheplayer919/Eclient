# Schematica (Eclient module)

Proxy-edition ghost-build renderer — dotted particle hologram of a schematic
anchored at your position. **Client-side visuals only; the server sees nothing.**

## Quick start

**Dashboard (easiest):** bottom bar → **Schematics** tab (cube icon, between
Configs and Accounts). **Import** button sits exactly where Configs' Import is —
picks any file via the system file picker and copies it into the schematics folder.
The tab lists every file on device: tap to select (chat-status confirms), an
**ACTIVE** chip marks the current one, Delete cleans up, Refresh rescans. The
pseudo-row "Newest file (auto)" restores blank/auto behavior. The folder path
is shown at the bottom of the tab.

**Manual:**

1. Drop files into the schematics folder (any file manager works):
   `/sdcard/Android/data/<package>/files/schematics/`
   (fallbacks also scanned: `Documents/Eclient/schematics`, `Download/Eclient/schematics`)
2. Supported formats (4):
   - **`.mcstructure`** — native bedrock (export with a structure block, or download)
   - **`.schem`** — Sponge/WorldEdit v2 and v3 (gzip NBT)
   - **`.schematic`** — legacy MCEdit/Classic (numeric IDs; ~70 mapped, unknown ghost as stone)
   - **`.litematic`** — Litematica (multi-region merged; gzip or plain NBT autodetected)
3. In the client: **MISC → Schematica** → enable.
   - With **File** blank: loads the *newest* file in the folder.
   - With **File** set: loads that exact filename.
4. The ghost anchors **at your feet when the module is toggled on** —
   walk to where the build should go, then toggle off→on. Status goes to chat.

## Settings

| Setting | Meaning |
|---|---|
| File (blank = newest) | Filename in the schematics folder |
| Render Style | **WIREFRAME** (crisp per-block boxes, ServerScriptDebugDrawer — needs Minecraft 26.20+, recommended 26.40) / PARTICLES / BOTH — below 26.20 wireframe gracefully falls back to particles with a one-time chat notice |
| Marker Particle | FLAME / BLUE_FLAME / BALLOON / HEART / NOTE |
| Layer (0 = all) | Show only one Y layer (1-based) — layer-by-layer building |
| Max Points | Ghost density cap (50–1500, default 350) — closest blocks win |
| Repaint ms | How often the ghost re-paints (250–2000, default 600) |
| Nudge X/Y/Z | Anchor offset from your position (−64..+64) |

After changing File/Layer/Nudge, re-toggle the module (settings apply on enable).

## Design rule: why particles, not fake blocks

A true Schematica-style translucent-block hologram can't exist in a *proxy*
client (no GL access), and faking it with clientbound block packets would
**corrupt the client's own collision model**: step onto a fake floor → the client's
claims float where the server sees air → corrections and flags. That fights the
entire NoLagback claim-honesty line.

`SpawnParticleEffect` is the opposite: goes clientbound only, no collision, no
server visibility, no claim interaction. Zero flag risk by construction.
The proxy is also uniquely good at this feature among bedrock clients: it already
owns the world model (WorldBlockTracker) and can inject clientbound packets.

## Rendering API research (round: client-side block visuals)

| Primitive | Status | Verdict |
|---|---|---|
| **Particles** (`SpawnParticleEffect`) | ✅ shipped v1 | Zero-risk dots; faint but safe. |
| **ClientboundDebugRendererPacket** (Mojang legacy debug overlay) | ❌ removed (kills session) | Packet ID 164 no longer exists in current retail clients — the client's packet table has no handler for it and the game tears the session down on the first byte. Never send. |
| **ServerScriptDebugDrawer** (ID 328, hand-encoded BOX shapes via `UnknownPacket`) | ✅ shipped v3 | Crisp per-block boxes, one batched packet per repaint (~350 shapes in ~15 KB), stable shape ids for in-place refresh, explicit removal on disable, self-expiring TTL backstop. Cloudburst's own `DebugDrawerPacket` class is `java.awt.Color`-bound and cannot compile on Android, so the bytes are written by hand (`core/schem/DebugDrawerBoxes.kt`), cross-checked byte-for-byte against Cloudburst's serializer source chain (v818→v859→v924→v975→v1001→v2168). Supported wire layout: the v975-chain BOX path → **protocols 975/1001/2168 = Bedrock 26.20–26.44** (26.45+ moved to a new layout — not wired up yet). |
| **Falling-block entities** | ❌ rejected (researched) | Bedrock's `falling_block` appearance control from pure packets is buggy/unreliable (bedrock.dev: people fake it with block+entity combos instead). Java display entities don't exist on bedrock. |
| **Clientbound fake blocks** (`UpdateBlock` injection) | 🧪 future, needs safety gate | Physics-poisons the client's own collision → claim lies → flags. Only viable with a proximity gate (fake blocks far away, downgrade to boxes nearby). Complex bookkeeping: server updates constantly overwrite; must repaint at sub-chunk granularity. |
| **Native GL hook via attach** | 🔒 blocked on round-3 device experiment | True translucent Schematica-style hologram. Requires `libeclient_attach` Phase A GO to inject rendering into the game process. |

## Current limits / roadmap

- **No printer** (auto-place) — server-auth inventory + placement packets make
  this a separate project; v1 is display-only. Ask if wanted.
- Formats moved off this list — all four are supported now: `.schem`, `.litematic`, `.mcstructure`, legacy `.schematic` (see Quick start + Fix record below).
- Shell-only rendering (interior hidden block faces get no points) — this is a
  perf feature, not a bug: a 10k-block castle renders as ~1–2k points.
- Block *states* (stairs orientation etc.) are dropped — the ghost shows shape,
  not orientation.
- Repaint interval changes need a re-toggle (loop starts on enable).

## Fix record

### .litematic block-count mismatch / scrambled ghost (fixed in 07dcf95)

**Symptom:** `Modern_Office_Building_Shell-from-abfielder.litematic` reported
18,136 blocks / 18,053 shell points; the file's own metadata says 16,703.
Dimensions (`Size` tag) were correct; blocks were visibly scrambled after the
first stretch of the build.

**Root cause:** the decoder read the `BlockStates` long array in the **padded**
modern chunk-section layout — `perLong = 64 / bits` entries wholly inside one
long, `bits = max(2, ceil(log2(palette)))`. Litematica instead packs
**tightly**: entry N starts at bit `N * bits` of the bit stream and **may
straddle two longs** (LSB-first, no per-long alignment). For palette bit widths
that divide 64 evenly (2/4/8/16/32/64) the two layouts are byte-identical,
which is why the bug stayed hidden until a 7-bit palette hit a large build.

Consequences with a 7-bit palette (125 states), 144,432 cells:

- Every entry after the first long is read at the wrong bit offset → wrong
  palette indices → scrambled blocks + inflated solid count.
- The padded reader also stops early: the file carries
  `ceil(total * bits / 64)` longs (**15,798**); the padded reader "expects"
  16,048, so the last ~2,250 cells never get decoded (142,182 of 144,432).

The exact `ceil(total * bits / 64) == longArray.size` invariant is the
format-level proof: a tight 7-bit stream of 144,432 cells yields exactly
15,798 longs — the file's own array length — while the padded layout is
provably bigger.

**Fix:** bit-indexed decode (`bitIndex = cell * bits`, carry across longs via
`packed[li+1] shl (64 - off)`), `perLong` removed, stale comments corrected in
`core/schem/SchematicModel.kt`.

**Verification (three independent routes):**

1. Reporter's independent parse (gzip + NBT, tight bit-pack): solid count
   16,703 == file metadata `TotalBlocks` 16,703.
2. Long-count arithmetic above.
3. Synthetic round-trip at the same parameters (7 bits, 144,432 cells):
   tight encoder produced exactly 15,798 longs; the old loop decoded exactly
   142,182 cells (matching the observed buggy behavior); the corrected loop
   recovered **144,432 / 144,432** cells bit-exactly.

**Result after fix (on the reported file):** 16,703 blocks / 15,924 shell
points. `.schem`, `.schematic`, `.mcstructure` were never affected (separate
decoders).
