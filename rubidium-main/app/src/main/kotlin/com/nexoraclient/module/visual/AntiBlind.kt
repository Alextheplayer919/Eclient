package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket

class AntiBlind : BaseModule(
    name        = "AntiBlind",
    category    = ModuleCategory.VISUAL,
    description = "Blindness efektinin ekran kararmasını engeller"
) {

    private companion object {
        const val BLINDNESS_EFFECT_ID = 15
    }

    private val shortcut = bool("Shortcut", false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
        val pkt = event.packet as? MobEffectPacket ?: return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
        if (pkt.effectId != BLINDNESS_EFFECT_ID) return

        event.cancel()
    }
}
