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

---

# ROUND-2 ADDENDUM — deep research pass + code audit (2026-09-19)

Sources: vendored Cloudburst v975 codec; **gophertunnel master** (raw `.go` — wire-authoritative);
**Oomph-AC/oomph @ stable** (open-source MITM bedrock anticheat — a full reference implementation of
the adversary); **Zuri** (PMMP plugin AC) README taxonomy; Mojang bedrock-protocol-docs (round 1).

## A. Wire facts — locked to source this round

**`ClientMovementPredictionSyncPacket` (322, C2S)** — exact marshal order (gophertunnel master):
`ActorFlags[Bitset×EntityDataFlagCount], BoundingBoxScale f32, BoundingBoxWidth f32, BoundingBoxHeight f32,
MovementSpeed f32, UnderwaterMovementSpeed f32, LavaMovementSpeed f32, JumpStrength f32, Health f32,
Hunger f32, FrictionModifier f32, Bounciness f32, AirDragModifier f32, EntityUniqueID i64, Flying bool`.
Doc note: "sent **periodically if the client has received movement corrections**" — the answer-channel
model stands. Attributes serialize as 0 when unset.
⚠️ **322 does not exist in our vendored Cloudburst v975 codec.** Verification task (A4 below) is now
top-of-P1: if our relay drops what it cannot decode, we are *already* starving 322 today.

**`CorrectPlayerMovePredictionPacket` (161, S2C)** — full layout confirmed:
`PredictionType u8 (0=Player,1=Vehicle), Position v3, Delta v3, Rotation v2, VehicleAngularVelocity opt f32,
OnGround bool, Tick varuint64`. Semantics sharpened by Oomph's `Sync()`:
- `Position = serverSimPos + (0, 1.621)` → **eye frame, not feet**. Any anchor math must subtract the
  eye offset (1.62 standing, 1.27 sneaking) before comparing to feet-frame positions.
- `Delta = server's believed VELOCITY at Tick` (not "change vs our claim" — the Mojang-doc reading).
- Sent **iff** authority mode is server-with-rewind; corrections >40 ticks old applied without rewind.

**`PlayerAuthInputPacket` (C2S, the ONLY legit movement channel on V3)**: client sends it **once per tick
instead of MovePlayer** when server-auth movement is on. Fields incl. `Position` (eye frame), `Delta`
(= client movement delta), `MoveVector` (normalized, permission/sneak-scaled), `RawMoveVector`
(unnormalized pre-permission), `AnalogueMoveVector`, `CameraOrientation`, `Tick` — "server tick at which
the packet was sent, used in relation to 161": **the client mirrors the server tick clock it learned**
(from StartGame/corrections), so correction Tick↔input Tick is a real shared clock, not two clocks.

**`MovePlayerPacket`**: modes `0=Normal, 1=Reset, 2=Teleport, 3=Rotation`; teleport causes
`0=Unknown,1=Projectile,2=ChorusFruit,3=Command,4=Behaviour`; `Tick` field again 161-related.
Clientbound `Position` is **feet frame** (render position) — different Y reference from 161/AuthInput.

**`StartGamePacket.PlayerMovementSettings` (current protocol)**: the movement-mode enum is GONE —
only `{ RewindHistorySize varint32 (server's knob, 40 default), ServerAuthoritativeBlockBreaking bool }`.
Server-auth-rewind is simply *the* protocol now on 1.26.x. Flavor detection = read `RewindHistorySize` +
observe correction cadence/threshold; PMMP-flavor = absence of 161 + presence of MovePlayer RESET corrections.

**`ActorFallPacket`**: gone from current gophertunnel; even in older branches: "should not be used by the
server — easily spoofed; servers compute fall damage themselves." → **NoFall-style onGround spoofing is
doubly dead territory**; the server's own sim accumulates YOUR claimed fall distance regardless.

## B. Vanilla movement constants — exact table (Oomph `game/movement.go`, validated vs Mob code)

The belief-replay engine (P2/P3) needs *numbers*, and now has them:

| Constant | Value | | Constant | Value |
|---|---|---|---|---|
| jump velocity | 0.42 | | gravity | 0.08 (×0.98/tick decay) |
| slow-falling gravity | 0.01 | | air friction | 0.91 |
| block friction (ground) | 0.6 | | air acceleration | 0.02 (0.026 sprint) |
| sprint speed mult | ×1.3 | | impulse scale | moveVector × 0.98 |
| sneak impulse cap | 0.3 | | consuming impulse cap | 0.1225 |
| normalized impulse cap | 0.707 (1/√2 diag) | | step height | 0.5625 (9/16) |
| ladder climb speed | 0.2 | | jump cooldown | 10 ticks |
| eye offset standing | 1.62001 (net 1.621) | | eye offset sneaking | 1.27 |
| glide boost window | 20 ticks | | slide offset mult | 0.4 (≤1.20.60) |

