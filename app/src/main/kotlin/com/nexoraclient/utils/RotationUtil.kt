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
}
