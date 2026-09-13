package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.PLAYER,
    description = "Totemi sürekli sol ele takar"
) {
    override val priority = 0
    private val shortcut = bool("Shortcut", false)

    companion object {
        // How long to wait for the server to confirm our move packet before retrying.
        // Real round trip is ~30-80ms; 200ms is generous but not glacial.
        private const val PENDING_TIMEOUT_MS = 200L

        // Safety-net tick. Cheap now that we don't allocate in the loop.
        private const val SAFETY_TICK_MS = 20L

        // Per-slot failure counter — after this many failed attempts on the same
        // slot, mark it unusable for a bit and try the next totem slot instead.
        private const val MAX_FAILS_PER_SLOT = 2
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var totemSlot        = -1
    @Volatile private var offhandHasTotem  = false
    @Volatile private var pendingSendMs    = 0L   // 0 = not waiting, else = timestamp of last send
    @Volatile private var pendingSlotSent  = -1
    @Volatile private var lastSendMs       = 0L

    // Slots we've recently failed on — skip them until we've tried the others.
    private val failedSlots = ConcurrentHashMap<Int, Int>()   // slot -> fail count

    override fun onEnable() {
        super.onEnable()
        totemSlot         = -1
        offhandHasTotem   = false
        pendingSendMs     = 0L
        pendingSlotSent   = -1
        lastSendMs        = 0L
        failedSlots.clear()
        refreshFromSnapshot()
        if (!offhandHasTotem && totemSlot >= 0) equipTotem()
        tickJob = launchTickLoop(SAFETY_TICK_MS) { tickCheck() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    /**
     * Zero-allocation refresh. Iterates slot by slot instead of taking a full
     * Map<Int, ItemData> copy of the entire inventory.
     */
    private fun refreshFromSnapshot() {
        offhandHasTotem = InventoryUtil.isTotem(EntityTracker.getInventoryItem(InventoryUtil.OFFHAND_SLOT))
        totemSlot = -1
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            if (InventoryUtil.isTotem(EntityTracker.getInventoryItem(slot))) {
                // Skip slots we've recently marked as broken
                val fails = failedSlots[slot] ?: 0
                if (fails >= MAX_FAILS_PER_SLOT) continue
                totemSlot = slot
                break
            }
        }
        // If every slot was skipped due to fails, clear fails and pick the first one anyway
        if (totemSlot == -1 && !offhandHasTotem) {
            failedSlots.clear()
            for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
                if (InventoryUtil.isTotem(EntityTracker.getInventoryItem(slot))) {
                    totemSlot = slot
                    break
                }
            }
        }
    }

    private fun tickCheck() {
        val now = System.currentTimeMillis()

        // Resolve pending state: did the server confirm or reject?
        if (pendingSendMs > 0L) {
            if (offhandHasTotem) {
                // Server confirmed — clear pending, reset fail count for the slot we used
                pendingSendMs = 0L
                pendingSlotSent = -1
                failedSlots.remove(pendingSlotSent)
                return
            }
            if (now - pendingSendMs > PENDING_TIMEOUT_MS) {
                // Timed out — server never confirmed. Count this as a failure on that slot.
                val failedSlot = pendingSlotSent
                if (failedSlot >= 0) {
                    val fails = (failedSlots[failedSlot] ?: 0) + 1
                    failedSlots[failedSlot] = fails
                    DiagLog.log("AutoTotem", "slot $failedSlot timed out, fails=$fails")
                }
                pendingSendMs = 0L
                pendingSlotSent = -1
                // Force refresh so we can pick a different slot if this one is broken
                refreshFromSnapshot()
                // fall through to try again
            } else {
                // Still waiting — do nothing
                return
            }
        }

        // Recheck local state
        val hasTotemNow = InventoryUtil.isTotem(EntityTracker.getInventoryItem(InventoryUtil.OFFHAND_SLOT))
        offhandHasTotem = hasTotemNow
        if (hasTotemNow) return

        // Refresh cached slot if it's stale
        if (totemSlot < 0 || !InventoryUtil.isTotem(EntityTracker.getInventoryItem(totemSlot))) {
            refreshFromSnapshot()
        }
        if (totemSlot < 0) return

        equipTotem()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                when (pkt.containerId) {
                    0 -> {
                        // Full inventory refresh — rebuild totemSlot from scratch
                        totemSlot = -1
                        pkt.contents.forEachIndexed { slot, item ->
                            if (totemSlot == -1 && InventoryUtil.isTotem(item)) {
                                val fails = failedSlots[slot] ?: 0
                                if (fails < MAX_FAILS_PER_SLOT) totemSlot = slot
                            }
                        }
                        if (!offhandHasTotem && totemSlot >= 0) equipTotem()
                    }
                    InventoryUtil.OFFHAND_SLOT -> {
                        val nowHasTotem = InventoryUtil.isTotem(pkt.contents.firstOrNull())
                        offhandHasTotem = nowHasTotem
                        if (nowHasTotem) {
                            // Confirmed! Clear pending + reset fail counter
                            failedSlots.remove(pendingSlotSent)
                            pendingSendMs = 0L
                            pendingSlotSent = -1
                        } else {
                            // Server says offhand is empty — if we had a pending send,
                            // it just got resolved (as a rejection)
                            if (pendingSendMs > 0L) {
                                val failedSlot = pendingSlotSent
                                if (failedSlot >= 0) {
                                    failedSlots[failedSlot] = (failedSlots[failedSlot] ?: 0) + 1
                                }
                                pendingSendMs = 0L
                                pendingSlotSent = -1
                                refreshFromSnapshot()
                            }
                            if (totemSlot >= 0) equipTotem()
                        }
                    }
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId == InventoryUtil.OFFHAND_SLOT) {
                    val nowHasTotem = InventoryUtil.isTotem(pkt.item)
                    offhandHasTotem = nowHasTotem
                    if (nowHasTotem) {
                        failedSlots.remove(pendingSlotSent)
                        pendingSendMs = 0L
                        pendingSlotSent = -1
                    } else {
                        if (pendingSendMs > 0L) {
                            val failedSlot = pendingSlotSent
                            if (failedSlot >= 0) {
                                failedSlots[failedSlot] = (failedSlots[failedSlot] ?: 0) + 1
                            }
                            pendingSendMs = 0L
                            pendingSlotSent = -1
                            refreshFromSnapshot()
                        }
                        if (totemSlot >= 0) equipTotem()
                    }
                } else if (pkt.containerId == 0) {
                    if (InventoryUtil.isTotem(pkt.item)) {
                        if (totemSlot == -1) totemSlot = pkt.slot
                    } else if (totemSlot == pkt.slot) {
                        // Our cached source slot just got emptied (either by us, or by
                        // the player moving stuff around). Rescan.
                        totemSlot = -1
                        refreshFromSnapshot()
                    }
                }
            }

            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val type = runCatching { pkt.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (type.contains("CONSUME") || type.contains("TOTEM")) {
                    // Totem was popped. Offhand is empty NOW.
                    offhandHasTotem = false
                    totemSlot = -1
                    pendingSendMs = 0L
                    pendingSlotSent = -1
                    // Don't clear failedSlots — those failures are still valid
                    refreshFromSnapshot()
                    if (totemSlot >= 0) equipTotem()
                }
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return

        // If we're already waiting on a server confirmation, don't fire again.
        if (pendingSendMs > 0L && System.currentTimeMillis() - pendingSendMs < PENDING_TIMEOUT_MS) return

        val itemData = EntityTracker.getInventoryItem(slot)
        if (itemData == null || !InventoryUtil.isTotem(itemData)) {
            totemSlot = -1
            return
        }

        val session = PacketEventBus.currentSession ?: return
        val offhandItem = EntityTracker.getInventoryItem(InventoryUtil.OFFHAND_SLOT) ?: ItemData.AIR

        val now = System.currentTimeMillis()
        lastSendMs = now
        pendingSendMs = now
        pendingSlotSent = slot

        InventoryUtil.sendInventoryMove(
            session           = session,
            sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceContainerId = 0,
            sourceSlot        = slot,
            sourceItem        = itemData,
            destContainer     = ContainerSlotType.OFFHAND,
            destContainerId   = ContainerId.OFFHAND,
            destSlot          = 0,
            destItem          = offhandItem
        )
    }
}
