# RotsAura — Kiwi/Lynx rotation engines

**What this is.** `module/combat/RotsAura.kt` plus the six engines in
`module/combat/rots/`, ported from a KiwiMods/WClient build (`com.retrivedmods.wclient`). The
engines are **verbatim**: same constants, same call order, same maths. Nothing in them was
rewritten to "fit" this client — the client was adapted *to them* instead (see §2).

```
module/combat/RotsAura.kt            module (settings, targets, attack pacing, mode dispatch)
module/combat/rots/KillauraRots.kt   12 zero-memory handlers (Lynx / GatoAuraXRots port)
module/combat/rots/PredictiveAim.kt  motion prediction + smooth-advance engine (stateful)
module/combat/rots/PredictiveRotator.kt  glue: target lock + camera sync around PredictiveAim
module/combat/rots/AbysmRot.kt       unified rotation (predict + accel + style multiplier)
module/combat/rots/GeoRot.kt         geometric aim paths (Circle / Square / Zigzag / Spiral)
module/combat/rots/HvHGod.kt         enemy rotation-style classifier -> handler picker
module/combat/rots/RotsSupport.kt    THE BRIDGE: EntityTracker -> engine contract
```

## 1. Engines and what they expect

All six engines share one contract, which in the source client was filled by
`game/kiwi/*` over `GameSession`/`Entity`/`Player`:

| Engine input | Meaning |
|---|---|
| target position (`posX/posY/posZ`) | **feet** position of the target |
| target velocity (`velX/velY/velZ`) | **delta per tick**, not blocks/second |
| target yaw | body yaw (`rotationYaw`), used by `HvHGod` and `FrontStrafe` |
| target hitbox (`width`, `height`) | AABB size, used for dead zones and aim points |
| local eye position | **eye** Y of the local player |
| local keys / space / time | `PlayerAuthInputPacket` input flags + seconds |

`KillauraRots` handlers are pure functions of `(Ctx, Target, Env)`; the only state they keep is
the global tick counter, the `FrontStrafe` per-target yaw memory and the `AirHvHPro` sample ring
(`resetState()` clears all three). `PredictiveAim` keeps per-entity motion state, so
`PredictiveRotator` owns one instance per module instead of a global `object`.

## 2. How the client was adapted

`RotsSupport` is the single place where this client's data model is translated:

- **Feet vs eye frame.** `EntityTracker.selfY` follows the channel that fed it
  (`selfYFrameIsEye`): `PlayerAuthInputPacket` carries eye/head Y, `MovePlayerPacket` carries feet
  Y. `localFeetY()` / `localEyeY()` normalise that once, using the client's fixed 1.62 eye height
  (the same convention `RotationUtil`/`CrystalAura` use). Tracked entities' `y` is always feet,
  matching the source client's `Entity.posY`.
- **Hitbox.** A Bedrock player is 0.6 × 1.8 (1.5 when sneaking); other entities get a generic
  0.6 × 1.0 box because `EntityTracker` does not store per-entity dimensions.
- **Velocity per tick.** `TrackedEntity.vel*` is the `motion` of the `MovePlayerPacket`, which is
  already a per-tick delta; when a server leaves it at zero the position difference against
  `prev*` is used instead of feeding the engines zeros.
- **Local motion.** `PlayerAuthInputPacket.delta` (x, y, z) is what `localPlayer.motionX/Y/Z` was
  in the source client. `motion` is only X/Z on the wire (two floats), so it is the fallback.
- **Attack path.** `PacketUtil.sendSwingAndAttack` (swing + `ITEM_USE_ON_ENTITY`) instead of
  `localPlayer.attack(...)`; the outgoing `PlayerAuthInputPacket` is mutated in place, exactly like
  the client's other auras.
- **Settings surface.** The source module used `stringValue("Rot Mode", default, options)`. This
  client's setting system has no text-option list, so `Rot Mode` is an `EnumSetting` with a label
  map (`BaseModule.enum(name, default, labels)`, rendered by `OverlayService`). The enum *name* is
  what gets persisted, so labels never break saved configs.

Behaviour deltas (deliberate, documented in the class KDoc):
- `Players Only = false` now means "players and mobs". In the source it produced an empty target
  list, i.e. it silently disabled the whole aura.
- Friends are excluded from the target list (this client's `isFriendEntity`), and `Anti Bot` uses
  this client's convention (`blank name || uniqueId == 0`).
- `Silent Rotation` and `Shortcut` are this client's conventions and were added; `Silent Rotation`
  on (default) writes only the outgoing rotation, off also updates `EntityTracker.selfYaw/selfPitch`.

## 3. Modes

| Mode | Engine | Notes |
|---|---|---|
| `Abysm` | `AbysmRot` (+ `HvHGod` style) | prediction + accelerated easing, speed scaled by the enemy's rotation style |
| `Circle` `Square` `Zigzag` `Spiral` | `GeoRot` + `AbysmRot` | aim point travels a figure around the enemy's chest |
| `Predictive` | `PredictiveRotator` + `PredictiveAim` | smooth follow with motion lead, hysteresis target lock, optional camera sync |
| `None` `Snap` `Normal` `Silent` `Body` `Ping Predict` `Lead Shot` `Smooth` `Legit` `Jitter` `Strafe` `Anticipate` | module | Kiwi modes; `None` neither rotates nor attacks (source behaviour) |
| `Lynx Smooth` `Nemesis` `Vortex` `FrontStrafe` `AirHvH Pro` `FrontsX` `Astral` `Atomic` `Syntax` `Cortex` `Alpha` | `KillauraRots` | recomputed on the attack tick, held between attacks |
| `HvHGod` | `HvHGod` -> `KillauraRots` | classifies the enemy (spinner/jitter/snap/air/strafer/locked/idle) with hysteresis and dispatches to the mapped handler |

`HvHGod.PROFILE` (`rots/HvHGod.kt`) is the only heuristic table in the port; it maps a detected
enemy style to a handler name. Editing it tunes the counter-pick without touching any engine.

## 4. Settings (`RotsAura`)

Combat: `Range` (6, 1–20), `CPS` (20, 5–30), `Packets` (1, 1–10), `Players Only`, `Anti Bot`,
`Ignore Friends`.
Rotation: `Rot Mode` (enum, see §3), `Randomize` (Kiwi direct modes), `Smooth Speed` (15, 1–45,
Kiwi Smooth/Legit), `Strafe Amp` (8, 0–30, Kiwi Strafe), `Vertical Offset` (0.3, −1..1, Lynx aim
point), `Silent Rotation`.
Predictive only: `Pred Time` (2, 0–8), `Pred Strength` (1, 0–1.5), `Rot Smoothing` (0.35,
0.05–1), `Rot Max Speed` (30, 5–90), `Rot Accel` (12, 2–60), `Camera Sync`.

## 5. Reading order for a debug session

1. Is the module picking the right target? `RotsAura.searchForTargets` / `isTarget`.
2. Is it the wrong Y? `RotsSupport.localEyeY()` and `entityFeetY()` (frame flags).
3. Does the aim lag or overshoot? `PredictiveAim.step` (smoothing/speed/accel), or
   `AbysmRot.maxSpeed` + `HvHGod.PROFILE` for `Abysm`.
4. Aim point drifting down the body? `vertOffset`, and `GeoRot.radius`.
5. Wrong handler chosen? `HvHGod.classify` thresholds in the companion object.
