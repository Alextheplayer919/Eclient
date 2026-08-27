package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

class ObsidianMinerModule : BaseModule(
    name        = "ObsidianMiner",
    category    = ModuleCategory.COMBAT,
    description = "Hotbardaki kazmayla rakibin yanındaki obsidiani otomatik kazar"
) {

    companion object {
        private val PICKAXE_IDS = listOf(
            "minecraft:netherite_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:wooden_pickaxe"
        )

        private val MINE_OFFSETS = listOf(
            Triple(0, 0, 1), Triple(0, 0, -1),
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 1), Triple(0, 1, -1),
            Triple(1, 1, 0), Triple(-1, 1, 0),
            Triple(0, -1, 0)
        )
    }

    private val range         = float("Range",          6f,  2f, 12f)
    private val breakDelay    = int  ("Break Delay",    80,  30, 500)
    private val onlyHotbar    = bool ("Hotbar Only",    true)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val shortcut      = bool ("Shortcut",       false)

    private var lastBreakMs = 0L
    private var lastPickaxeSlot = -1

    override fun onEnable() {
        super.onEnable()
        lastBreakMs = 0L
        lastPickaxeSlot = -1
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        restoreSlot()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return

        val session = event.session
        val target = findTarget() ?: return
        val obsPos = findObsidianNear(target) ?: return
        val pickSlot = findPickaxeSlot() ?: return

        switchToPickaxe(session, pickSlot)
        sendBreak(session, obsPos)
        lastBreakMs = now
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        return EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isPlayer &&
                    !(ignoreFriends.value && e.isFriendEntity)
        }.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun findObsidianNear(target: EntityTracker.TrackedEntity): Vector3i? {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        var best: Vector3i? = null
        var bestDist = Float.MAX_VALUE

        for ((dx, dy, dz) in MINE_OFFSETS) {
            val bx = tx + dx; val by = ty + dy; val bz = tz + dz
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id != "minecraft:obsidian") continue
            val d = MathUtil.dist3sq(
                bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
            )
            if (d < bestDist) { bestDist = d; best = Vector3i.from(bx, by, bz) }
        }
        return best
    }

    private fun findPickaxeSlot(): Int? {
        val range = if (onlyHotbar.value)
            InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END
        else
            InventoryUtil.HOTBAR_START..InventoryUtil.INV_END

        for (slot in range) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
            if (PICKAXE_IDS.any { id.contains(it.removePrefix("minecraft:")) }) return slot
        }
        return null
    }

    private fun switchToPickaxe(session: RubidiumRelaySession, slot: Int) {
        if (slot == EntityTracker.selfHotbarSlot) return
        if (lastPickaxeSlot == -1) lastPickaxeSlot = EntityTracker.selfHotbarSlot
        InventoryUtil.sendHotbarSelect(session, slot)
        EntityTracker.selfHotbarSlot = slot
    }

    private fun restoreSlot() {
        if (lastPickaxeSlot == -1) return
        val session = PacketEventBus.currentSession ?: return
        InventoryUtil.sendHotbarSelect(session, lastPickaxeSlot)
        EntityTracker.selfHotbarSlot = lastPickaxeSlot
        lastPickaxeSlot = -1
    }

    private fun sendBreak(session: RubidiumRelaySession, pos: Vector3i) {
        val startPkt = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.START_BREAK
            blockPosition   = pos
            resultPosition  = pos
            face            = 1
        }
        val predictPkt = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.BLOCK_PREDICT_DESTROY
            blockPosition   = pos
            resultPosition  = pos
            face            = 1
        }
        val stopPkt = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.ABORT_BREAK
            blockPosition   = pos
            resultPosition  = pos
            face            = 1
        }
        session.serverBound(startPkt)
        session.serverBound(predictPkt)
        session.serverBound(stopPkt)
    }
}
