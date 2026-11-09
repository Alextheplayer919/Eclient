package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.*

object RotationUtil {

    data class Rotation(val yaw: Float, val pitch: Float)

    fun toEntity(e: EntityTracker.TrackedEntity): Rotation =
        toPoint(e.x, e.y + 1.62f, e.z)

    fun toPoint(tx: Float, ty: Float, tz: Float): Rotation {
        val dx = tx - EntityTracker.selfX
        val dy = ty - (EntityTracker.selfY + 1.62f)
        val dz = tz - EntityTracker.selfZ
        val dist = sqrt(dx * dx + dz * dz).toDouble()
        val yaw   = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = Math.toDegrees(-atan2(dy.toDouble(), dist)).toFloat()
        return Rotation(yaw, pitch)
    }

    fun approximate(r: Rotation, yawJitter: Float = 2f, pitchJitter: Float = 1f): Rotation {
        val y = r.yaw   + (Math.random() * yawJitter * 2 - yawJitter).toFloat()
        val p = r.pitch + (Math.random() * pitchJitter * 2 - pitchJitter).toFloat()
        return Rotation(y.coerceIn(-180f, 180f), p.coerceIn(-90f, 90f))
    }

    // "Legit" smoothing: sabit bir smoothFactor kullanan exponential smoothing
    // (KillAura/KillAuraPro head lock'ta olduğu gibi) her tick AYNI oranda
    // döner — bu, rotation-speed-consistency check'lerinin (bkz. MX
    // Anticheat, LiquidBounce forum "Rotation Speed Consistency") tam
    // yakaladığı patern: %75-100 sabit dönüş hızı. Buradaki fark: smoothFactor
    // her çağrıda küçük bir aralıkta rastgele değişiyor (speedJitter) ve
    // sonuca da küçük bir açısal jitter ekleniyor — yani hem hız hem de
    // varış açısı tick'ten tick'e biraz değişkenlik gösteriyor, tam sabit
    // oran/GCD paterni oluşmuyor.
    fun smoothTo(
        currentYaw: Float, currentPitch: Float,
        target: Rotation,
        baseFactor: Float = 0.25f,
        speedJitter: Float = 0.08f,
        yawJitter: Float = 0.6f,
        pitchJitter: Float = 0.3f
    ): Rotation {
        val factor = (baseFactor + (Math.random() * speedJitter * 2 - speedJitter).toFloat())
            .coerceIn(0.02f, 1f)

        var diff = target.yaw - currentYaw
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val rawYaw = currentYaw + diff * factor
        val rawPitch = currentPitch + (target.pitch - currentPitch) * factor

        val jitteredYaw = rawYaw + (Math.random() * yawJitter * 2 - yawJitter).toFloat()
        val jitteredPitch = rawPitch + (Math.random() * pitchJitter * 2 - pitchJitter).toFloat()

        return Rotation(normalize(jitteredYaw), jitteredPitch.coerceIn(-90f, 90f))
    }

    fun normalize(yaw: Float): Float {
        var y = yaw % 360f
        if (y > 180f)  y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    fun angleDiff(a: Float, b: Float): Float = abs(normalize(a - b))

    fun fovCheck(e: EntityTracker.TrackedEntity, fovDeg: Float): Boolean {
        if (fovDeg >= 360f) return true
        val r = toEntity(e)
        return angleDiff(r.yaw, EntityTracker.selfYaw) <= fovDeg / 2f
    }

    fun buildMovePacket(
        yaw: Float,
        pitch: Float,
        teleport: Boolean = false,
        onGround: Boolean = true
    ): org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket =
        org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            rotation              = Vector3f.from(pitch, yaw, yaw)
            mode                  = if (teleport) org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket.Mode.TELEPORT
                                    else          org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        }

    // =====================================================================
    // Orbit (port of Neon `RotationUtils.Orbit`)
    // =====================================================================

    /** Highest per-tick travel still treated as flying rather than a lagback/teleport. */
    private const val MAX_PLAUSIBLE_TRAVEL_PER_TICK = 8f
    private const val MIN_TRAVEL_PER_TICK = 0.02f
    private const val ORBIT_MIN_RADIUS = 0.6f
    private const val ORBIT_FALLBACK_RADIUS = 2.5f
    private const val CORRECTION_SETTLE_TICKS = 3
    private const val SPEED_DECAY = 0.5f
    private const val CAPTURE_RADIUS_FACTOR = 2f
    private const val CAPTURE_MARGIN = 2f

    /**
     * Tracks an orbit around a target whose pace is the player's own movement rather than a
     * configured speed. Each tick advances the player around the target by exactly the distance
     * the client covered on its own, while walking them back onto the radius.
     *
     * The measurement excludes the commanded steps, so the orbit cannot feed its own speed back
     * into itself. Standing still means no orbit.
     */
    class Orbit {

