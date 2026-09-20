# NoLagback v3 — Verification Checklist Execution

Companion to `NO_LAGBACK_V3.md`. This doc turns the verification checklist into
a runnable procedure: one instrumented debug flight + one analyzer run answers
every BLOCKING question before P1/P2/P3 code starts.

**Tooling (this commit):**
- `app/.../module/misc/FlightProbe.kt` — passive wire logger (module **FlightProbe**, MISC).
  Never cancels/rewrites; logs `{HH:mm:ss.SSS} [VPROBE] VP|v1|…` lines to **baba.txt**.
  Each enable = a phase marker (`MARK|n=…`) — the flight protocol toggles it per phase.
- `tools/analyze_vprobe.py` — parses baba.txt into per-item verdict hints.

Run: `python3 tools/analyze_vprobe.py baba.txt`

---

## Status board

| # | Item | Status |
|---|------|--------|
| 13 | motion.x = strafe, motion.y = forward | ✅ **CLOSED (code)** — double-implementation concordance: `KillAura.kt:473` (`inFwd = pkt.motion.y`, `inStr = pkt.motion.x`, orbit verified working live) and Oomph (`mF=Impulse().Y(), mS=Impulse().X()`). Live P5 = cheap sanity only. |
| 14 | Ability spoof: game-state vs wire-only | ✅ **CLOSED (code)** — CreativeFly/BypassFly **cancel** client `RequestAbility(FLYING)` **and** inbound `UpdateAbilitiesPacket`, then inject a forged `UpdateAbilitiesPacket` **clientbound** → the *game itself* believes MAY_FLY+FLYING. Consequences: (a) the client's own 322 `Flying` flag and StartFlying input flags are **self-consistent** (game-state branch — Q1 moot, no 322 rewrite needed); (b) **server never grants → trustFlyStatus=false server-side** — E10 confirmed: all spoof-flight is "illegal" in the server sim *by construction*; corrections during it are inherently self-inflicted (folds into P3 flavor model); (c) known blindness: blanket-cancel of inbound UpdateAbilities also hides *legit* server grants (hub fly zones). Noted for P3 audit. |
| 5 | Authority mode | ◐ **PRE-ANSWERED (codec)** — v975 StartGame still carries `authoritativeMovementMode` but it's `@Deprecated since v818 — SERVER_WITH_REWIND is now the default`. Modern servers send V3 unconditionally; PMMP-flavor shows behaviorally (no 161s, RESET corrections). FlightProbe logs the field + flavor markers. **Flight confirms.** |
| 1, 3, 6, 10 | tick semantics, belief evolution, acceptance threshold, cadence | 🛫 **FLIGHT** (batch A below) |
| 2 | 322 emission & cadence sensitivity | 🛫 **FLIGHT** (P0–P2 passive; item 2b A/B = optional 2×30 min after) |
| 4 | SyncedPlayerMovementSettings per-server | 🛫 **FLIGHT** (SG logged every join) |
| 7 | SetEntityMotion C2S semantics | ⚠️ **OPT-IN PROBE** (module setting "Motion Packet Probe", default OFF — sends ONE C2S SetEntityMotion(self, +1.0) per enable. Active tampering; use on a disposable server first.) |
| 8 | Ticked SetEntityData/UpdateAttributes self | 🛫 **FLIGHT** (SAD/UAT lines) |
| 9 | Legit-cause corrections exist | 🛫 **FLIGHT** (ctx fields on every correction) |
| 11 | RakNet brand reliability | 🔎 **OFF-BAND** — probe logs host per phase; brand via `tools` ping or the attach-experiment capture. Lowest priority of batch B. |
| 12 | MovePlayer mode for rubber-bands | 🛫 **FLIGHT** (MP mode histogram) |

---

## The debug flight (≈15 min, one session)

Preparation: enable **FlightProbe** (module starts logging on enable — but join
*first* with it already enabled so phase 0 captures StartGame). For clean data:
NoLagback **ON in ADAPTIVE** for P0–P3 (production behavior), **OFF for P4**
(governor would mask the induced drift). FlightProbe **off→on at each phase
boundary** (its MARK increments; the analyzer segments phases by it).

