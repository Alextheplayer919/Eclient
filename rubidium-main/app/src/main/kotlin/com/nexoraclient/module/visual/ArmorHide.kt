package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket

class ArmorHide : BaseModule(
    name        = "ArmorHide",
    category    = ModuleCategory.VISUAL,
    description = "Sunucudan gelen zırh verisini client'a boş gösterip 3D zırh modelini gizler"
) {

    private val shortcut = bool("Shortcut", false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                if (pkt.containerId != ContainerId.ARMOR) return
                val list = pkt.contents
                if (list is MutableList<ItemData>) {
                    var changed = false
                    for (i in list.indices) {
                        if (list[i] != ItemData.AIR) { list[i] = ItemData.AIR; changed = true }
                    }
                    if (changed) event.cancelAndReplace(pkt)
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId != ContainerId.ARMOR) return
                if (pkt.item == ItemData.AIR) return
                pkt.item = ItemData.AIR
                event.cancelAndReplace(pkt)
            }

            is MobArmorEquipmentPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                var changed = false
                if (pkt.helmet != ItemData.AIR) { pkt.helmet = ItemData.AIR; changed = true }
                if (pkt.chestplate != ItemData.AIR) { pkt.chestplate = ItemData.AIR; changed = true }
                if (pkt.leggings != ItemData.AIR) { pkt.leggings = ItemData.AIR; changed = true }
                if (pkt.boots != ItemData.AIR) { pkt.boots = ItemData.AIR; changed = true }
                if (changed) event.cancelAndReplace(pkt)
            }

            else -> {}
        }
    }
}
