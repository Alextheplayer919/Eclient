# Hybrid-lite — packets are the brain, the game is a read-only sensor

**Decision (2026-10):** Eclient stays a packet proxy. The in-game agent (Eclient-Attach) is an
*optional, read-only sensor* that reports the one thing packets cannot carry — what the camera
is really doing — and is used as a referee for the proxy's own assumptions.

```
 Minecraft ── packets ───────────────────────────► Eclient relay ──► server   (state, actions, movement)
 Minecraft (patched, optional) ── camera + pose, read-only, ~20×/s ──► Eclient app
```

Stock Minecraft + the app still works exactly as before. The agent only makes things *better*.

## Who owns what

| Thing | Owner | Why |
|---|---|---|
| Self position, rotation, inventory, entities, blocks | **packets** (`EntityTracker`) | already on the wire; no offsets to maintain per game update |
| Every action (combat, inventory moves, placement) | **packets** | one choke point, version-proof |
| Movement | **packets** | by design: it must be sequenced against `PlayerAuthInput` ticks |
| Camera: FOV, pose, view-projection matrix | **agent** (when it has derived it) | the only thing the wire cannot tell you |
| Ground truth for self position | **agent → audit only** | used to *check* the tracker, never to write it |

Nothing from the agent writes `EntityTracker`. That is what removes the old "two writers on one
map" rule, so the relay and the agent can run together.

## Modes (`Backends.Mode`)

| Mode | What runs | When |
|---|---|---|
| `PROXY` | relay only | stock Minecraft, no agent answering |
| `HYBRID` | relay (brain) + agent as read-only sensor | **default whenever an agent answers** |
| `MEMORY` | legacy: agent feeds the tracker, no relay | opt-in via `AgentRuntime.preferMemory = true` |

## The camera, in layers (`RenderCamera.project`)

Each layer is used only if present **and fresh** (≤ 300 ms). A broken derivation after a game
update degrades the ESP one layer; it never kills it.

| # | Source | Needs from the agent | Notes |
|---|---|---|---|
| 1 | `SENSOR_MATRIX` | `camera.vp` | no guesses at all |
| 2 | `SENSOR_POSE` | `camera.fovY` + `eye` + `yaw`/`pitch` | works in third person too |
| 3 | `SENSOR_FOV` | `camera.fovY` | **cheapest to derive; fixes the FOV guess.** Third person is hidden (no pose) rather than misplaced |
| 4 | `PACKETS` | nothing | the original estimate; byte-for-byte the old behaviour |

`MathUtil.worldToScreen` keeps its signature and now delegates here, so ESP, TargetESP, Xray and
ChunkFinder all benefit without edits. A unit test proves layer 4 is bit-identical to the old code.

### Layer 0 — the audit (works today, no new reverse engineering)

The agent already reports a derived self position. `SensorAudit` compares it with the tracker on
every push and answers a question the code could not answer by itself:

> The ESP used to add a fixed `+1.62` to the tracker's Y, but `EntityTracker` documents that
> `PlayerAuthInput` positions are already the **eye** height (`selfYFrameIsEye`). If so, the
> camera was 1.62 blocks too high.

`tracker.Y − agent.Y ≈ 1.62` while the tracker claims eye frame ⇒ confirmed. In `AUTO` mode (the
default) the correction is applied **only after it has been measured**, so a proxy-only user sees
no behaviour change.

## Contract v1 — `camera` in the agent's state push

Every field is optional; send only what is derived. Absent object ⇒ the app uses packets.

```json
"camera": {
  "fovY": 74.5,                                  // degrees, VERTICAL, as the renderer uses it right now
  "mode": 0,                                     // 0 first person, 1 third person back, 2 third person front
  "eye": {"x": 123.5, "y": 65.62, "z": -88.25},  // camera position, world space
  "yaw": 137.5, "pitch": -4.2,                   // same convention as the Bedrock packets, degrees
  "viewport": {"w": 2400, "h": 1080},
  "vp": [ /* 16 floats, COLUMN-major, OpenGL clip space: clip = VP * [x y z 1] */ ]
}
```

`caps` may include `CameraFov`, `CameraPose`, `CameraMatrix` (informational; the app decides by
which fields are present). Rules that do **not** change: token on every line, no `readMem` /
`writeMem`, unresolved ⇒ omit (never guess), the app never crashes on a missing field.

The agent no longer needs `attack`, `setRotation`, `setInput`, `toggleModule` or `setSetting`;
sensor-only builds should refuse them. No gameplay logic lives in the game.

### Derive in this order (each step is useful on its own)

1. **`fovY`** — one float. Removes the biggest guess (see below).
2. **`mode`** — first/third person.
3. **`eye` + `yaw`/`pitch`** — per-frame camera pose (includes sneak height and bobbing).
4. **`vp`** — the full matrix.

*How you will know it is right:* `.camera` shows the active source; with the ESP on, a known
block should sit on its on-screen position at the screen edges as well as the centre (FOV errors
show up at the edges first). A point `eye + forward·10` must project to within ~3% of the screen
centre in first person.

## What this can and cannot fix (honest limits)

* It fixes **systematic** error: wrong FOV, the eye-height frame, sprint/speed FOV changes, third
  person. It does **not** fix latency: the agent samples at ~20 Hz, so fast camera turns can lag
  by up to ~50 ms plus the loopback hop. Less lag needs a higher push rate sampled on the render
  thread, or drawing inside the game (which was removed on purpose).
* The `GameFov` default (110°) and whether the in-game slider is a vertical or horizontal angle
  are **unverified guesses** today. Layer 3 replaces the guess with the real value.
* The sensor is only available on the patched, exact-version build. Stock users stay on layer 4.

## Try it on the phone (3 minutes)

Type these in Minecraft chat while joined through the relay (replies are local-only):

| Command | What it does |
|---|---|
| `.camera` | active projection source, sensor contents, eye-height fix state, engine/agent status |
| `.camera audit` | the agent-vs-tracker measurements (needs the patched game running) |
| `.camera fix on` / `off` / `auto` | flip the eye-height correction live and compare the ESP by eye |
| `.camera fov <30-130>` | set the packet-path FOV while there is no sensor FOV |

The same audit line is written to `Downloads/baba.txt` every 5 s (`audit samples=…`).

## Tests

`app/src/test` covers: bit-identity of the legacy projection, matrix-vs-pinhole agreement,
layer selection (staleness, third person, behind-camera), the eye-height decision in every mode,
the JSON parser's defensive behaviour, and the audit's verdicts. They run on every push via
`.github/workflows/tests.yml` (separate from the APK/signing workflow).

## Follow-ups (not in this change)

* Persist the verified eye-frame result so proxy-only sessions benefit after one hybrid session.
* A tap-to-calibrate wizard (fit FOV + eye height from a few taps) as a no-agent alternative.
* Push `camera` at 60 Hz from the render thread if latency matters.
* Link reliability, Android platform risks, native-side requirements and the blueprint for a memory-based
  KillAura: `docs/BRIDGE.md`.
