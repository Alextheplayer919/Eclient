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

// NOT (doğrulanmadı): Piston'un hangi yöne baktığı (facing) burada manuel
// belirlenmiyor — CrystalAura'daki obsidian/crystal ITEM_USE deseniyle aynı
// mantık: sunucuya sadece "şu yüzeye, şu tıklama pozisyonunda item kullanıldı"
// bilgisi gönderiliyor, facing'i sunucu (oyuncunun bakış açısına göre) kendi
// belirliyor — tıpkı gerçek oyuncunun piston koyması gibi. Bu yüzden piston
// koymadan hemen önce hedefe dönük olmak (faceTarget) kritik. Bazı sunucu
// implementasyonlarında bu davranış farklı olabilir, ilk testte piston'un
// gerçekten hedefe doğru baktığını gözle doğrula.
class PistonAura : BaseModule(
    name        = "PistonAura",
    category    = ModuleCategory.COMBAT,
    description = "Hedefin yanına piston + lever koyup iterek uzaklaştırır"
) {
    companion object {
        private const val TICK_MS          = 150L
        private const val ACTIVATE_DELAY_MS = 250L
        private const val PENDING_RETRY_MS = 500L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }

    private val targetRange   = int("Target Range", 8, 2, 16)
    private val friendSkip    = bool("Friend Skip", true)
    private val placeRange    = int("Place Range", 6, 2, 12)
    private val useSticky     = bool("Sticky Piston", false)
    private val noSwitch      = bool("No Switch", true)
    private val cooldownMs    = int("Cooldown (ms)", 1500, 200, 5000)
    private val shortcut      = bool("Shortcut", false)

    private data class Attempt(val pistonPos: Vector3i, val leverPos: Vector3i, val armedAt: Long, var activated: Boolean)

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

    private fun pistonId(): String = if (useSticky.value) "minecraft:sticky_piston" else "minecraft:piston"

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return

        active?.let { a ->
            if (!a.activated && System.currentTimeMillis() - a.armedAt >= ACTIVATE_DELAY_MS) {
                if (PlacementUtil.sendInteract(session, a.leverPos, "minecraft:lever")) {
                    a.activated = true
                }
            } else if (a.activated && System.currentTimeMillis() - a.armedAt >= ACTIVATE_DELAY_MS + cooldownMs.value) {
                active = null
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < cooldownMs.value) return

        val target = nearestEnemy() ?: return
        attemptTrap(session, target)
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

    // Piston'u hedefin yanına, kendisine bakacak şekilde koy: (hedef - self)
    // yönünün tersinde bir hücre seç ki piston'un yüzü hedefe baksın.
    private fun attemptTrap(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        val dx = tx - floor(EntityTracker.selfX).toInt()
        val dz = tz - floor(EntityTracker.selfZ).toInt()
        val stepX = if (dx == 0) 0 else if (dx > 0) 1 else -1
        val stepZ = if (dz == 0) 0 else if (dz > 0) 1 else -1
        if (stepX == 0 && stepZ == 0) return

        val pistonPos = if (kotlin.math.abs(dx) >= kotlin.math.abs(dz))
            Vector3i.from(tx + stepX, ty, tz)
        else
            Vector3i.from(tx, ty, tz + stepZ)

        val leverPos = Vector3i.from(pistonPos.x, pistonPos.y + 1, pistonPos.z)

        val hasData = WorldBlockTracker.hasAnyTerrainData()
        val pistonSpotFree = !hasData || (WorldBlockTracker.getBlockIdentifier(pistonPos.x, pistonPos.y, pistonPos.z) ?: "minecraft:air") in NON_SOLID
        if (!pistonSpotFree) return

        val cx = pistonPos.x + 0.5f; val cy = pistonPos.y + 0.5f; val cz = pistonPos.z + 0.5f
        if (MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ) > placeRange.value) return

        lastAttemptMs = System.currentTimeMillis()

        val pistonRef = PlacementUtil.findClickableNeighbor(pistonPos.x, pistonPos.y, pistonPos.z) ?: return
        val pistonItem = PlacementUtil.prepareItemForUse(session, pistonId(), noSwitch.value) ?: return
        val pistonOk = PlacementUtil.sendPlacementUseRaw(session, pistonItem, pistonRef.first, pistonRef.second, blockFace = pistonRef.third)
        PlacementUtil.revert(session, pistonItem)
        if (!pistonOk) return

        val leverItem = PlacementUtil.prepareItemForUse(session, "minecraft:lever", noSwitch.value) ?: return
        val leverOk = PlacementUtil.sendPlacementUseRaw(session, leverItem, Vector3i.from(pistonPos.x, pistonPos.y, pistonPos.z), pistonId(), blockFace = 1)
        PlacementUtil.revert(session, leverItem)
        if (!leverOk) return

        active = Attempt(pistonPos, leverPos, System.currentTimeMillis(), activated = false)
    }
}
