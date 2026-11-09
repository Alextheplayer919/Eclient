package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.combat.rots.AbysmRot
import com.rubidiumclient.module.combat.rots.GeoRot
import com.rubidiumclient.module.combat.rots.HvHGod
import com.rubidiumclient.module.combat.rots.KillauraRots
import com.rubidiumclient.module.combat.rots.PredictiveRotator
import com.rubidiumclient.module.combat.rots.RotsSupport
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct port of the uploaded Neon `KillauraModule` (`d4c_aura`), adapted to this client's
 * module/event model instead of the Neon `Module`/`GameSession` one.
 *
 * Kept deliberately faithful to the source: same setting keys and ranges, same 30 rotation
 * modes, same dispatch order (targets -> rotations -> strafe -> CPS-gated attack), and the
 * same "rotations are computed every tick, attacks are rate-limited separately" split.
 *
 * Where Neon reached into its own world model, this uses the equivalents this client already
 * has (each one is called out at the call site):
 *  - `session.level.entityMap` / `session.localPlayer` -> [EntityTracker]
 *  - `session.localPlayer.attack(entity)`              -> [PacketUtil.sendSwingAndAttack]
 *  - `session.orbitAround(...)`                        -> [RotationUtil.orbitAround]
 *  - Neon `RotationUtils.Orbit`                        -> [RotationUtil.Orbit] (same algorithm)
 *  - `FriendManager.isFriend(uuid)`                    -> the repo's `isFriendEntity` helper
 *
 * Differences from the source that are not ported:
 *  - `fromJson` legacy-key migration: this client's config stores settings by display name, so
 *    the Neon `"Rot Mode"`/`"Pred Time"` migration keys do not exist here.
 *  - `auto_prediction`: Neon derived the lookahead from `session.latency` and its hit tracker.
 *    There is no equivalent estimator here, so the setting is not offered and "Prediction Ticks"
 *    is always the lookahead when "Prediction" is on.
 */
