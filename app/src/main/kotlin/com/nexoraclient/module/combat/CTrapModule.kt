package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class CTrapModule : BaseModule(
    name        = "CTrap",
    category    = ModuleCategory.COMBAT,
    description = "4 köşeye obsidian koyar, sonra köşelere kristal yerleştirip patlatarak düşmana hasar verir"
) {

    companion object {
        private val AIR_IDS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

        private val CORNER_OFFSETS_L0 = listOf(
            Triple(1, 0, 1), Triple(1, 0, -1),
            Triple(-1, 0, 1), Triple(-1, 0, -1)
        )
        private val CORNER_OFFSETS_L1 = listOf(
            Triple(1, 1, 1), Triple(1, 1, -1),
            Triple(-1, 1, 1), Triple(-1, 1, -1)
        )

        private const val PICKAXES = "netherite_pickaxe|diamond_pickaxe|iron_pickaxe|stone_pickaxe|golden_pickaxe|wooden_pickaxe"
    }

    private val range          = float("Range",        6f,   2f,  12f)
    private val placeDelay     = int  ("Place Delay",  60,   20,  300)
    private val layers         = int  ("Layers",       2,    1,   2)
    private val crystalPhase   = bool ("Crystal Phase", true)
    private val explodeInstant = bool ("Instant Explode", true)
    private val ignoreFriends  = bool ("Ignore Friends", true)
    private val shortcut       = bool ("Shortcut",     false)

    private enum class Phase { OBSIDIAN, CRYSTAL }
    private var phase = Phase.OBSIDIAN
    private var buildQueue = ArrayDeque<Triple<Vector3i, String, Int>>()
    private var placedCorners = mutableListOf<Vector3i>()
    private var lastPlaceMs = 0L
    private val pendingCrystalPos = ConcurrentHashMap.newKeySet<Long>()

    override fun onEnable() {
        super.onEnable()
        reset()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        reset()
        super.onDisable()
    }

    private fun reset() {
        phase = Phase.OBSIDIAN
        buildQueue.clear()
        placedCorners.clear()
        pendingCrystalPos.clear()
        lastPlaceMs = 0L
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        if (event.isServerToClient && explodeInstant.value && crystalPhase.value) {
            val pkt = event.packet as? AddEntityPacket ?: return
            if (pkt.identifier != "minecraft:ender_crystal") return
            val key = posKey(
                floor(pkt.position.x).toInt(),
                floor(pkt.position.y).toInt(),
                floor(pkt.position.z).toInt()
            )
            if (pendingCrystalPos.remove(key)) {
                val session = event.session
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, pkt.uniqueEntityId)
            }
            return
        }

        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return

        val session = event.session

        when (phase) {
            Phase.OBSIDIAN -> handleObsidianPhase(session, now)
            Phase.CRYSTAL  -> handleCrystalPhase(session, now)
        }
    }

    private fun handleObsidianPhase(session: RubidiumRelaySession, now: Long) {
        if (buildQueue.isEmpty()) rebuildObsidianQueue()
        if (buildQueue.isEmpty()) {
            if (crystalPhase.value && placedCorners.isNotEmpty()) {
                phase = Phase.CRYSTAL
            }
            return
        }

        val prepared = PlacementUtil.prepareItemForUse(session, "minecraft:obsidian") ?: return
        val (pos, supportId, face) = buildQueue.removeFirst()
        val placed = PlacementUtil.sendPlacementUseRaw(session, prepared, pos, supportId, face)
        if (placed) {
            lastPlaceMs = now
            placedCorners.add(pos)
        }
        PlacementUtil.revert(session, prepared)
    }

    private fun handleCrystalPhase(session: RubidiumRelaySession, now: Long) {
        val cornerObsidianPositions = placedCorners.filter { pos ->
            val above = WorldBlockTracker.getBlockIdentifier(pos.x, pos.y + 1, pos.z)
            above == null || above in AIR_IDS
        }

        if (cornerObsidianPositions.isEmpty()) {
            phase = Phase.OBSIDIAN
            placedCorners.clear()
            return
        }

        val prepared = PlacementUtil.prepareItemForUse(session, "minecraft:end_crystal") ?: return
        val target = cornerObsidianPositions.first()
        val crystalPos = Vector3i.from(target.x, target.y + 1, target.z)
        val neighbor = PlacementUtil.findClickableNeighbor(crystalPos.x, crystalPos.y, crystalPos.z)
            ?: Triple(target, "minecraft:obsidian", 1)

        if (explodeInstant.value) {
            pendingCrystalPos.add(posKey(crystalPos.x, crystalPos.y, crystalPos.z))
        }
        val placed = PlacementUtil.sendPlacementUseRaw(session, prepared, neighbor.first, neighbor.second, neighbor.third)
        if (placed) {
            lastPlaceMs = now
            placedCorners.remove(target)
        }
        PlacementUtil.revert(session, prepared)
    }

    private fun rebuildObsidianQueue() {
        val sx = floor(EntityTracker.selfX).toInt()
        val sy = floor(EntityTracker.selfY).toInt()
        val sz = floor(EntityTracker.selfZ).toInt()

        val offsets = if (layers.value >= 2)
            CORNER_OFFSETS_L0 + CORNER_OFFSETS_L1
        else
            CORNER_OFFSETS_L0

        for ((dx, dy, dz) in offsets) {
            val bx = sx + dx; val by = sy + dy; val bz = sz + dz
            val current = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (current !in AIR_IDS) continue
            val neighbor = PlacementUtil.findClickableNeighbor(bx, by, bz) ?: continue
            buildQueue.addLast(neighbor)
        }
    }

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
