package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class NoFallDamage : BaseModule(
    name        = "NoFallDamage",
    category    = ModuleCategory.MOVEMENT,
    description = "Düşüş mesafesini periyodik reset ile sunucuya sıfırlatır"
) {

    private val triggerDistance = float("Trigger Distance", 3f, 1f, 10f)
    private val minIntervalMs   = int  ("Min Interval (ms)", 150, 50, 2000)
    private val shortcut        = bool ("Shortcut", false)

    @Volatile private var lastY       = 0f
    @Volatile private var fallDistance = 0f
    @Volatile private var lastResetMs  = 0L
    @Volatile private var initialized  = false

    override fun onEnable() {
        super.onEnable()
        lastY        = EntityTracker.selfY
        fallDistance = 0f
        lastResetMs  = 0L
        initialized  = true
    }

    override fun onDisable() {
        super.onDisable()
        initialized = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val (x, y, z, yaw, pitch, reportedOnGround) = when (val pkt = event.packet) {
            is PlayerAuthInputPacket -> {
                if (!initialized) { lastY = pkt.position.y; initialized = true }
                MoveSnapshot(pkt.position.x, pkt.position.y, pkt.position.z, pkt.rotation.y, pkt.rotation.x, null)
            }
            is MovePlayerPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (!initialized) { lastY = pkt.position.y; initialized = true }
                MoveSnapshot(pkt.position.x, pkt.position.y, pkt.position.z, pkt.rotation.y, pkt.rotation.x, pkt.isOnGround)
            }
            else -> return
        }

        val deltaY = y - lastY
        lastY = y

        if (reportedOnGround == true) {
            fallDistance = 0f
        } else if (deltaY < 0f) {
            fallDistance += -deltaY
        }

        val now = System.currentTimeMillis()
        if (fallDistance >= triggerDistance.value && now - lastResetMs >= minIntervalMs.value) {
            val session = event.session
            PacketUtil.sendMove(
                session        = session,
                x              = x,
                y              = y,
                z              = z,
                yaw            = yaw,
                pitch          = pitch,
                onGround       = true,
                teleport       = false,
                mirrorToClient = false
            )
            fallDistance = 0f
            lastResetMs  = now
        }
    }

    private data class MoveSnapshot(
        val x: Float, val y: Float, val z: Float,
        val yaw: Float, val pitch: Float,
        val reportedOnGround: Boolean?
    )
}
