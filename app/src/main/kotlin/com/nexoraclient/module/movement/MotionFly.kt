package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.*

class MotionFly : BaseModule(
    name        = "MotionFly",
    category    = ModuleCategory.MOVEMENT,
    description = "LeHu-style fly (Testfly port)"
) {

    // ── LeHu settings ──────────────────────────────────
    private val hSpeedBPS     = float("H Speed BPS",     46.0f, 1.0f, 100.0f)
    private val upSpeedBPS    = float("Up Speed BPS",    19.8f, 1.0f, 60.0f)
    private val downSpeedBPS  = float("Down Speed BPS",  46.0f, 1.0f, 60.0f)
    private val glide         = float("Glide",           -0.02f, -0.3f, 0.0f)
    private val upHFactor     = float("Up H Factor",     0.55f, 0.1f, 1.0f)
    private val downHFactor   = float("Down H Factor",   0.55f, 0.1f, 1.0f)

    // ── State ──────────────────────────────────────────
    private var lastPos = Vector3f.ZERO

    override fun onEnable() {
        super.onEnable()
        lastPos = EntityTracker.getSelfPosition() ?: Vector3f.ZERO
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        val session = event.session

        val selfPos = EntityTracker.getSelfPosition() ?: return

        // ── Anti‑rubberband: distance check ──────────
        val dx = selfPos.x - lastPos.x
        val dy = selfPos.y - lastPos.y
        val dz = selfPos.z - lastPos.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > 4.5f) {
            lastPos = selfPos
            return
        }

        // ── Read input ──────────────────────────────
        val wantUp = pkt.inputData.contains(PlayerAuthInputData.WANT_UP) ||
                pkt.inputData.contains(PlayerAuthInputData.JUMPING)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN) ||
                pkt.inputData.contains(PlayerAuthInputData.SNEAKING)

        // If no input and no motion and glide is zero → skip
        val motion = pkt.motion
        if (!wantUp && !wantDown && motion.x == 0f && motion.y == 0f && glide.value == 0f) {
            lastPos = selfPos
            return
        }

        // ── Horizontal speed ──────────────────────────
        var horizSpeed = hSpeedBPS.value / 20f
        val maxHoriz = sqrt(5.99f)  // ~2.447
        horizSpeed = min(horizSpeed, maxHoriz)

        if (wantUp) horizSpeed *= upHFactor.value
        if (wantDown) horizSpeed *= downHFactor.value

        // ── Vertical speed ──────────────────────────
        val vertSpeed = when {
            wantUp -> upSpeedBPS.value / 20f
            wantDown -> -downSpeedBPS.value / 20f + glide.value
            else -> glide.value
        }

        // ── Input rotation ──────────────────────────
        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val yawRad = Math.toRadians(pkt.rotation.y.toDouble())
        val sinYaw = sin(yawRad)
        val cosYaw = cos(yawRad)

        // Forward/strafe relative to yaw
        val forward = inputZ
        val strafe = inputX

        // Compute motion vector
        var motionX = strafe * cosYaw - forward * sinYaw
        var motionZ = forward * cosYaw + strafe * sinYaw

        // Normalize and scale
        val len = sqrt(motionX * motionX + motionZ * motionZ)
        if (len > 0.001f) {
            motionX = motionX / len * horizSpeed
            motionZ = motionZ / len * horizSpeed
        } else {
            motionX = 0f
            motionZ = 0f
        }

        // ── Send motion packet ──────────────────────
        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(motionX, vertSpeed, motionZ)
        }
        session.clientBound(motionPacket)

        // Update last position to current
        lastPos = selfPos
    }
}
