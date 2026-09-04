package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

class AntiKnockback : BaseModule(
    name        = "AntiKnockback",
    category    = ModuleCategory.MOVEMENT,
    description = "Gelen knockback'i tamamen engeller"
) {
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        val pkt = event.packet
        if (pkt !is SetEntityMotionPacket) return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return

        event.cancel()
    }
}
