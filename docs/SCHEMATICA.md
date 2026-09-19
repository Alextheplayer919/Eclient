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
- No .litematic / legacy .schematic yet (easy to add next to .schem).
- Shell-only rendering (interior hidden block faces get no points) — this is a
  perf feature, not a bug: a 10k-block castle renders as ~1–2k points.
- Block *states* (stairs orientation etc.) are dropped — the ghost shows shape,
  not orientation.
- Repaint interval changes need a re-toggle (loop starts on enable).