| Phase | Duration | Do | Feeds items |
|-------|----------|-----|-------------|
| P0 | 60 s | Join server, stand idle. | 4, 5, 8, 11 — SG settings, baseline 322 (should be ~zero without corrections) |
| P1 | 90 s | Normal play: walk, sprint-jump. Take ONE mob hit if convenient (knockback sample). | baseline ticks; item 9 legit-cause sample |
| P2 | 180 s | CreativeFly/MotionFly sustained (default speed, mixed ascent/hover/horizontal). | 1, 2, 6, 10, 12 — corrections stream |
| P3 | 45 s | Mid-air: **release ALL inputs** (hover), 30 s+; then glide/drop to ground. | 3 — correction-to-correction drift with (nearly) no new claims = belief evolution |
| P4 | 3×30 s + 15 s rest between | NoLagback OFF. Hover; make small deliberate overshoot bursts (~0.3, ~0.45, ~0.6 blocks past a reference wall/point), rest between. | 6 — which induced distances draw corrections = empirical threshold |
| P5 | 20 s | Enable "W/D Motion Probe" setting; hold W 3 s, release, hold D 3 s; disable setting. | 13 live sanity (expect MOT my≈+0.98 on W; mx≈+0.98 on D) |

Also logged passively everywhere: `DISC` (kick reason), `PLAY` (status transitions),
`MOTN/EFF/ABIL/ADV` (context channels).

Item 2b (cadence sensitivity A/B): after the analyzer shows 322 actually follows
corrections, run two 30-min sessions later — SILENT vs ADAPTIVE — and compare
`corr/min` and DISC lines. Decides SILENT removal per the matrix.

---

## Interpretation matrix (unchanged from checklist, keyed to analyzer output)

- **Item 1** — lag=auth−corrTick distribution. Mostly 1..40 → P2 ring justified.
  Mostly 0 → drop P2 ring.
- **Item 2** — PSYNC follows ≥50% of corrections → answer-channel model holds,
  322 hygiene mandatory, SILENT removal justified. Never emitted → §4 collapses,
  SILENT stays.
- **Item 3** — P3 phase consecutive-correction drift ≈ 0 → belief static, envelope
  planner trivial. Drifting (gravity rates) → P3 needs forward-replay (gravity/drag/
  grounded — constants already in addendum §B).
- **Item 4** — SG values varying by server → parse-and-adapt real; defaults
  everywhere → keep v2.2 learned calibration.
- **Item 5** — mode field + 161-presence (analyzer prints flavor verdict).
  Client-auth flavor → governor moot, module no-op.
- **Item 6** — empirical correction floor ≈ 0.2–0.5 and per-tick → planner step
  model confirmed; clustered wildly → redesign before P3, not after.
- **Item 7** — MPROBE applied (Y jumped server-seen) → claims are physics;
  ignored/corrected → claims are requests, governor models server sim. (Expect:
  ignored — SetEntityMotion is S2C-authoritative in vanilla; probe is cheap proof.)
- **Item 8** — SAD/UAT tick≠0 during fly phases → anchor unification keeps 39/29;
  zero → P1 anchors stay {161, MovePlayer}.
- **Item 9** — corrections with knockback/teleport/attribute ctx → classifier scope
  stands; all UNEXPLAINED → classifier dead weight, governor only.
- **Item 10** — gap histogram ≥4.5 ticks dominant → 5-tick min applies; else
  different scheduler → calibration-only.
- **Item 11** — brand reliability: compare host ↔ known software across 2–3 servers;
  Waterdog fronts may mask. Decision flips §7 signal priority.
- **Item 12** — expect rubber-bands as RESPAWN(1) (Cloudburst name for wire RESET)
  and maybe TELEPORT(2); analyzer histogram settles NoLagback's branch mapping.

---

## Notes / invariants

- FlightProbe obeys the relay invariant from the v3 audit: **interceptors must
  never throw and must never block forwarding** — every handler is guarded and the
  module never cancels.
- baba.txt grows during flights; clear or note start time before flying. Analyzer
  only reads `[VPROBE] VP|v1|` lines, so a shared file is fine (phases segment it).
- Codec gap: 322 arrives to modules as `UnknownPacket` (id 322) — probe matches by
  id. A typed codec stub is still slated for P1 (Auditor metrics), separately.
