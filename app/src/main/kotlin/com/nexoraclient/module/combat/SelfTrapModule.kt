package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

class SelfTrapModule : BaseModule(
    name        = "SelfTrap",
    category    = ModuleCategory.COMBAT,
    description = "Kendinizi obsidian ile çevreler, dışarıdan gelen kristal hasarını azaltır"
) {

    companion object {
        private val AIR_IDS = setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")
        private val SELF_OFFSETS = listOf(
            Triple(0, 1, 1), Triple(0, 1, -1),
            Triple(1, 1, 0), Triple(-1, 1, 0),
            Triple(0, 2, 0)
        )
    }

    private val blockId     = enum ("Block", BlockChoice.OBSIDIAN)
    private val placeDelay  = int  ("Place Delay", 80, 30, 500)
    private val includeCeil = bool ("Ceiling", true)
    private val shortcut    = bool ("Shortcut", false)

    enum class BlockChoice(val id: String) {
        OBSIDIAN("minecraft:obsidian"),
        COBBLESTONE("minecraft:cobblestone")
    }

    private var lastPlaceMs = 0L
    private var buildQueue  = ArrayDeque<Triple<Vector3i, String, Int>>()

    override fun onEnable() {
        super.onEnable()
        buildQueue.clear()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        buildQueue.clear()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return

        val session = event.session
        val chosenId = blockId.value.id

        if (buildQueue.isEmpty()) rebuildQueue()
        if (buildQueue.isEmpty()) return

        val prepared = PlacementUtil.prepareItemForUse(session, chosenId) ?: return
        val (pos, supportId, face) = buildQueue.removeFirst()
        val placed = PlacementUtil.sendPlacementUseRaw(session, prepared, pos, supportId, face)
        if (placed) lastPlaceMs = now
        PlacementUtil.revert(session, prepared)
    }

    private fun rebuildQueue() {
        val sx = floor(EntityTracker.selfX).toInt()
        val sy = floor(EntityTracker.selfY).toInt()
        val sz = floor(EntityTracker.selfZ).toInt()
        val offsets = if (includeCeil.value) SELF_OFFSETS else SELF_OFFSETS.filter { it.second < 2 }

        for ((dx, dy, dz) in offsets) {
            val bx = sx + dx; val by = sy + dy; val bz = sz + dz
            val current = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (current !in AIR_IDS) continue
            val neighbor = PlacementUtil.findClickableNeighbor(bx, by, bz) ?: continue
            buildQueue.addLast(neighbor)
        }
    }
}