## C. The adversary, from source — Oomph's correction engine

Per incoming AuthInput (`handleMovement`), after replaying the input through the sim:

```
posDiff = serverSimPos − clientClaimPos
needsCorrection = |posDiff| > CorrectionThreshold        (config 0.2–0.5; vanilla 0.5)
fire correction ONLY IF all hold:
  PendingTeleports == 0          → teleport in flight exempts the tick
  !hasTeleport                   → teleport tick exempts
  !InputFlagJumpPressedRaw       → jump-press ticks are exempt!
  !hasKnockback                  → knockback window exempts
```

Structural rules that matter for the planner/classifier:

1. **One correction in flight.** `PendingCorrectionACK` gates new corrections until the client ACKs the
   previous one ("cooldown" = one clean post-correction tick). Correction *rate* is therefore bounded by
   RTT, not just the 5-tick server knob — our 250ms pressure-recovery constant matches this cadence. ✓
2. **Correction packet carries sim truth:** position (eye), velocity, onGround, tick — everything P2's
   belief ring needs arrives signed and stamped.
3. **Persuasion (the quiet absorber):** when *below* threshold and no pending corrections, server pos
   drifts toward the client's by ≤ PersuasionThreshold/tick — **X/Z only, never Y**. Horizontal
   micro-drift is silently forgiven; vertical micro-drift is not (matches our climb-guard emphasis).
   Planner corollary: *converge Y exactly, let X/Z converge lazily*.
4. **Outgoing position rewrite:** MITM validators clamp the relayed `pk.Position` to the corrected one —
   *other players see the server's belief, not your cheats*. Vanilla BDS behaves likewise. OPSEC
   windfall: spectator-visible movement is automatically sanitized; our own risk surface is only the
   correction/score stream back to us.
5. **Timer gate:** 20 inputs/sec allowance (`allowedInputs`); excess inputs are not simulated.
6. **trustFlyStatus:** `StartFlying` input flag is honored only after server-sent abilities grant MayFly.
   Spoofing the fly flag in AuthInput without the server's ability packet = untrusted = sims as illegal.
   322's `Flying` field must tell the same story as the last UpdateAbilities the client ACKed.
7. **Impulse discipline:** MoveVector components are clamp-checked against 1.0 / 0.3 (sneak) / 0.1225
   (consuming). Out-of-range components = instant InvalidPackets flag (Zuri agrees). We must never
   synthesize or inject vectors beyond these caps.
8. **Sprint state race:** start+stop-sprint flags in one tick on 1.21+ are treated as "not sprinting"
   (air accel 0.02). Flag sloppiness gets simulated, not corrected — small persistent tells.

## D. Zuri (PMMP plugin AC) claim-map — the other flavor's predicates

From its README architecture (public code on main only ships Speed checks; taxonomy still standing):
`FlyA` rising while airborne · `FlyB` packet-flag/ability inconsistency (= our trustFlyStatus finding) ·
`FlyC` block-surroundings + air-tick legitimacy (claims onGround while unsupported/airborne long) ·
`InvalidPacketsA` spoofed/out-of-range move vectors · `TimerC` MovePlayer-vs-AuthInput interleave
stability · `TimerD` auth-input ticks vs real-time drift · `CrasherA` impossible Y · `AntiVoid`
impossible Y-return. → PMMP-flavor plan: no 161 stream to learn from; corrections arrive as MovePlayer
RESET; predicates 1/3/5 apply to *our* claim integrity, and our injected MovePlayer packets are exactly
what TimerC inspects.

## E. Code audit — findings (against `main` @ docs-commit)

