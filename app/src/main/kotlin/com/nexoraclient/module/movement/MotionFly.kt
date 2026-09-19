package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.proxy.MovementCompliance
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.math.vector.Vector2f
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

    // ── Shortcut ──────────────────────────────────────
    private val shortcut      = bool("Shortcut",         false)   // appears in floating shortcut bar

    // ── Legit Vertical (v3-lite, EXPERIMENTAL) ──
    // Flat hovering is hard physical proof of flight to any server that
    // simulates gravity. With this on, the vertical channel instead emits a
    // simulated vanilla trajectory: full gravity + drag, jump impulse every
    // 400ms (vanilla hop cadence). Altitude gain ≈1 block/s (WAY slower than
    // hover fly) but the trajectory looks like aggressive bunny-hopping —
    // physics-consistent by construction. WANT_UP = keep hopping, WANT_DOWN
    // sink assist. Horizontal channel is unaffected (still governor-scaled).
    private val legitVertical = bool("Legit Vertical",   false)

    // ── State ──────────────────────────────────────────
    private var lastPos = Vector3f.ZERO

    // Legit Vertical simulated ballistic state
    private var vySim = 0f
    private var lastJumpMs = 0L

    override fun onEnable() {
        super.onEnable()
        lastPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
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

        val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

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
        val maxHoriz = sqrt(5.99)  // Double
        horizSpeed = min(horizSpeed, maxHoriz.toFloat())

        if (wantUp) horizSpeed *= upHFactor.value
        if (wantDown) horizSpeed *= downHFactor.value

        // ── Vertical speed ──────────────────────────
        var vertSpeed = when {
            wantUp -> upSpeedBPS.value / 20f
            wantDown -> -downSpeedBPS.value / 20f + glide.value
            else -> glide.value
        }

        // ── Compliance governor (NoLagback ADAPTIVE, v2.2 shared helpers) ──
        if (MovementCompliance.adaptive) {
            horizSpeed = MovementCompliance.governedSpeed(horizSpeed)
            if (MovementCompliance.shouldSettleVertical() && vertSpeed > glide.value) {
                vertSpeed = glide.value
            }
        }

        // ── Legit Vertical override (v3-lite) ──
        if (legitVertical.value) {
            if (MovementCompliance.adaptive && MovementCompliance.ageOfLastCorrectionMs() < 100L) vySim = 0f
            vySim = (vySim - 0.08f) * 0.98f          // vanilla gravity + air drag
            if (wantUp && System.currentTimeMillis() - lastJumpMs >= 400L) {
                vySim = 0.42f                          // vanilla jump impulse (blocks/tick)
                lastJumpMs = System.currentTimeMillis()
            }
            if (wantDown) vySim -= 0.15f
            vySim = vySim.coerceIn(-3.0f, 1.0f)
            vertSpeed = vySim
        }

        // ── Input rotation ──────────────────────────
        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val yawRad = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yawRad.toDouble()).toFloat()
        val cosYaw = cos(yawRad.toDouble()).toFloat()

        val forward = inputZ
        val strafe = inputX

        var motionX = strafe * cosYaw - forward * sinYaw
        var motionZ = forward * cosYaw + strafe * sinYaw

        // Normalize and scale
        val len = sqrt(motionX.toDouble() * motionX.toDouble() + motionZ.toDouble() * motionZ.toDouble()).toFloat()
        if (len > 0.001f) {
            motionX = motionX / len * horizSpeed
            motionZ = motionZ / len * horizSpeed
        } else {
            motionX = 0f
            motionZ = 0f
        }

        // ── Send motion packet ──────────────────────
        val motionPacket = SetEntityMotionPacket()
        motionPacket.runtimeEntityId = EntityTracker.selfRuntimeId
        motionPacket.motion = Vector3f.from(motionX, vertSpeed, motionZ)
        session.clientBound(motionPacket)

        // Update last position to current
        lastPos = selfPos
    }
}
