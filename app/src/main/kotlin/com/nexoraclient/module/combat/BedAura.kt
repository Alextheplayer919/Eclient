
package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import kotlin.math.floor

// NOT: Yatak patlaması sadece Nether (dimension 1) ve End'de (dimension 2)
// çalışır — Overworld'de (dimension 0) sadece normal yatak yerleştirir,
// patlamaz. selfDimension EntityTracker'dan okunuyor.
class BedAura : BaseModule(
    name        = "BedAura",
    category    = ModuleCategory.COMBAT,
    description = "Hedefin yanına yatak koyup patlatır (Nether/End)"
) {
    companion object {
        private const val TICK_MS           = 150L
        private const val ACTIVATE_DELAY_MS = 200L
    }

    private val targetRange  = int("Target Range", 8, 2, 16)
    private val friendSkip   = bool("Friend Skip", true)
    private val placeRange   = int("Place Range", 6, 2, 12)
    private val bedIdentifier = string("Bed", "minecraft:red_bed")
    private val noSwitch      = bool("No Switch", true)
    private val cooldownMs    = int("Cooldown (ms)", 1500, 200, 5000)
    private val requireNetherOrEnd = bool("Require Nether/End", true)
    private val shortcut = bool("Shortcut", false)

    private val NON_SOLID = setOf(
        "minecraft:air", "minecraft:water", "minecraft:flowing_water",
        "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:void_air", "minecraft:cave_air"
    )

    private data class Attempt(val bedPos: Vector3i, val armedAt: Long)

    @Volatile private var active: Attempt? = null
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    override fun onEnable() {
        super.onEnable()
        active = null
        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
        active = null
    }

    private fun tick() {
        if (requireNetherOrEnd.value && EntityTracker.selfDimension == 0) return
        val session = PacketEventBus.currentSession ?: return

        active?.let { a ->
            if (System.currentTimeMillis() - a.armedAt >= ACTIVATE_DELAY_MS) {
                PlacementUtil.sendInteract(session, a.bedPos, bedIdentifier.value)
                active = null
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < cooldownMs.value) return

        val target = nearestEnemy() ?: return
        attemptPlace(session, target)
    }

    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        if (EntityTracker.selfRuntimeId <= 0L) return null
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun attemptPlace(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        val hasData = WorldBlockTracker.hasAnyTerrainData()
        val bedPos = Vector3i.from(tx, ty, tz)
        val spotFree = !hasData || (WorldBlockTracker.getBlockIdentifier(bedPos.x, bedPos.y, bedPos.z) ?: "minecraft:air") in NON_SOLID
        if (!spotFree) return

        val below = WorldBlockTracker.getBlockIdentifier(bedPos.x, bedPos.y - 1, bedPos.z)
        if (hasData && (below == null || below in NON_SOLID)) return

        val cx = bedPos.x + 0.5f; val cy = bedPos.y + 0.5f; val cz = bedPos.z + 0.5f
        if (MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ) > placeRange.value) return

        lastAttemptMs = System.currentTimeMillis()

        val prepared = PlacementUtil.prepareItemForUse(session, bedIdentifier.value, noSwitch.value) ?: return
        val below2 = Vector3i.from(bedPos.x, bedPos.y - 1, bedPos.z)
        val ok = PlacementUtil.sendPlacementUseRaw(session, prepared, below2, below ?: "minecraft:obsidian", blockFace = 1)
        PlacementUtil.revert(session, prepared)
        if (!ok) return

        active = Attempt(bedPos, System.currentTimeMillis())
    }
}
