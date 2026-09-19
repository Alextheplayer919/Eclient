# NoLagback v3 — Design Draft
*Status: DRAFT for review — no v3 code exists yet. v2.x shipped: governor + calibration + climb guard + smart resync (`main` @ 2972df6).*

---

## 0. Why a v3

v2 treats corrections as *pressure and anchors*. The protocol research below shows corrections are actually a **tick-addressed, rate-limited, budgeted negotiation** with a well-defined answer channel. v3 plays that negotiation by its real rules instead of approximating them.

---

## 1. Research corpus

| Source | Takeaway |
|---|---|
| Mojang `bedrock-protocol-docs` — `PlayerMovementOverview.md`, `ConfiguringAntiCheat.md` | Official authority-model docs: client-predict / server-validate / rewind-replay reconciliation (ServerAuthoritativeV3) |
| `docs/CorrectPlayerMovePredictionPacket.html` | Packet 161 field semantics incl. the **Tick** field |
| `docs/ClientMovementPredictionSyncPacket.html` | Packet 322 — the client's answer channel (see §4) |
| `AntiCheatServer.properties` | Hard numbers: min-correction-delay, history size, acceptance threshold |
| Cloudburst Protocol source (vendored v2193) | Field names/types |
| BDS `server.properties` refs | Legacy anti-glitch budget system (0.3 / score 20 / 500 ms) |

### Hard numbers now on the table

| Knob | Default | Meaning for us |
|---|---|---|
| `player-rewind-min-correction-delay-ticks` | **5** | Server self-limits: ≥5 ticks between corrections. Correction storms are rate-capped by the server itself |
| `player-rewind-history-size-ticks` | **40** (2 s) | Reconciliation window; corrections older than 40 ticks apply without rewind |
| `player-position-acceptance-threshold` | **0.5** | Discrepancy ≤0.5 → **no correction at all** |
| legacy anti-glitch | 0.3 / 20 / 500 ms | Separate older score system (BDS flags without prediction V3) |
| `SyncedPlayerMovementSettings` (StartGamePacket) | carries history size | **The server tells us its own settings at join** — v3 must read them |

---

## 2. Packet map of the movement system

### C2S (client → server)

| Packet | ID | Role |
|---|---|---|
| `PlayerAuthInputPacket` | 144 | Every-tick claim: predicted pos + input bitset + `inputTick` |
| `MovePlayerPacket` | 19 | Legacy/manual position claim; modes NORMAL / RESPAWN(=RESET wire 1) / TELEPORT / HEAD_ROTATION |
| `ClientMovementPredictionSyncPacket` | **322** | **Response to received corrections**: actor-data flags, bounding box, movement attributes, **flying state** — keeps the server's simulation context consistent |

### S2C (server → client)

| Packet | Role for us |
|---|---|
| `CorrectPlayerMovePredictionPacket` (161) | **Tick-addressed correction**: Pos / PosDelta / onGround / PredictionType / **`Tick`** — the rewind anchor |
| `MovePlayerPacket` self, mode ≠ NORMAL | Hard corrections: RESPAWN=forced reset; TELEPORT=legit re-anchor (respawn, /tp, dimension) |
| `SetActorDataPacket` (39), `UpdateAttributesPacket` (29) **with non-zero Tick**, self-targeted | **Also rewind triggers** — attribute latency (sneak state etc.) can force re-simulation; v2 ignores these, v3 must anchor from them |
| `PlayerAuthInputPacket` echoes | (relay-side truth source for self pos) |
| `StartGamePacket` | **`SyncedPlayerMovementSettings`**: history size etc. — per-server constants, parsed once |

### Authority modes (StartGame / settings)
`client-auth` (client wins — no corrections at all) · `server-auth` (validate, optionally correct) · `server-auth-with-rewind` (full V3: correction + client rewind-replay). Detection of which mode a server runs = read `StartGamePacket` settings, not guesswork.

---

## 3. Correction taxonomy — not all corrections are "you flew wrong"

Per the official docs, corrections also fire for **legitimate** reasons. v3 classifies; v2 didn't.

| Class | Signature | Correct response |
|---|---|---|
| **Self-inflicted illegality** | Steady-state divergence under fly/speed, no damage/teleport nearby; repeated corrections with similar delta direction | Governor + scheduled compliance (§5) |
| **Knockback / external force** | Recent SetEntityMotion/HurtEntity for self; large *impulse* delta; correction's PosDelta has knockback magnitude | **Honor instantly**, never throttle — resisting it reads as anti-knockback flag |
| **Attribute/data latency** | Adjacent SetActorData/UpdateAttributes with Tick; tiny deltas | Honor, anchor, no governor action |
| **Teleport (legit)** | MovePlayer TELEPORT; huge step | Pure re-anchor; reset drift ledger |

Classifier heuristics live in MovementCompliance: windowed context (damage in last 500 ms?, teleport seen?, magnitude of delta), emitting `CorrectionClass` instead of raw events.

---

## 4. The packet we were ignoring: 322 (`ClientMovementPredictionSyncPacket`)

Discovery: after corrections, a vanilla client **periodically answers the server** with its simulation context (flags, bbox, movement attributes, flying state).

