package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import java.util.concurrent.ConcurrentHashMap

class AutoArmor : BaseModule(
    name        = "AutoArmor",
    category    = ModuleCategory.PLAYER,
    description = "En iyi zırhı otomatik giyer"
) {
    private val shortcut = bool("Shortcut", false)

    companion object {
        private const val RESEND_COOLDOWN_MS = 250L
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    private val lastSendMs = ConcurrentHashMap<Int, Long>()

    override fun onEnable() {
        super.onEnable()
        lastSendMs.clear()
        tickJob = launchTickLoop(300L) { checkAndEquipBestArmor() }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
    }

    private fun checkAndEquipBestArmor() {
        val session = PacketEventBus.currentSession ?: return
        val snapshot = EntityTracker.getInventorySnapshot()

        var bestBySlot = HashMap<InventoryUtil.ArmorSlotType, Pair<Int, ItemData>>()
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            val item = snapshot[slot] ?: continue
            val armorType = InventoryUtil.resolveArmorSlotType(item) ?: continue
            val tier = InventoryUtil.armorMaterialTier(item)
            val current = bestBySlot[armorType]
            if (current == null || tier > InventoryUtil.armorMaterialTier(current.second)) {
                bestBySlot[armorType] = slot to item
            }
        }

        for ((armorType, candidate) in bestBySlot) {
            val (sourceSlot, sourceItem) = candidate
            val equipped = EntityTracker.getArmorItem(armorType.slotIndex)
            val equippedTier = InventoryUtil.armorMaterialTier(equipped)
            val candidateTier = InventoryUtil.armorMaterialTier(sourceItem)

            if (equippedTier >= candidateTier) continue

            val now = System.currentTimeMillis()
            val last = lastSendMs[armorType.slotIndex] ?: 0L
            if (now - last < RESEND_COOLDOWN_MS) continue
            lastSendMs[armorType.slotIndex] = now

            InventoryUtil.sendInventoryMove(
                session           = session,
                sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
                sourceContainerId = 0,
                sourceSlot        = sourceSlot,
                sourceItem        = sourceItem,
                destContainer     = ContainerSlotType.ARMOR,
                destContainerId   = ContainerId.ARMOR,
                destSlot          = armorType.slotIndex,
                destItem          = equipped ?: ItemData.AIR
            )
        }
    }
}
