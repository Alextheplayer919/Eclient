package com.rubidiumclient.module.combat

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class AntiCrystal : BaseModule(
    name        = "AntiCrystal",
    category    = ModuleCategory.PLAYER,
    description = "Reports a lowered position to the server to reduce end crystal explosion damage"
) {
    private val yLevel   = float("Y Level", 0.4f, 0.1f, 1.61f)
    private val shortcut = bool ("Shortcut", false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return

        // LAG FIX: eskiden her tick'te (~20/sn) ~18 alanı tek tek kopyalayan
        // YEPYENİ bir PlayerAuthInputPacket allocate ediliyordu. Aynı sonucu
        // orijinal paketi in-place mutate edip cancelAndReplace ile
        // göndererek elde ediyoruz — sıfır ekstra allocation, diğer tüm
        // modüllerin (Criticals, KillAuraPro vb.) kullandığı standart kalıp.
        pkt.position = pkt.position.add(0f, -yLevel.value, 0f)
        event.cancelAndReplace(pkt)
    }
}