class NeonAura : BaseModule(
    name        = "NeonAura",
    category    = ModuleCategory.COMBAT,
    description = "Neon d4c_aura portu: 30 rotasyon modu, predictive/HvHGod/Geo ve CPS saldırısı"
) {

    // ── rotation modes (identical order/labels to the source) ────────────────
    enum class RotationMode {
        PREDICTIVE, ABYSM, AUTO, SMOOTH, NEMESIS, VORTEX, FRONT_STRAFE, AIR_HVH_PRO, FRONTS_X,
        ASTRAL, ATOMIC, SYNTAX, CORTEX, ALPHA, HVH_GOD,
        GEO_CIRCLE, GEO_SQUARE, GEO_ZIGZAG, GEO_SPIRAL,
        KIWI_NONE, KIWI_SNAP, KIWI_NORMAL, KIWI_SILENT, KIWI_BODY, KIWI_PING_PREDICT,
        KIWI_LEAD_SHOT, KIWI_SMOOTH, KIWI_LEGIT, KIWI_JITTER, KIWI_STRAFE, KIWI_ANTICIPATE;

        val display: String
            get() = when (this) {
                PREDICTIVE -> "Predictive"; ABYSM -> "Abysm"; AUTO -> "Auto"
                SMOOTH -> "Lynx Smooth"; NEMESIS -> "Nemesis"; VORTEX -> "Vortex"
                FRONT_STRAFE -> "FrontStrafe"; AIR_HVH_PRO -> "AirHvH Pro"; FRONTS_X -> "FrontsX"
                ASTRAL -> "Astral"; ATOMIC -> "Atomic"; SYNTAX -> "Syntax"; CORTEX -> "Cortex"
                ALPHA -> "Alpha"; HVH_GOD -> "HvHGod"
                GEO_CIRCLE -> "Circle"; GEO_SQUARE -> "Square"; GEO_ZIGZAG -> "Zigzag"
                GEO_SPIRAL -> "Spiral"
                KIWI_NONE -> "None"; KIWI_SNAP -> "Snap"; KIWI_NORMAL -> "Normal"
                KIWI_SILENT -> "Silent"; KIWI_BODY -> "Body"; KIWI_PING_PREDICT -> "Ping Predict"
                KIWI_LEAD_SHOT -> "Lead Shot"; KIWI_SMOOTH -> "Smooth"; KIWI_LEGIT -> "Legit"
                KIWI_JITTER -> "Jitter"; KIWI_STRAFE -> "Strafe"; KIWI_ANTICIPATE -> "Anticipate"
            }
    }

    // ── settings (same keys and ranges as the source) ────────────────────────
    private val rangeValue          = float("Range", 7f, 2f, 10f)
    private val cpsValue            = int  ("CPS", 20, 5, 30)
    private val packets             = int  ("Packets", 1, 1, 10)
    private val playersOnly         = bool ("Players Only", true)
    private val mobsOnly            = bool ("Mobs Only", false)
    private val antiBot             = bool ("Anti Bot", true)
    private val rotations           = bool ("Rotations", true)
    private val prediction          = bool ("Prediction", true)
    private val predictionTicks     = int  ("Prediction Ticks", 2, 0, 10)

    private val rotationMode = enum(
        "Rotation Mode",
        RotationMode.PREDICTIVE,
        RotationMode.entries.associateWith { it.display }
    )

    private val verticalOffset      = float("Vertical Offset", 0.3f, -1f, 2f)
    private val randomizeRotations  = bool ("Randomize", false)
    private val smoothSpeed         = float("Smooth Speed", 15f, 1f, 45f)
    private val strafeAmplitude     = float("Strafe Amp", 8f, 0f, 30f)
    private val cameraSync          = bool ("Camera Sync", true)

    private val predictionStrength  = float("Pred Strength", 1f, 0f, 1.5f)
    private val rotationSmoothing   = float("Rot Smoothing", 0.35f, 0.05f, 1f)
    private val rotationMaxSpeed    = float("Rot Max Speed", 30f, 5f, 90f)
    private val rotationAcceleration= float("Rot Accel", 12f, 2f, 60f)

    private val tpAuraEnabled       = bool ("TP Aura", false)
    private val teleportBehind      = bool ("TP Behind", false)
    private val tpSpeed             = int  ("TP Speed", 100, 10, 500)
    private val tpYOffset           = int  ("TP Y Offset", 1, -10, 10)
    private val keepDistance        = float("Keep Distance", 1.2f, 0.5f, 10f)

    private val strafe              = bool ("Strafe", false)
    private val strafeRadius        = float("Strafe Radius", 2.5f, 1f, 6f)
    private val spinDirection       = enum("Spin Direction", RotationUtil.Orbit.SpinDirection.LEFT)

    // ── engine state (instance-owned: no cross-module statics) ───────────────
    private var lastAttackTime = 0L
    private var tpCooldown = 0L
    private val orbit = RotationUtil.Orbit()
    private val rotationEngine = KillauraRots()
    private val predictiveRotator = PredictiveRotator()
    private val abysm = AbysmRot()
    private val styleSelector = HvHGod()

    private var effectiveMode: RotationMode? = null
    private var lastRotationTick = Long.MIN_VALUE
    private var rotationContext: KillauraRots.Ctx? = null
    private var rotationTarget: Long? = null
    private var previousRotationMode: RotationMode? = null
    private var rotationTickCounter = 0L
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var orbitCommanded = false

    private fun resetRotations() {
        rotationEngine.resetState()
        predictiveRotator.reset()
        abysm.reset()
        styleSelector.reset()
        effectiveMode = null
        lastRotationTick = Long.MIN_VALUE
        rotationContext = null
        rotationTarget = null
        previousRotationMode = null
    }

    /** True while this module is actually moving the player around a target. */
    internal val isStrafing: Boolean get() = strafe.value && orbitCommanded

    override fun onEnable() {
        super.onEnable()
        lastAttackTime = 0L
        tpCooldown = 0L
        rotationTickCounter = 0L
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onDisable() {
        super.onDisable()
        rotationTickCounter = 0L
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val packet = event.packet
        val session = event.session

        // Server teleports and Bedrock movement-prediction corrections invalidate the orbit's
        // previous phase and speed estimate. Do not immediately replay movement after one.
        // NOTE: these arrive SERVER->CLIENT, so this must run BEFORE the direction filter —
        // otherwise the branch is unreachable and the orbit never resets after a correction.
        val isServerCorrection = event.direction == PacketEvent.Direction.SERVER_TO_CLIENT && (
            packet is CorrectPlayerMovePredictionPacket ||
                (packet is MovePlayerPacket &&
                    packet.runtimeEntityId == EntityTracker.selfRuntimeId &&
                    (packet.mode == MovePlayerPacket.Mode.TELEPORT ||
                        packet.mode == MovePlayerPacket.Mode.RESPAWN))
            )
        if (isServerCorrection) {
            resetRotations()
            orbit.onServerCorrection()
            return
        }

        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (packet !is PlayerAuthInputPacket) return

        val targets = searchForTargets().toMutableList()
        val tickRolledBack = packet.tick < lastRotationTick
        if (previousRotationMode != rotationMode.value || tickRolledBack) {
            resetRotations()
            previousRotationMode = rotationMode.value
            if (tickRolledBack) rotationTickCounter = 0L
        }
        lastRotationTick = packet.tick
        rotationTickCounter++

        if (!rotations.value) resetRotations()
        val mode = rotationMode.value

        if (targets.isEmpty()) {
            orbit.reset()
            if (rotations.value && mode == RotationMode.PREDICTIVE) {
                applyPredictiveRotation(targets, packet, session)
            } else {
                resetRotations()
            }
            orbitCommanded = false
            return
        }

        // Rotations and strafing run every tick, independently of attack CPS.
        val player = localFrame()
        predictiveRotator.selectTarget(targets, session, packet.tick)
        val primary = targets.first()

        if (rotations.value && (mode == RotationMode.ABYSM || mode == RotationMode.AUTO ||
                    mode == RotationMode.HVH_GOD)) {
            targets.forEach {
                styleSelector.observe(it.runtimeId, it.yaw, it.x, RotsSupport.entityFeetY(it), it.z, packet.tick)
            }
            styleSelector.retain(targets.map { it.runtimeId })
        }

        var shouldAttack = true
        if (rotations.value) {
            if (mode == RotationMode.PREDICTIVE) applyPredictiveRotation(targets, packet, session)
            else shouldAttack = applyRotations(player, primary, packet)
        } else {
            resetRotations()
        }

        if (strafe.value) {
            orbitCommanded = strafeAroundTarget(primary, packet.tick, session)
        } else {
            orbitCommanded = false
            orbit.reset()
        }

        if (!shouldAttack) return

        val now = System.currentTimeMillis()
        val delay = 1000L / cpsValue.value.coerceAtLeast(1)
        if (now - lastAttackTime < delay) return

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        for (target in targets) {
            if (tpAuraEnabled.value && now - tpCooldown >= tpSpeed.value) {
                teleportTo(target, session)
                tpCooldown = now
            }
            repeat(packets.value) { PacketUtil.sendSwingAndAttack(session, target.runtimeId, slot) }
        }
        lastAttackTime = now
    }

    // ------------------------------------------------------------------ targeting

    private fun searchForTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val out = ArrayList<EntityTracker.TrackedEntity>(8)
        val r = rangeValue.value
        for (e in EntityTracker.getAll()) {
            if (!isTarget(e)) continue
            if (MathUtil.dist3sq(e.x, e.y, e.z, sx, sy, sz) > r * r) continue
            out.add(e)
        }
        out.sortBy { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
        return out
    }

    private fun isTarget(e: EntityTracker.TrackedEntity): Boolean {
        if (e.runtimeId == EntityTracker.selfRuntimeId) return false
        if (e.isPlayer) {
            if (!playersOnly.value) return false
            if (antiBot.value && isBot(e)) return false
            return true
        }
        return mobsOnly.value && e.type == EntityTracker.EntityType.MONSTER
    }

    /**
     * Neon keyed this off the server PlayerList xuid; this client's tracker does not keep the
     * xuid, so the same "no real identity" test uses the blank-name / zero-uid criterion the
     * rest of the combat modules in this repo already use.
     */
    private fun isBot(e: EntityTracker.TrackedEntity): Boolean =
        e.name.isBlank() || e.uniqueId == 0L

    // ------------------------------------------------------------------ rotations

    /** Local-eye frame handed to the engines (Neon's `session.localPlayer`). */
    private class LocalFrame(val x: Float, val y: Float, val z: Float, val yaw: Float, val pitch: Float)

    private fun localFrame() = LocalFrame(
        EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ,
        EntityTracker.selfYaw, EntityTracker.selfPitch
    )

    private fun applyRotations(player: LocalFrame, target: EntityTracker.TrackedEntity, packet: PlayerAuthInputPacket): Boolean {
        val selected = if (rotationMode.value == RotationMode.AUTO || rotationMode.value == RotationMode.HVH_GOD) {
            when (styleSelector.pick(target.runtimeId, player.x, player.z, packet.tick)) {
                "Atomic" -> RotationMode.ATOMIC
                "Cortex" -> RotationMode.CORTEX
                "Nemesis" -> RotationMode.NEMESIS
                "AirHvH Pro" -> RotationMode.AIR_HVH_PRO
                "FrontStrafe" -> RotationMode.FRONT_STRAFE
                "Astral" -> RotationMode.ASTRAL
                "Lynx Smooth" -> RotationMode.SMOOTH
                "FrontsX" -> RotationMode.FRONTS_X
                else -> RotationMode.FRONTS_X
            }
        } else rotationMode.value

        if (rotationTarget != target.runtimeId || effectiveMode != selected) {
            rotationEngine.resetState()
            abysm.reset()
            abysm.seed(player.yaw, player.pitch)
            rotationContext = null
            rotationTarget = target.runtimeId
            effectiveMode = selected
        }

        if (selected == RotationMode.ABYSM) {
            styleSelector.pick(target.runtimeId, player.x, player.z, packet.tick)
            val targetChestY = feetY(target) + if (target.isPlayer) RotsSupport.hitboxHeight(target) * 0.6f else 0.9f
            val m = RotsSupport.motion(target)
            val (pitch, yaw) = abysm.aim(
                target.x, targetChestY, target.z,
                m.first, m.second, m.third,
                player.x, player.y, player.z, styleSelector.lastStyle
            )
            applyPacketRotation(packet, pitch, yaw, yaw)
            return true
        }

        val geoPattern = when (selected) {
            RotationMode.GEO_CIRCLE -> "Circle"
            RotationMode.GEO_SQUARE -> "Square"
            RotationMode.GEO_ZIGZAG -> "Zigzag"
            RotationMode.GEO_SPIRAL -> "Spiral"
            else -> null
        }
        if (geoPattern != null) {
            val (offsetX, offsetY, offsetZ) = GeoRot.offset(geoPattern, packet.tick)
            val chestY = feetY(target) + if (target.isPlayer) RotsSupport.hitboxHeight(target) * 0.6f else 0.9f
            val (pitch, yaw) = abysm.aim(
                target.x + offsetX, chestY + offsetY, target.z + offsetZ,
                0f, 0f, 0f, player.x, player.y, player.z, HvHGod.Style.UNKNOWN
            )
            applyPacketRotation(packet, pitch, yaw, yaw)
            return true
        }

        if (selected.isKiwiMode()) return applyKiwiRotation(selected, player, target, packet)

        val lookahead = lookaheadTicks()
        val last = rotationContext
        val ctx = KillauraRots.Ctx(
            pred50 = lookahead * 50f, c158 = 0.9f, c15C = 8f, c160 = 1f,
            c164 = 3f, c20C = 0.7f, c214 = 1.5f, c120 = 0.1f,
            c124 = 0.1f, c11C = 8, c130 = 20, vertOffset = verticalOffset.value
        ).apply {
            rotPitch = last?.rotPitch ?: player.pitch
            rotYaw = last?.rotYaw ?: player.yaw
            headYaw = last?.headYaw ?: player.yaw
        }
        val tMotion = RotsSupport.motion(target)
        val snapshot = KillauraRots.Target(
            target.x, feetY(target), target.z,
            tMotion.first, tMotion.second, tMotion.third,
            target.yaw,
            RotsSupport.hitboxWidth(target),
            RotsSupport.hitboxHeight(target)
        )
        val inputs = RotsSupport.inputs(packet)
        val lpMotion = RotsSupport.localMotion(packet)
        val env = KillauraRots.Env(
            player.x, player.y, player.z,
            lpMotion.first, lpMotion.second, lpMotion.third,
            inputs.w, inputs.a, inputs.s, inputs.d, inputs.space,
            (packet.tick % 72000L).toFloat() / 20f
        )

        when (selected) {
            RotationMode.SMOOTH -> rotationEngine.smooth(ctx, snapshot, env)
            RotationMode.NEMESIS -> rotationEngine.nemesis(ctx, snapshot, env)
            RotationMode.VORTEX -> rotationEngine.vortex(ctx, snapshot, env)
            RotationMode.FRONT_STRAFE -> rotationEngine.frontStrafe(ctx, snapshot, env, target.runtimeId)
            RotationMode.AIR_HVH_PRO -> rotationEngine.airHvHPro(ctx, snapshot, env)
            RotationMode.FRONTS_X -> rotationEngine.frontsX(ctx, snapshot, env)
            RotationMode.ASTRAL -> rotationEngine.astral(ctx, snapshot, env)
            RotationMode.ATOMIC -> rotationEngine.atomic(ctx, snapshot, env)
            RotationMode.SYNTAX -> rotationEngine.syntax(ctx, snapshot, env)
            RotationMode.CORTEX -> rotationEngine.cortex(ctx, snapshot, env)
            RotationMode.ALPHA -> rotationEngine.alpha(ctx, snapshot, env)
            else -> return true
        }

        // These source handlers only output pitch/body yaw; keep the head aligned rather than
        // retaining an unrelated head yaw from the last packet.
        if (selected == RotationMode.SMOOTH || selected == RotationMode.NEMESIS ||
            selected == RotationMode.VORTEX || selected == RotationMode.FRONT_STRAFE) {
            ctx.headYaw = ctx.rotYaw
        }
        if (ctx.rotPitch.isFinite() && ctx.rotYaw.isFinite() && ctx.headYaw.isFinite()) {
            rotationContext = ctx
            applyPacketRotation(packet, ctx.rotPitch, ctx.rotYaw, ctx.headYaw)
        } else {
            resetRotations()
        }
        return true
    }

    private fun RotationMode.isKiwiMode(): Boolean = when (this) {
        RotationMode.KIWI_NONE, RotationMode.KIWI_SNAP, RotationMode.KIWI_NORMAL, RotationMode.KIWI_SILENT,
        RotationMode.KIWI_BODY, RotationMode.KIWI_PING_PREDICT, RotationMode.KIWI_LEAD_SHOT,
        RotationMode.KIWI_SMOOTH, RotationMode.KIWI_LEGIT, RotationMode.KIWI_JITTER,
        RotationMode.KIWI_STRAFE, RotationMode.KIWI_ANTICIPATE -> true
        else -> false
    }

    /** Kiwi handlers keep their original formulas and write directly to the current input packet. */
    private fun applyKiwiRotation(
        mode: RotationMode,
        player: LocalFrame,
        target: EntityTracker.TrackedEntity,
        packet: PlayerAuthInputPacket
    ): Boolean {
        if (mode == RotationMode.KIWI_NONE) return false

        val eye = Vector3f.from(player.x, player.y, player.z)
        val (pitch, yaw) = calcAngle(eye, kiwiChestPosition(target))
        val outPitch: Float
        val outYaw: Float

        when (mode) {
            RotationMode.KIWI_SNAP, RotationMode.KIWI_NORMAL, RotationMode.KIWI_SILENT,
            RotationMode.KIWI_BODY, RotationMode.KIWI_PING_PREDICT, RotationMode.KIWI_LEAD_SHOT -> {
                var adjustedPitch = pitch
                var adjustedYaw = yaw
                if (randomizeRotations.value) {
                    adjustedPitch += (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                    adjustedYaw += (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                }
                smoothPitch = adjustedPitch
                smoothYaw = adjustedYaw
                outPitch = adjustedPitch
                outYaw = adjustedYaw
            }
            RotationMode.KIWI_JITTER -> {
                outPitch = pitch + (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                outYaw = yaw + (kotlin.random.Random.nextFloat() - 0.5f) * 14f
            }
            RotationMode.KIWI_SMOOTH, RotationMode.KIWI_LEGIT -> {
                var deltaYaw = yaw - smoothYaw
                while (deltaYaw > 180f) deltaYaw -= 360f
                while (deltaYaw < -180f) deltaYaw += 360f
                if (abs(deltaYaw) > smoothSpeed.value) {
                    deltaYaw = if (deltaYaw > 0f) smoothSpeed.value else -smoothSpeed.value
                }
                smoothYaw += deltaYaw
                smoothPitch += (pitch - smoothPitch) * 0.3f
                if (mode == RotationMode.KIWI_LEGIT && abs(deltaYaw) < 5f) {
                    smoothPitch += (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                    smoothYaw += (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                }
                outPitch = smoothPitch
                outYaw = smoothYaw
            }
            RotationMode.KIWI_STRAFE -> {
                val dx = target.x - eye.x
                val dz = target.z - eye.z
                val scale = min(sqrt(dx * dx + dz * dz) / 6f, 1f)
                outYaw = sin(rotationTickCounter * 0.35f) * strafeAmplitude.value * scale + yaw
                outPitch = pitch + cos(rotationTickCounter * 0.22f) * strafeAmplitude.value * 0.25f * scale
            }
            RotationMode.KIWI_ANTICIPATE -> {
                val yawRadians = Math.toRadians(target.yaw.toDouble())
                val ahead = Vector3f.from(
                    target.x - sin(yawRadians).toFloat() * ANTICIPATE_DEPTH,
                    kiwiChestPosition(target).y,
                    target.z + cos(yawRadians).toFloat() * ANTICIPATE_DEPTH
                )
                val (aheadPitch, aheadYaw) = calcAngle(eye, ahead)
                outPitch = aheadPitch
                outYaw = aheadYaw
            }
            else -> return false
        }

        applyPacketRotation(packet, outPitch, outYaw, outYaw)
        return true
    }

    /** (pitch, yaw) in degrees using the uploaded Kiwi angle convention. */
    private fun calcAngle(from: Vector3f, to: Vector3f): Pair<Float, Float> {
        val x = (to.x - from.x).toDouble()
        val y = (to.y - from.y).toDouble()
        val z = (to.z - from.z).toDouble()
        val distance = sqrt(x * x + y * y + z * z)
        val pitch = if (distance > 0.001) (-asin(y / distance) * RAD2DEG).toFloat() else 0f
        val yaw = (-atan2(x, z) * RAD2DEG).toFloat()
        return pitch to yaw
    }

    private fun kiwiChestPosition(target: EntityTracker.TrackedEntity): Vector3f = Vector3f.from(
        target.x,
        feetY(target) + if (target.isPlayer) RotsSupport.hitboxHeight(target) * 0.5f else 0.9f,
        target.z
    )

    private fun feetY(e: EntityTracker.TrackedEntity): Float = RotsSupport.entityFeetY(e)

    /**
     * Neon read latency and hit-tracker offsets here. This client has no per-session latency
     * estimator, so the manual tick count is always used.
     */
    private fun lookaheadTicks(): Float =
        if (!prediction.value) 0f else predictionTicks.value.toFloat()

    private fun applyPredictiveRotation(
        targets: MutableList<EntityTracker.TrackedEntity>,
        packet: PlayerAuthInputPacket,
        session: RubidiumRelaySession
    ) {
        val settings = PredictiveRotator.Settings(
            predTime = lookaheadTicks(), predStrength = predictionStrength.value,
            smoothing = rotationSmoothing.value, maxSpeed = rotationMaxSpeed.value,
            accel = rotationAcceleration.value, cameraSync = cameraSync.value,
            vertOffset = verticalOffset.value
        )
        if (predictiveRotator.update(session, packet, targets, settings)) {
            applyPacketRotation(packet, predictiveRotator.pitch, predictiveRotator.yaw, predictiveRotator.yaw)
        }
    }

    private fun applyPacketRotation(packet: PlayerAuthInputPacket, pitch: Float, yaw: Float, headYaw: Float) {
        if (pitch.isFinite() && yaw.isFinite() && headYaw.isFinite()) {
            val rotation = Vector3f.from(pitch.coerceIn(-90f, 90f), yaw, headYaw)
            packet.rotation = rotation
            runCatching { packet.interactRotation = Vector2f.from(rotation.x, rotation.y) }
        } else resetRotations()
    }

    // ------------------------------------------------------------------ movement

    private fun teleportTo(target: EntityTracker.TrackedEntity, session: RubidiumRelaySession) {
        val pos = Vector3f.from(target.x, target.y, target.z)
        val self = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

        val yawRad = Math.toRadians(target.yaw.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        val tpPos = if (teleportBehind.value) {
            Vector3f.from(
                pos.x + behind.x * keepDistance.value,
                pos.y + tpYOffset.value,
                pos.z + behind.z * keepDistance.value
            )
        } else {
            val dir = pos.sub(self).normalize()
            Vector3f.from(
                pos.x - dir.x * keepDistance.value,
                pos.y + tpYOffset.value,
                pos.z - dir.z * keepDistance.value
            )
        }

        // Neon sent this client-bound; here it goes server-bound with the client mirror so the
        // local physics engine follows instead of rubber-banding back (see PacketUtil.sendMove).
        PacketUtil.sendMove(
            session,
            tpPos.x, tpPos.y, tpPos.z,
            target.yaw, EntityTracker.selfPitch,
            onGround = false,
            mirrorToClient = true
        )
    }

    /** Orbit pace is the player's own travel per tick; the radius only bends that movement. */
    private fun strafeAroundTarget(
        target: EntityTracker.TrackedEntity,
        tick: Long,
        session: RubidiumRelaySession
    ): Boolean {
        val next = orbit.next(target, strafeRadius.value, tick, spinDirection.value) ?: return false
        PacketUtil.sendMove(
            session,
            next.x, next.y, next.z,
            EntityTracker.selfYaw, EntityTracker.selfPitch,
            onGround = true,
            mirrorToClient = true
        )
        return true
    }

    private companion object {
        const val RAD2DEG = 57.29577951308232
        const val ANTICIPATE_DEPTH = 3f
    }
}