| # | Sev | Finding | Disposition |
|---|-----|---------|-------------|
| E1 | **HIGH** | Smart Resync (`NoLagback.kt:124`) injects `MovePlayer TELEPORT, onGround=true` **serverbound** on V3 sessions — legacy channel (legit clients only move via AuthInput), impossible flag claim (next AuthInput says onGround=false), teleport-mode spike for TimerC-style checks. Together: a handshake announcing a proxy. | P1: V3 = stop serverbound inject entirely; let the real client absorb corrections (it re-sims natively). If client must be moved visually, `mirrorToClient`-only. Keep serverbound path for PMMP flavor (their only channel). |
| E2 | **HIGH** | SILENT mode still compiled/shipped (drops 161/RESET). Round-1 ruled: dropping corrections starves 322 generation = tamper tell. | P1 removal (already planned; now double-evidenced). |
| E3 | OK ✓ | Codec gap investigated: 322 absent from vendored v975 codec, BUT `RubidiumRelaySession.{Server,Client}Session.onPacket` forwards **every** surviving packet as `UnknownPacket` (raw payload re-wrap, both directions) — 322 flows through untouched today; we are NOT starving it. Remaining wants: (a) codec stub so the Auditor can *observe* 322 rate (correction-follow-through metric for P3), (b) document the invariant: `handle*Packet` runs **before** forward, and its catch logs-and-DROPS the packet — relay hygiene rule: **interceptors must never throw, and must always return true unless deliberately filtering**. | P1: codec stub + invariant note in relay docs; no passthrough bug exists. |
| E4 | **MED** | `MovementCompliance.noteCorrection()` keeps only x/y/z — drops Tick, OnGround, Delta(=server velocity). Belief anchor is frame-ambiguous (eye vs feet unaccounted) and time-stale (ticks ignored). | P2: belief ring keyed by tick, store full tuple, subtract eye offset on ingest. |
| E5 | **MED** | Y reference frames unverified: is `EntityTracker.selfY` eye frame (AuthInput-sourced) or feet (MovePlayer-sourced)? If mixed with a 161-derived anchor, `discrepancy()` carries a systematic ~1.62 error → phantom "drift" → spurious resyncs. | P1: one-line verification; pick feet-everywhere internally, convert at ingest. |
| E6 | **LOW** | Stale docstrings: `MovementCompliance` header still says "invert to a controlled sink" (behavior is clamp-to-0 since 11ee6cc). | Fixed this commit. |
| E7 | OK ✓ | Pressure recovery 0.0008/ms ≈ 250ms/hit matches both vanilla min-correction cadence (5t) and Oomph's RTT-ACK gate. Settle 600ms covers edge-to-edge comfortably. | No change. |
| E8 | OK ✓ | `serverProven` evidence-gating (`2972df6`) verified in code: no preemptive caps; slider is law until first correction. Matches user directive. | Keep. |
| E9 | OK ✓ | Anchor set covers 161 + MovePlayer RESET/TELEPORT — correct for both flavors; PMMP flavor has no 161, V3 flavor uses 161. | Extend in P2 with tick frames. |
| E10 | MED | CreativeFly/BypassFly ability-spoof flows were not rechecked against `trustFlyStatus` semantics: server sim only honors flying after a server-issued MayFly. If the modules spoof abilities client-side only, the sim treats all flight as illegal *from tick one* — silent flag accumulation without corrections (scores, not snaps). | P3 audit task: trace UpdateAbilities/RequestAbility flows per module; dedicated finding then. |

## F. v3 spec deltas from this round (folded into the plan)

- **P1 (hardening)** gains: E1 resync-hygiene fix, E3 codec-322 stub + passthrough test, E5 frame
  unification. These are now *blocking* for anything else — they close hazards active in the current build.
- **P2 (belief replay)** upgraded from "approximate replay" to "faithful for common cases": §B constants
  + `Delta`-as-velocity + onGround + eye-frame conversions + 40-tick window from `RewindHistorySize`.
- **P3 (planner + classifier)** gains: correction-suppression exemption set {teleport in flight,
  teleport tick, JumpPressedRaw, knockback window} = classifier's "expected quiet" model; planner
  converges **Y first, X/Z lazily** (persuasion absorbs horizontal slack); single-correction-in-flight
  gates our compliance-step cadence to RTT; never emit vectors beyond impulse caps (§C.7).
- **P4 (flavors)** keyed on: `RewindHistorySize`, SABB flag, 161-presence vs RESET-presence, learned
  threshold (from correction Delta magnitudes), learned cadence (from correction Tick spacing).
- **New recorded mechanism — "jump exemption":** raw jump-press ticks never draw corrections in the
  reference implementation. Noted for completeness of the threat model; the planner must NOT rely on it
  as a suppression trick (config-dependent, and a jump-spam signature is itself a heuristic tell).

Open questions from §11 stand; E10 joins them. Next engineering step: **P1 hardening batch** (E1/E2/E3/E5
+ comment fixes), then device validation.
