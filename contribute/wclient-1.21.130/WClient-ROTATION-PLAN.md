# WClient — Rotation Work (prediction + fast spin), target 1.21.130

Design notes for the two things you want to build: **predict target rotations to miss fewer hits**, and
**faster rotations to spin around**. Everything below is grounded in this repo's actual code plus the
documented Bedrock packet fields.

---

## 1. What the client already has (facts, with locations)

| Thing | Where | Notes |
|---|---|---|
| `rotationYaw`, `rotationPitch`, `rotationYawHead` | `game/entity/Entity.kt:50-54` | per tracked entity |
| `prevPosX/Y/Z`, `motionX/Y/Z` | `Entity.kt:41-80` | position + per-tick delta already computed in `move()` |
| `tickExists` | `Entity.kt:83` | tick counter per entity, incremented on move packets |
| `MoveEntityAbsolutePacket` handling | `Entity.kt:171-176` | absolute `move()` + `rotate()` (rotation carries pitch, yaw, head yaw) |
| `MoveEntityDeltaPacket` handling | `Entity.kt:177-191` | **rotation fields are deltas**: applies `rotationYaw + packet.yaw` etc., each guarded by `HAS_YAW` / `HAS_PITCH` / `HAS_HEAD_YAW` flags |
| `LocalPlayer.attack()` | `game/entity/LocalPlayer.kt` | sends `InventoryTransactionPacket` with `playerPosition` — **no rotation field at all** |
| Only rotation math in the client | `combat/InfiniteAuraModule.kt` (`calculateRotationToTarget`) | yaw only, no pitch, used solely for the teleport packet |

### The two gaps this exposes

1. **No angular velocity is tracked anywhere.** Nothing stores a previous yaw/pitch, so there is
   currently no way to know how fast a target is turning — prediction has no input.
2. **Attacks carry no aim.** `attack()` sends a transaction with position only. The server scores the
   hit against the rotation it last received from the client's `PlayerAuthInputPacket`, so today the
   effective aim is "whatever the player was looking at", completely decoupled from the module's own
   target selection.

> **Proxy subtlety worth knowing before you design anything:** mutating the outgoing
> `PlayerAuthInputPacket.rotation` changes what the *server* thinks your aim is, but it does **not**
> move the local player's camera — the client already rendered that frame from its own input. So a
> rotation change is inherently "silent" unless you also inject a **client-bound** `MovePlayerPacket`
> to drag the camera. That is the difference between a silent-aim module and a visible one, and it
> should be an explicit setting rather than an accident.

---

## 2. Packet facts to build on

From the Bedrock protocol documentation (minecraft.wiki / CloudburstMC):

* **`MoveEntityDeltaPacket` flag bits** (server → client): `HAS_X 0x01`, `HAS_Y 0x02`, `HAS_Z 0x04`,
  `HAS_PITCH 0x08`, `HAS_YAW 0x10`, `HAS_ROLL 0x20` — plus head-yaw in Cloudburst's enum, which this
  code already uses. The packet only writes non-zero fields, and rotation values are **deltas**, which
  is exactly why `Entity.kt` adds them.
* **`MovePlayerPacket`** (both directions) carries `position`, `rotation` (pitch, yaw, head yaw),
  `mode`, `onGround`, `ridingRuntimeEntityId`, and the tick. `Mode.ROTATION` is a dedicated
  rotation-only update — useful for both features below.
* **`PlayerAuthInputPacket`** (client → server) is the per-tick packet under server-authoritative
  movement: position, pitch, yaw, head yaw, input flags, motion, tick. Under movement V3 the server
  runs "rewind and replay" reconciliation, so anything you mutate here is what gets validated.
* `AddEntity` / `AddPlayer` / `MoveEntityAbsolute` all send rotation as **absolute** (pitch, yaw, head
  yaw), so a target's rotation arrives either absolutely or as a delta — the tracker must handle both,
  and it already does.

