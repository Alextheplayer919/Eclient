package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3i
import kotlin.math.floor
import kotlin.math.sqrt

class Scaffold : BaseModule(
    name        = "Scaffold",
    category    = ModuleCategory.MOVEMENT,
    description = "God Bridge/Telly - sprint sırasında hareket yönüne otomatik köprü kurar"
) {

    private companion object {
        val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }

    private val sprintOnly      = bool ("Sprint Only",        true)
    private val predictDistance = float("Predict Distance",   1.1f, 0.5f, 3f)
    private val placeDelayMs    = int  ("Place Delay (ms)",   90,   30,   500)
    private val tickMs          = int  ("Tick Speed (ms)",    25,   10,   100)
    private val shortcut        = bool ("Shortcut",            false)

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastX = 0f
    @Volatile private var lastZ = 0f
    @Volatile private var dirX  = 0f
    @Volatile private var dirZ  = 0f
    @Volatile private var initialized = false

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastPlaceMs = 0L
        lastX = EntityTracker.selfX
        lastZ = EntityTracker.selfZ
        dirX = 0f; dirZ = 0f
        initialized = false
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(tickMs.value.toLong())
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelayMs.value) return
        if (sprintOnly.value && !EntityTracker.selfSprinting) return

        val curX = EntityTracker.selfX
        val curZ = EntityTracker.selfZ
        val dx = curX - lastX
        val dz = curZ - lastZ
        lastX = curX; lastZ = curZ

        val distSq = dx * dx + dz * dz
        if (!initialized) { initialized = true; return }
        if (distSq > 0.0009f) { dirX = dx; dirZ = dz }

        val len = sqrt(dirX * dirX + dirZ * dirZ)
        if (len < 0.0001f) return
        val nx = dirX / len
        val nz = dirZ / len

        val targetX = floor(curX + nx * predictDistance.value).toInt()
        val targetZ = floor(curZ + nz * predictDistance.value).toInt()
        val targetY = floor(EntityTracker.selfY).toInt() - 1

        val existing = WorldBlockTracker.getBlockIdentifier(targetX, targetY, targetZ)
        if (existing != null && existing !in NON_SOLID) return

        val refX = floor(curX).toInt()
        val refZ = floor(curZ).toInt()
        if (refX == targetX && refZ == targetZ) return

        val refId = WorldBlockTracker.getBlockIdentifier(refX, targetY, refZ) ?: return
        if (refId in NON_SOLID) return

        val ddx = targetX - refX
        val ddz = targetZ - refZ
        val face = when {
            ddx > 0 -> 5
            ddx < 0 -> 4
            ddz > 0 -> 3
            ddz < 0 -> 2
            else -> return
        }

        val blockIdentifier = findScaffoldBlock() ?: return
        val prepared = PlacementUtil.prepareItemForUse(session, blockIdentifier, noSwitch = true) ?: return

        val ok = PlacementUtil.sendPlacementUseRaw(
            session   = session,
            prepared  = prepared,
            blockPos  = Vector3i.from(refX, targetY, refZ),
            blockId   = refId,
            blockFace = face
        )
        PlacementUtil.revert(session, prepared)

        if (ok) lastPlaceMs = now
    }

    private fun findScaffoldBlock(): String? {
        val held = EntityTracker.getHeldItem() ?: return null
        if (held.count <= 0) return null
        val id = InventoryUtil.resolveIdentifier(held) ?: return null
        if (id == "minecraft:air") return null
        return id
    }
}
