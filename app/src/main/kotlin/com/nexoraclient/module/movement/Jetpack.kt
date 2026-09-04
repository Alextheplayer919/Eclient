package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

class Jetpack : BaseModule(
    name        = "Jetpack",
    category    = ModuleCategory.MOVEMENT,
    description = "Zıplama tuşuna basılı tutulduğunda karakterin baktığı yöne doğru motion paketiyle itiş uygular"
) {
    private val thrustSpeed = float("Thrust Speed", 1.2f, 0.1f, 3.0f)
    private val requireJump = bool ("Require Jump", true)
    private val interval    = int  ("Delay",        55,   15, 150)
    private val shortcut    = bool ("Shortcut",     false)

    @Volatile private var lastThrustMs = 0L

    override fun onEnable() {
        super.onEnable()
        lastThrustMs = 0L
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val jumping = pkt.inputData.contains(PlayerAuthInputData.JUMPING) ||
                      pkt.inputData.contains(PlayerAuthInputData.WANT_UP)

        if (requireJump.value && !jumping) return

        // FIX: eskiden throttle yoktu - jump basılı tutulurken HER
        // PlayerAuthInputPacket'te (saniyede onlarca kez) bir motion paketi
        // gönderiliyordu. Diğer motion modülleriyle (BypassFly/MotionFly)
        // aynı aralık kısıtlaması eklendi.
        val now = System.currentTimeMillis()
        if (now - lastThrustMs < interval.value) return
        lastThrustMs = now

        val pitch = Math.toRadians(pkt.rotation.x.toDouble()).toFloat()
        val yaw   = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()

        val cosPitch = cos(pitch)

        val dirX = -sin(yaw) * cosPitch
        val dirY = -sin(pitch)
        val dirZ = cos(yaw) * cosPitch

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                dirX * thrustSpeed.value,
                dirY * thrustSpeed.value,
                dirZ * thrustSpeed.value
            )
        }

        event.session.clientBound(motionPacket)
    }
}
