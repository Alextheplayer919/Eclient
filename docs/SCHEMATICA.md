# Schematica (Eclient module)

Proxy-edition ghost-build renderer — dotted particle hologram of a schematic
anchored at your position. **Client-side visuals only; the server sees nothing.**

## Quick start

1. Drop files into the schematics folder (any file manager works):
   `/sdcard/Android/data/<package>/files/schematics/`
   (fallbacks also scanned: `Documents/Eclient/schematics`, `Download/Eclient/schematics`)
2. Supported formats:
   - **`.mcstructure`** — native bedrock (export with a structure block, or download)
   - **`.schem`** — Sponge/WorldEdit v2 and v3
3. In the client: **MISC → Schematica** → enable.
   - With **File** blank: loads the *newest* file in the folder.
   - With **File** set: loads that exact filename.
4. The ghost anchors **at your feet when the module is toggled on** —
   walk to where the build should go, then toggle off→on. Status goes to chat.

## Settings

| Setting | Meaning |
|---|---|
| File (blank = newest) | Filename in the schematics folder |
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

## Current limits / roadmap

- **No printer** (auto-place) — server-auth inventory + placement packets make
  this a separate project; v1 is display-only. Ask if wanted.
- No .litematic / legacy .schematic yet (easy to add next to .schem).
- Shell-only rendering (interior hidden block faces get no points) — this is a
  perf feature, not a bug: a 10k-block castle renders as ~1–2k points.
- Block *states* (stairs orientation etc.) are dropped — the ghost shows shape,
  not orientation.
- Repaint interval changes need a re-toggle (loop starts on enable).
