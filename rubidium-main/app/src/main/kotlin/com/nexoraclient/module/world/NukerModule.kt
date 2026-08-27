package com.rubidiumclient.module.world

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

class NukerModule : BaseModule(
    name        = "Nuker",
    category    = ModuleCategory.WORLD,
    description = "Etraftaki blokları otomatik kırar (sadece anti-cheat'siz sunucular)"
) {

    companion object {
        private val UNBREAKABLE = setOf(
            "minecraft:bedrock", "minecraft:air", "minecraft:void_air",
            "minecraft:cave_air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava"
        )
    }

    private val range       = float("Range",     3.5f, 1f, 6f)
    private val breakDelay  = int  ("Break Delay", 50, 10, 500)
    private val onlyExposed = bool ("Only Exposed", false)
    private val whitelist   = bool ("Whitelist Mode", false)
    private val shortcut    = bool ("Shortcut",   false)

    private var tickJob: Job? = null
    private val lastBreakMs = java.util.concurrent.atomic.AtomicLong(0L)

    override fun onEnable() {
        super.onEnable()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val now = System.currentTimeMillis()
                if (now - lastBreakMs.get() >= breakDelay.value) {
                    val session = PacketEventBus.currentSession
                    if (session != null) {
                        findTarget()?.let { (pos, id) ->
                            lastBreakMs.set(now)
                            breakBlock(session, pos, id)
                        }
                    }
                }
            }
            delay(10L)
        }
    }

    private fun breakBlock(session: com.rubidiumclient.core.relay.RubidiumRelaySession, pos: Vector3i, blockId: String) {
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val startPacket = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action = PlayerActionType.START_BREAK
            blockPosition = pos
            resultPosition = pos
            face = 1
        }
        val stopPacket = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action = PlayerActionType.ABORT_BREAK
            blockPosition = pos
            resultPosition = pos
            face = 1
        }
        val predictPacket = PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action = PlayerActionType.BLOCK_PREDICT_DESTROY
            blockPosition = pos
            resultPosition = pos
            face = 1
        }
        session.serverBound(startPacket)
        session.serverBound(predictPacket)
        session.serverBound(stopPacket)
    }

    private fun findTarget(): Pair<Vector3i, String>? {
        val sx = floor(EntityTracker.selfX).toInt()
        val sy = floor(EntityTracker.selfY).toInt()
        val sz = floor(EntityTracker.selfZ).toInt()
        val r = floor(range.value).toInt()
        var best: Pair<Vector3i, String>? = null
        var bestDist = Float.MAX_VALUE

        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val bx = sx + dx; val by = sy + dy; val bz = sz + dz
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id in UNBREAKABLE) continue
            val d = MathUtil.dist3sq(bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (d > range.value * range.value) continue
            if (d < bestDist) { bestDist = d; best = Vector3i.from(bx, by, bz) to id }
        }
        return best
    }
}
