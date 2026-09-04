package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket

class NoHurtCam : BaseModule(
    name        = "NoHurtCam",
    category    = ModuleCategory.VISUAL,
    description = "Hasar alma kafa sallantısını ve ekran titreşimini engeller"
) {
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        when (val pkt = event.packet) {
            is EntityEventPacket -> {
                if (pkt.runtimeEntityId == EntityTracker.selfRuntimeId) {
                    val typeStr = runCatching { pkt.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                    if (typeStr.contains("HURT")) {
                        event.cancel()
                    }
                }
            }
            is AnimatePacket -> {
                if (pkt.runtimeEntityId == EntityTracker.selfRuntimeId) {
                    val actionStr = runCatching { pkt.action?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                    if (actionStr.contains("HURT")) {
                        event.cancel()
                    }
                }
            }
        }
    }
}