Mechanical consequences:
- **Dropping corrections (SILENT/v1-style) doesn't just hide snaps — it starves 322 generation**: the game never learns it was corrected, so it never syncs. The server sees a client "uncooperative to its own corrections" — a tamper tell on top of desync. This formally kills the SILENT design, as originally suspected.
- **v3 hygiene rule: never intercept what the client must answer.** Adaptive forwards corrections to the game (so genuine 322s flow), and the relay additionally *gatekeeps* spoofing modules (BypassFly ability spoof) out of 322 content: the flying-state field must not contradict recent UpdateAbilities spoofing on the wire. (Design question P3: rewrite 322's flying flag to match the wire's last claim instead of the game's belief.)

---

## 5. v3 core: the Scheduled-Compliance Planner

v2 reacts to corrections. v3's center: **make corrections unnecessary on the server's watchlist by arriving voluntarily.**

Inputs per tick (from our own claims + anchors): server-belief B (last 161 pos, replayed forward by PosDelta), own claim C, history T=40 (from StartGame settings), acceptance ε=0.5, correction-delay d=5 ticks.

**Envelope strategy.** When drifting D = |C−B| exceeds a soft target (say 0.35), plan a *convergence schedule*: emit claims that approach B in steps kept **inside the acceptance tolerance from the server's own replay** (each step ≤ ε = 0.5), spread over enough ticks that no single tick is anomalous **and** the 5-tick correction timer is never rewarded with a fresh anomaly window. Net effect: instead of the server rubber-banding us every 5 ticks under pressure, we quietly collapse the discrepancy *ourselves within its tolerance* — corrections stop arriving; flags never form. Speed governor then acts per-plan (temporary ceiling while converging), user slider restored when D ≈ 0.

**Planner states:** `FREE` (no fresh belief, D<0.35) → `CONVERGING` (envelope schedule active, ceiling = plan rate) → `CLEAN` (D≈0, calm timer) → back to FREE. Storm guard remains: corrections ≥3/s or D > hard budget → existing Smart Resync (controlled micro-snap) unchanged.

**Tick-accurate book-keeping (enabler).** Maintain our own rewind history: ring of (tick → claimed pos, inputs) sized from `SyncedPlayerMovementSettings`, keyed by `PlayerAuthInputPacket.inputTick`. On 161(T,pos,delta): replay B forward from T to *now* using our ring + PosDelta to compute *current* belief — today's code treats 161 pos as current truth, but it's the truth **at tick T**, potentially ~half the history window old. This alone makes v2's drift estimates meaningfully stale.

---

## 6. Anchor unification

One writer of server-belief, many sources:
- 161 (tick-aware, forward-replayed — primary)
- MovePlayer RESPAWN (hard reset) / TELEPORT (legit anchor, no pressure)
- SetActorData/UpdateAttributes w/ Tick (latency anchors)
- TransferPacket (re-anchor on transfer)

All feed `MovementCompliance.noteAnchor(class, position, tick, delta)`; classifier tags pressure only for self-inflicted class.

---

## 7. Server flavor detection (Calibration 2.0)

Different movement software ≠ same thresholds:
- **Vanilla BDS / Waterdog-fronted BDS**: V3 rewind; numbers as above.
- **PMMP-family**: openly position-auth, corrects aggressively on speed violations; its movement checks differ materially.
- Detection inputs: server brand string from RakNet pong/MOTD (already fetched at connect time in relay code), `StartGamePacket` movement mode + `SyncedPlayerMovementSettings`, first-correction cadence (d=5 ⇒ BDS-consistent).
- Presets per flavor: governor ceilings, envelope targets, climb budget defaults.

---

## 8. Module & settings sketch (Kotlin, combat untouched)

`NoLagback` becomes the container for all of it:
- **Mode**: `ADAPTIVE` (v3 planner, default) · `STRICT` (immediate honor-all-corrections, minimal flags, some snaps) · `AUDIT` (observe + diag only, zero intervention — the research mode) · `SILENT` removed entirely (§4 verdict: protocol-telltale), retained only behind a debug flag for A/B.
- **Settings**: Envelope Target (0.35), Convergence Ticks (auto≈computed), Climb Budget BPS (existing), Resync Distance (existing), Classifier (bool), Sync322 Policy: FORWARD/MATCH-WIRE.
- **Migration**: existing ADAPTIVE maps to planner ADAPTIVE; existing SILENT users warned *once* via module description.

Subsystems: `MovementCompliance` (evolves: anchors, classifier, history ring) · `CompliancePlanner` (NEW — envelope scheduler + tick replay) · fly modules unchanged (they keep calling the same governor API).

---

## 9. Phasing

| Phase | Contents | Gate |
|---|---|---|
| **P1** | Parse StartGame movement settings + flavor detect; anchor unification incl. tick-bearing 39/29; 322 hygiene (forward-only + wire-consistency audit of flying state); remove SILENT | No behavior regression on anarchy (proven server = never) |
| **P2** | Tick ring + belief forward-replay (kill stale-belief error) | Drift estimate matches reality under induced corrections |
| **P3** | Correction classifier + envelope planner (states + convergence schedule) | Induced-correction storm collapses in planner without Smart Resync firing |
| **P4** | Flavor presets + calibration 2.0 persistence | Same host re-enters at learned presets |

Each phase ships green on main independently; P3 is the "giant" one.

---

## 10. Ethics/limits (unchanged, restated)

Planner minimizes detection surface of movement modules; it cannot make server-illegal geometry legal per se (§0 rule holds: evade the *budget*, not physics itself). Network-behavioral fingerprints (Timer, packet cadences) are out of v3 scope. Anticheat plugins with custom heuristics (velocity-delta detectors etc.) remain per-server research, per Phase P4 presets.

---

## 11. Open design questions

1. 322 `flying` field vs ability-spoof modules: proxy-override to wire claim, or relay both faithfully? (Correctness vs consistency-with-spoof.)
2. Envelope should ALSO shape AscensionPaths for anti-camper loops (WorldBlockTracker-informed), or stay generic?
3. Do we keep Climb Budget as a separate predicate-guard, or fold into planner (planner can *see* the same number)? Current lean: fold, one policy engine.
4. Auditor UI: is a live "compliance state" badge in the dashboard worth the resync into UI code?