---

## 3. Feature A — rotation prediction (miss fewer hits)

### 3.1 Track angular velocity

Extend `Entity` (this is the only place that needs new state):

```kotlin
// Entity.kt
open var prevRotationYaw = 0f
open var prevRotationPitch = 0f
private var yawVelocity = 0f          // deg per tick, smoothed
private var pitchVelocity = 0f

// call from rotate(), after assigning the new values
private fun updateAngularVelocity(prevYaw: Float, prevPitch: Float) {
    val dYaw = shortestAngleDelta(prevYaw, rotationYaw)      // normalise to [-180, 180]
    val dPitch = rotationPitch - prevPitch
    yawVelocity   = yawVelocity   * (1f - ALPHA) + dYaw   * ALPHA
    pitchVelocity = pitchVelocity * (1f - ALPHA) + dPitch * ALPHA
}
val predictedYaw   get() = rotationYaw   + yawVelocity   * leadTicks
val predictedPitch get() = rotationPitch + pitchVelocity * leadTicks
```

* `shortestAngleDelta` is mandatory — a target crossing ±180° otherwise registers a ~360° jump and
  the prediction goes the wrong way for several ticks.
* `ALPHA` ≈ 0.25 gives a ~4-tick smoothing window; expose it as a setting so jittery targets
  (mobile players with touch steering) don't produce wild predictions.

### 3.2 Turn prediction into fewer misses

A hit is scored on **position**, not rotation, so prediction should be split:

| Prediction | Input | Use |
|---|---|---|
| **Position** | `motionX/Y/Z` (already tracked) + acceleration estimate `motion - prevMotion` | aim at the *predicted hitbox centre*: `p + v*t + ½a*t²`, with `t` = lead ticks |
| **Rotation** | new yaw/pitch velocity | predict which way they will *move* next (players strafe relative to their yaw), and for reach/aim consistency |
| **Lead time** | `t = (RTT/2)/50ms + reactionTicks` | RTT estimate: measure from `PlayerAuthInput.tick` → server echo, or use a rolling average of round-trip corrections |

Minimum viable version, in one place — a `RotationUtils` object:

```kotlin
object RotationUtils {
    fun shortestAngleDelta(from: Float, to: Float): Float   // [-180, 180]
    fun lookAt(from: Vector3f, to: Vector3f): Vector3f      // (pitch, yaw, headYaw)
    fun stepTowards(current: Float, target: Float, maxStep: Float): Float
    fun lerpAngle(a: Float, b: Float, t: Float): Float      // shortest-path interpolation
}
```

