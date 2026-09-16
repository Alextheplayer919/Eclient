package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

class AutoTrapModule : BaseModule(
    name        = "AutoTrap",
    category    = ModuleCategory.COMBAT,
    description = "Rakibi obsidian kutu içine hapseder"
) {

    companion object {
        private val AIR_IDS = setOf(
            "minecraft:air","minecraft:cave_air","minecraft:void_air"
        )
        private val TRAP_OFFSETS = listOf(
            Triple(0, 0, 1), Triple(0, 0, -1),
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 1), Triple(0, 1, -1),
            Triple(1, 1, 0), Triple(-1, 1, 0),
            Triple(0, 2, 0),
            Triple(0, 1,  0)
        )
    }

    private val range         = float("Range",         6f, 2f, 12f)
    private val placeDelay    = int  ("Place Delay",   100, 50, 500)
    private val blockId       = enum ("Block", BlockChoice.OBSIDIAN)
    private val opening       = enum ("Opening", OpeningMode.NONE)
    private val includeCeiling= bool ("Ceiling",       true)
    private val ignoreFriends = bool ("Ignore Friends",true)
    private val shortcut      = bool ("Shortcut",      false)

    enum class BlockChoice(val id: String) {
        OBSIDIAN("minecraft:obsidian"),
        COBBLESTONE("minecraft:cobblestone")
    }

    /**
     * Trapper mode: which 2-block-tall column to leave UNSEALED so the hole
     * has exactly one opening. Pair with AnchorAura — it will place the anchor
     * in that opening (nearest free spot to the target), charge it, detonate,
     * repeat, straight in the camper's face while the rest of the hole holds.
     */
    enum class OpeningMode {
        NONE,       // full sealed box (classic trap)
        TOWARD_ME,  // leave the column facing you open
        NORTH, EAST, SOUTH, WEST
    }

    private var lastPlaceMs = 0L
    private var targetQueue = ArrayDeque<Triple<Vector3i,String,Int>>()

    override fun onEnable() {
        super.onEnable()
        targetQueue.clear()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        targetQueue.clear()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return

        val session = event.session
        val target = findTarget() ?: return
        val chosenId = blockId.value.id

        if (targetQueue.isEmpty()) {
            buildQueue(target)
        }
        if (targetQueue.isEmpty()) return

        val prepared = PlacementUtil.prepareItemForUse(session, chosenId) ?: return
        val (pos, supportId, face) = targetQueue.removeFirst()

        val placed = PlacementUtil.sendPlacementUseRaw(session, prepared, pos, supportId, face)
        if (placed) lastPlaceMs = now
        PlacementUtil.revert(session, prepared)
    }

    /** Horizontal (dx, dz) of the opening column, or null for full seal. */
    private fun openingDirection(target: EntityTracker.TrackedEntity): Pair<Int, Int>? {
        return when (opening.value) {
            OpeningMode.NONE  -> null
            OpeningMode.NORTH -> 0 to -1
            OpeningMode.SOUTH -> 0 to 1
            OpeningMode.EAST  -> 1 to 0
            OpeningMode.WEST  -> -1 to 0
            OpeningMode.TOWARD_ME -> {
                // Axis-aligned side from the target's cell toward you, so the
                // hole opens in your face — that's where the anchors go in.
                val dx = EntityTracker.selfX - floor(target.x)
                val dz = EntityTracker.selfZ - floor(target.z)
                if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
                    (if (dx >= 0f) 1 else -1) to 0
                } else {
                    0 to (if (dz >= 0f) 1 else -1)
                }
            }
        }
    }

    private fun buildQueue(target: EntityTracker.TrackedEntity) {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val offsets = if (includeCeiling.value) TRAP_OFFSETS else TRAP_OFFSETS.filter { it.second < 2 }
        val openDir = openingDirection(target)

        for ((dx, dy, dz) in offsets) {
            // Skip the opening column (the 2-tall door, feet + head height).
            // Ceiling above the opening still gets sealed.
            if (openDir != null && dx == openDir.first && dz == openDir.second && dy < 2) continue

            val bx = tx + dx; val by = ty + dy; val bz = tz + dz
            val current = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (current !in AIR_IDS) continue
            val neighbor = PlacementUtil.findClickableNeighbor(bx, by, bz) ?: continue
            targetQueue.addLast(neighbor)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        return EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isPlayer &&
                    !(ignoreFriends.value && e.isFriendEntity)
        }.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }
}