        /** Which way the player travels around the target while spinning. */
        enum class SpinDirection {
            /** Counter-clockwise around the target: the player's left when facing it. */
            LEFT,
            /** Clockwise around the target: the player's right when facing it. */
            RIGHT
        }

        private var targetId: Long? = null
        private var lastTick: Long? = null

        /** Last position the orbit commanded; the anchor for the next measurement. */
        private var commandedPosition: Vector3f? = null

        private var travelPerTick = 0f
        private var settleTicks = 0

        /**
         * Last position this orbit commanded. [EntityTracker] is only updated by packets from the
         * wire, so the local player's tracked position does not include our own steps; measuring
         * `tracked - anchor` would double-count them and make the orbit accelerate itself. The
         * real travel between commands is therefore reconstructed as
         * `tracked + pendingOffset - anchor`.
         */
        private var pendingX = 0f
        private var pendingZ = 0f

        fun reset() {
            targetId = null
            lastTick = null
            commandedPosition = null
            travelPerTick = 0f
            settleTicks = 0
            pendingX = 0f
            pendingZ = 0f
        }

        /**
         * Drop the old orbit state after a server correction. Waiting a few ticks before starting
         * again avoids immediately replaying the position that caused a rubber-band.
         */
        fun onServerCorrection() {
            targetId = null
            lastTick = null
            commandedPosition = null
            travelPerTick = 0f
            settleTicks = CORRECTION_SETTLE_TICKS
            pendingX = 0f
            pendingZ = 0f
        }

        /**
         * Next orbit position around [target], or null when the orbit should not move the player
         * this tick (new target, settling after a correction, not moving, or too far to orbit).
         */
        fun next(
            target: EntityTracker.TrackedEntity,
            radius: Float,
            tick: Long,
            direction: SpinDirection
        ): Vector3f? {
            val current = Vector3f.from(
                EntityTracker.selfX + pendingX,
                EntityTracker.selfY,
                EntityTracker.selfZ + pendingZ
            )

            // New target: anchor on the player's real position and let them move first.
            if (targetId != target.runtimeId) {
                targetId = target.runtimeId
                lastTick = tick
                pendingX = 0f
                pendingZ = 0f
                commandedPosition = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                travelPerTick = 0f
                if (settleTicks > 0) settleTicks--
                return null
            }

            val elapsedTicks = (tick - (lastTick ?: tick)).coerceAtLeast(1L).toFloat()
            lastTick = tick

            val anchor = commandedPosition
            if (anchor == null) {
                commandedPosition = current
                return null
            }

            // How far the client moved on its own since the last command.
            val freeTravel = hypot(current.x - anchor.x, current.z - anchor.z) / elapsedTicks
            if (!freeTravel.isFinite() || freeTravel > MAX_PLAUSIBLE_TRAVEL_PER_TICK) {
                onServerCorrection()
                return null
            }
            travelPerTick = if (freeTravel > travelPerTick) {
                freeTravel
            } else {
                travelPerTick + (freeTravel - travelPerTick) * SPEED_DECAY
            }

            if (settleTicks > 0) {
                settleTicks--
                commandedPosition = current
                return null
            }
            if (travelPerTick < MIN_TRAVEL_PER_TICK) {
                commandedPosition = current
                return null
            }

            val r = radius.takeIf { it.isFinite() }?.coerceAtLeast(ORBIT_MIN_RADIUS) ?: ORBIT_FALLBACK_RADIUS
            val relativeX = anchor.x - target.x
            val relativeZ = anchor.z - target.z
            val distanceToTarget = hypot(relativeX, relativeZ)
            if (distanceToTarget < 1e-3f) {
                commandedPosition = current
                return null
            }

            if (distanceToTarget > r * CAPTURE_RADIUS_FACTOR + CAPTURE_MARGIN) {
                // Too far to orbit: let the movement module bring us in.
                commandedPosition = current
                return null
            }

            val travel = (travelPerTick * elapsedTicks).coerceAtMost(MAX_PLAUSIBLE_TRAVEL_PER_TICK)
            val currentAngle = Math.atan2(relativeZ.toDouble(), relativeX.toDouble()).toFloat()
            val arc = travel / r
            val nextAngle = if (direction == SpinDirection.LEFT) currentAngle + arc else currentAngle - arc

            val desiredX = target.x + cos(nextAngle) * r
            val desiredZ = target.z + sin(nextAngle) * r

            var moveX = desiredX - anchor.x
            var moveZ = desiredZ - anchor.z
            val requestedDistance = hypot(moveX, moveZ)
            if (requestedDistance < 1e-4f) {
                commandedPosition = current
                return null
            }

            // The commanded step is never longer than the player's own travel.
            if (requestedDistance > travel) {
                val scale = travel / requestedDistance
                moveX *= scale
                moveZ *= scale
            }

            val next = Vector3f.from(anchor.x + moveX, current.y, anchor.z + moveZ)
            commandedPosition = next
            pendingX += moveX
            pendingZ += moveZ
            return next
        }
    }
}