`lookAt` completes what `InfiniteAuraModule.calculateRotationToTarget` does today (it computes yaw but
copies the player's existing pitch):

```
yaw   = (deg(atan2(dz, dx)) - 90 + 360) % 360
pitch = -deg(atan2(dy, sqrt(dx*dx + dz*dz)))     // negative = looking up
```

### 3.3 Applying the rotation so it actually lands

Three routes, in increasing order of "visible to the player":

1. **Mutate the outgoing `PlayerAuthInputPacket.rotation`** on the same tick you attack — cheapest, and
   it is the rotation the server validates the hit against. Both Killaura and AutoFight already hook
   `PlayerAuthInputPacket` in `beforePacketBound`, so this is a few lines in each.
2. **Send `MovePlayerPacket` with `Mode.ROTATION`** when you want the rotation to be a discrete,
   readable event (also what the existing teleport code does with `Mode.NORMAL`).
3. **Send a client-bound `MovePlayerPacket`** if you want the camera to follow (visible aim).

Recommended settings on the module: `Prediction Ticks` (0–5), `Smoothing` (ALPHA), `Rotation Mode`
(silent / packet / visible), `Max Turn Speed` (deg/tick — see §4).

### 3.4 Randomisation that is missing today

Killaura already has `anti_bot` and AutoFight has `Randomize Timing` / `Humanize Movement`, but the
**rotation** path has no jitter. Add a small per-attack jitter (±0.5–2°) and a per-target
"settle" error so consecutive attacks are not byte-identical angles — that is the usual signature
detection vector when aim is injected.

---

## 4. Feature B — faster rotations / spinning

The limiting factors are not the packet rate, they are **how far you turn per tick** and **how the
server sees it**:

* Rotation lives in `PlayerAuthInputPacket` (one per tick, ~20/s) and in `MovePlayerPacket`
  (`Mode.ROTATION`). You cannot exceed one update per tick *per packet type*, but you can send both.
* A full spin is 360°; at `Max Turn Speed` deg/tick you need `ceil(360 / speed)` ticks (e.g. 60°/tick
  ≈ 0.3 s per revolution). Anything above ~180°/tick starts to look like a teleport to a human viewer
  and to rotation-delta heuristics.
* **Wrap correctly**: never send `yaw + 400.0`; normalise into `[0, 360)` and use `shortestAngleDelta`
  so you never accidentally request a 350° backwards turn.
* `tick` must stay consistent — WClient's `MovePlayer` builders already pass
  `tick = session.localPlayer.tickExists`, and `KeyStrokesModule` shows rotation can be read from
  `packet.rotation.y` per auth-input.

Practical shape of a spin: keep a `targetYaw` that accumulates by
`min(maxTurnSpeed, angularDistanceToTarget) * sign` each tick, write it into the outgoing auth-input,
and optionally mirror it client-bound so the player sees the spin. Make the turn speed a setting
(default ~45–90°/tick) and add a "hold while key pressed" mode so it is usable in a fight.

**Verification to run on device (1.21.130):** log per tick `sentYaw`, `prevYaw`, `delta` and watch for
server corrections (any `MovePlayerPacket` echo with a large yaw delta means the server rewound you).
That log is the fastest way to find the usable ceiling for your ping.

---

## 5. Suggested order of work

1. **`RotationUtils`** + angular velocity on `Entity` (no behaviour change yet, just data). Verify the
   deltas look sane in a log for a moving target.
2. **Aim on attack**: mutate `PlayerAuthInput.rotation` in Killaura / AutoFight / InfiniteAura right
   before `attack()`, using `lookAt` against the *current* target position. This alone should move the
   hit rate, because attacks currently carry no aim at all.
3. **Prediction**: add `Prediction Ticks` + lead-time-from-latency to the target position used by
   `lookAt`, then measure. Keep it settable to 0 so you can A/B it.
4. **Fast spin** as its own module (Motion or Combat) built on `stepTowards` + `Max Turn Speed`.
5. **Humanisation last**, once the mechanics work — jitter and settle error on top of a working aim.

Steps 1–2 are self-contained and low risk; step 3 is where the interesting tuning lives.

---

## 6. Places the work touches

| File | Change |
|---|---|
| `game/entity/Entity.kt` | prev yaw/pitch + velocities, `predictedYaw/Pitch` |
| `game/utils/misc/` (or a new `utils/rotation/`) | `RotationUtils` |
| `game/module/combat/KillauraModule.kt` | aim before `attack()`, prediction settings |
| `game/module/combat/AutoFightModule.kt` | same, plus its multi-target path |
| `game/module/combat/InfiniteAuraModule.kt` | replace ad-hoc yaw math with `RotationUtils` |
| `game/module/combat/ACAModule.kt`, `EnemyHunterModule.kt`, `PlayerTPModule.kt` | teleport rotations via `RotationUtils` |
| `game/module/motion/` (new file) | the spin module |
| `game/entity/LocalPlayer.kt` | optionally let `attack()` take an aim parameter |

Nothing here depends on the 1.21.130 codec work — the packets involved (`PlayerAuthInput`,
`MovePlayer`, `MoveEntityDelta`, `MoveEntityAbsolute`) are unchanged across 1.21.80 → 1.21.130.
