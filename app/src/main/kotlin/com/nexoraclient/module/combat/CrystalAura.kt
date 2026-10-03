package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Places server-verified crystal bases and attacks using explosion-damage scoring"
) {

    companion object {
        private const val EXPLOSION_SIZE   = 6f
        private const val TICK_INTERVAL_MS = 10L
        private const val CRYSTAL_ID       = "minecraft:end_crystal"
        private const val OBSIDIAN_ID      = "minecraft:obsidian"
        private const val BEDROCK_ID       = "minecraft:bedrock"
        private const val LOG_FAIL_INTERVAL_MS  = 1000L
        private const val CHAT_FAIL_INTERVAL_MS = 10000L
        private const val PENDING_TIMEOUT_MS = 450L
        private const val OBSIDIAN_ACK_TIMEOUT_MS = 1000L
        private const val OBSIDIAN_RETRY_DELAY_MS = 500L
        private const val OBSIDIAN_SEARCH_RADIUS = 2
        private const val PENDING_MATCH_RADIUS = 1.5f
        private const val PREDICT_HORIZON  = 32L
        private const val ROTATION_SETTLE_MS = 20L
        private const val PLAYER_HALF_WIDTH = 0.3f
        private const val PLAYER_HEIGHT = 1.8f

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
        private val AIR_BLOCKS = setOf(
            "minecraft:air", "minecraft:void_air", "minecraft:cave_air"
        )
        private val ADJACENT_OFFSETS = listOf(
            0 to 1, 0 to -1, 1 to 0, -1 to 0,
            1 to 1, 1 to -1, -1 to 1, -1 to -1
        )
    }

    private val range           = float("Range",           5f,   3f,  10f)
    private val suicide         = bool ("Suicide",         false)
    private val whileEating     = int  ("WhileEating",     0,    0,   3)

    private val place           = bool ("Place",           true)
    private val placeDelayMs    = int  ("PlaceDelay",      20,   0,   500)
    private val wasteAmount     = int  ("WasteAmount",     1,    1,   5)
    private val autoObsidian    = bool ("Auto Obsidian",   true)
    private val footPriority   = bool ("Foot Priority",   true)
    private val silentRotation = bool ("Silent Rotation", true)

    private val explode         = bool ("Explode",         true)
    private val explodeDelayMs  = int  ("ExplodeDelay",    10,   0,   300)
    private val idPredict       = bool ("IDPredict",       true)
    private val idPackets       = int  ("IDPackets",       3,    1,   15)
    private val blacklistMs     = int  ("BlacklistMs",     500,  0,   2000)

    // Damage gates: avoid negligible hits and cap self-damage per crystal.
    private val minPlaceDmg     = float("Min Place Damage", 4f,   0f,  20f)
    private val maxSelfDmg      = float("Max Self Damage",  8f,   0f,  20f)
    private val minBreakDmg     = float("Min Break Damage", 1f,   0f,  20f)

    private val removeParticles = bool ("RemoveParticles", true)
    private val log             = bool ("Log",             false)
    private val verboseLog      = bool ("VerboseLog",      false)

    @Volatile private var lastExplodeMs = 0L
    @Volatile private var lastPlaceMs   = 0L
    @Volatile private var lastFailLogMs = 0L
    @Volatile private var lastChatFailMs = 0L
    @Volatile private var highestCrystalId = 0L

    private var tickJob: Job? = null

    @Volatile private var lockedBase: Triple<Int, Int, Int>? = null
    @Volatile private var lockedBaseTargetId: Long? = null
    @Volatile private var lockedTargetId: Long? = null
    @Volatile private var lockedCrystalId: Long? = null
    @Volatile private var pendingObsidian: PendingObsidian? = null
    @Volatile private var rotationGate: RotationGate? = null
    /** Key for an obsidian request that is still awaiting an authoritative block update. */
    @Volatile private var autoBaseAttemptKey: String? = null
    @Volatile private var autoBaseConfirmedKey: String? = null
    @Volatile private var autoBaseConfirmedBase: Triple<Int, Int, Int>? = null
    /** Failed/temporarily unavailable target-cell retry throttle; never a permanent lockout. */
    @Volatile private var autoBaseRetryKey: String? = null
    @Volatile private var autoBaseRetryAtMs = 0L

    private val crystalBlacklist = ConcurrentHashMap<Long, Long>()

    private data class PendingPlace(
        val x: Float, val y: Float, val z: Float,
        val sentAt: Long,
        val blockId: String,
        val itemNetId: Int,
        val hotbarSlot: Int
    )
    private val pendingPlaces = CopyOnWriteArrayList<PendingPlace>()

    private data class PendingObsidian(
        val targetId: Long,
        val targetCell: Triple<Int, Int, Int>,
        val base: Triple<Int, Int, Int>,
        val sentAt: Long
    )

    private data class ObsidianSpot(
        val targetId: Long,
        val targetCell: Triple<Int, Int, Int>,
        val base: Triple<Int, Int, Int>,
        val supportPos: Vector3i,
        val supportId: String,
        val face: Int,
        val clickPosition: Vector3f
    )

    private data class RotationGate(
        val key: String,
        @Volatile var yaw: Float,
        @Volatile var pitch: Float,
        val lastAppliedAtMs: AtomicLong = AtomicLong(0L)
    )

    private enum class PendingBaseStatus { NONE, WAITING, CONFIRMED, REJECTED }

    private fun autoBaseKey(targetId: Long, targetCell: Triple<Int, Int, Int>): String =
        "$targetId:${targetCell.first},${targetCell.second},${targetCell.third}"

    private fun resetAutoBaseState() {
        autoBaseAttemptKey = null
        autoBaseConfirmedKey = null
        autoBaseConfirmedBase = null
        autoBaseRetryKey = null
        autoBaseRetryAtMs = 0L
    }

    private fun clearAutoBaseAttempt(targetId: Long, targetCell: Triple<Int, Int, Int>) {
        val key = autoBaseKey(targetId, targetCell)
        if (autoBaseAttemptKey == key) autoBaseAttemptKey = null
        if (autoBaseRetryKey == key) {
            autoBaseRetryKey = null
            autoBaseRetryAtMs = 0L
        }
    }

    private fun deferAutoBaseRetry(targetId: Long, targetCell: Triple<Int, Int, Int>, now: Long) {
        autoBaseAttemptKey = null
        autoBaseRetryKey = autoBaseKey(targetId, targetCell)
        autoBaseRetryAtMs = now + OBSIDIAN_RETRY_DELAY_MS
    }

    private data class ExplosionResult(val mostDamage: Float, val selfDamage: Float)

    // PacketEventBus normally pauses combat listeners while eating. CrystalAura
    // has its own WhileEating policy, and silent placement still needs to see
    // outgoing movement packets when that policy explicitly allows placement.
    override val pauseWhileEating: Boolean get() = false
    override val priority: Int get() = 200

    override fun onEnable() {
        super.onEnable()
        lastExplodeMs = 0L
        lastPlaceMs   = 0L
        highestCrystalId = 0L
        lockedBase = null
        lockedBaseTargetId = null
        lockedTargetId = null
        lockedCrystalId = null
        pendingObsidian = null
        rotationGate = null
        resetAutoBaseState()
        crystalBlacklist.clear()
        pendingPlaces.clear()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        PacketEventBus.unregister(this)
        pendingPlaces.clear()
        crystalBlacklist.clear()
        pendingObsidian = null
        rotationGate = null
        resetAutoBaseState()
        lockedBase = null
        lockedBaseTargetId = null
        lockedTargetId = null
        lockedCrystalId = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        if (event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
            when (val pkt = event.packet) {
                is PlayerAuthInputPacket -> applySilentRotation(event, pkt)
                is MovePlayerPacket -> applySilentRotation(event, pkt)
                else -> {}
            }
            return
        }

        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (!pkt.identifier.contains("crystal", ignoreCase = true)) return
                if (pkt.runtimeEntityId > highestCrystalId) highestCrystalId = pkt.runtimeEntityId

                val now = System.currentTimeMillis()
                val cx = pkt.position.x; val cy = pkt.position.y; val cz = pkt.position.z

                val matched = pendingPlaces.filter {
                    MathUtil.dist3(it.x, it.y, it.z, cx, cy, cz) <= PENDING_MATCH_RADIUS
                }
                if (matched.isNotEmpty()) pendingPlaces.removeAll(matched)

                val eating = EntityTracker.selfUsingItem
                val allowAttack = !eating || whileEating.value == 1 || whileEating.value == 3
                // Keep instant unrotated breaking only in non-silent mode. With
                // silent rotations enabled, the tick path gates the break until
                // the server has received the aim movement packet.
                if (!idPredict.value && explode.value && allowAttack && !silentRotation.value) {
                    val distToSelf = MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, playerEyeY(), EntityTracker.selfZ)
                    if (now - lastExplodeMs < explodeDelayMs.value) return
                    if (distToSelf > range.value) return
                    if (crystalBlacklist.containsKey(pkt.runtimeEntityId)) return

                    val dmg = simulateExplosionDamage(cx, cy, cz)
                    val effectiveDamage = effectiveBreakDamage(dmg)
                    if (effectiveDamage != null && (dmg.mostDamage > 0f || suicide.value)) {
                        val session = PacketEventBus.currentSession ?: return
                        attackCrystal(session, pkt.runtimeEntityId)
                        lastExplodeMs = now
                        sendLog(session, "Patlatıldı (anlık) - ${effectiveDamage.toInt()} hasar")
                    }
                }
            }

            is LevelEventPacket -> {
                if (removeParticles.value && pkt.type == LevelEvent.PARTICLE_EXPLOSION) event.cancel()
            }

            else -> {}
        }
    }

    /**
     * Silent aim is applied only to outbound movement packets. The client view
     * is untouched; the next server-bound movement packet carries the aim, and
     * the placement or crystal attack is sent only after that rotation has had
     * time to reach the server.
     */
    private fun applySilentRotation(event: PacketEvent, packet: PlayerAuthInputPacket) {
        if (!silentRotation.value) return
        val gate = rotationGate ?: return
        packet.rotation = Vector3f.from(gate.pitch, gate.yaw, gate.yaw)
        gate.lastAppliedAtMs.set(System.currentTimeMillis())
        event.cancelAndReplace(packet)
    }

    private fun applySilentRotation(event: PacketEvent, packet: MovePlayerPacket) {
        if (!silentRotation.value) return
        val gate = rotationGate ?: return
        packet.rotation = Vector3f.from(gate.pitch, gate.yaw, gate.yaw)
        gate.lastAppliedAtMs.set(System.currentTimeMillis())
        event.cancelAndReplace(packet)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                try { tick() }
                catch (e: Exception) { DiagLog.log("CrystalAura", "tick exception: ${e.message}") }
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()
        pruneBlacklist(now)
        checkTimedOutPlacements(session, now)

        val eating = EntityTracker.selfUsingItem
        val allowPlace  = !eating || whileEating.value == 2 || whileEating.value == 3
        val allowAttack = !eating || whileEating.value == 1 || whileEating.value == 3

        val selectedTarget = pickTarget() ?: run {
            lockedTargetId = null
            lockedBase = null
            lockedBaseTargetId = null
            lockedCrystalId = null
            pendingObsidian = null
            rotationGate = null
            resetAutoBaseState()
            return
        }
        val target = currentServerTarget(selectedTarget.runtimeId) ?: run {
            rotationGate = null
            return
        }

        if (lockedTargetId != target.runtimeId) {
            lockedBase = null
            lockedBaseTargetId = null
            pendingObsidian = null
            rotationGate = null
            resetAutoBaseState()
        }
        lockedTargetId = target.runtimeId

        val pendingBaseStatus = advancePendingObsidian(target, now, session)

        val breakInProgress = if (explode.value && allowAttack && now - lastExplodeMs >= explodeDelayMs.value) {
            tryExplodeBest(session, now)
        } else {
            if (!explode.value || !allowAttack) {
                if (rotationGate?.key?.startsWith("break:") == true) rotationGate = null
                lockedCrystalId = null
            }
            false
        }
        // Break work owns the rotation gate until the attack is sent or the
        // selected crystal becomes invalid; placement can resume next tick.
        if (breakInProgress) return

        if (pendingBaseStatus == PendingBaseStatus.WAITING) return
        if (place.value && allowPlace && now - lastPlaceMs >= placeDelayMs.value) {
            tryPlace(session, now, target)
        } else if (!place.value || !allowPlace) {
            rotationGate = null
        }
    }

    /** EntityTracker positions are populated from server entity packets. Re-read
     * the runtime id before each action rather than trusting an old target
     * object captured before the rotation/placement wait. */
    private fun currentServerTarget(runtimeId: Long): EntityTracker.TrackedEntity? {
        if (runtimeId == EntityTracker.selfRuntimeId) return null
        val target = EntityTracker.getById(runtimeId) ?: return null
        if (!target.isPlayer) return null
        if (!target.x.isFinite() || !target.y.isFinite() || !target.z.isFinite()) return null
        val dist = MathUtil.dist3(
            target.x, target.y, target.z,
            EntityTracker.selfX, playerFeetY(), EntityTracker.selfZ
        )
        return target.takeIf { dist <= range.value }
    }

    private fun pickTarget(): EntityTracker.TrackedEntity? {
        val locked = lockedTargetId
        if (locked != null) currentServerTarget(locked)?.let { return it }

        return EntityTracker.getPlayers(Float.MAX_VALUE)
            .asSequence()
            .mapNotNull { currentServerTarget(it.runtimeId) }
            .minByOrNull {
                MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, playerFeetY(), EntityTracker.selfZ)
            }
    }

    /** Returns true while a break action owns the silent-rotation gate. */
    private fun tryExplodeBest(session: RubidiumRelaySession, now: Long): Boolean {
        val crystals = EntityTracker.getCrystals(range.value)
        val lockedId = lockedCrystalId
        val lockedCrystal = lockedId?.let { id -> crystals.firstOrNull { it.runtimeId == id } }
        if (lockedId != null && lockedCrystal == null) {
            lockedCrystalId = null
            if (rotationGate?.key == "break:$lockedId") rotationGate = null
        }

        var bestCrystal: EntityTracker.TrackedEntity? = lockedCrystal
        var bestDamage = -1f
        if (bestCrystal != null) {
            bestDamage = effectiveBreakDamage(simulateExplosionDamage(bestCrystal.x, bestCrystal.y, bestCrystal.z)) ?: -1f
        } else {
            for (crystal in crystals) {
                if (crystalBlacklist.containsKey(crystal.runtimeId)) continue
                val damage = effectiveBreakDamage(simulateExplosionDamage(crystal.x, crystal.y, crystal.z)) ?: continue
                if (damage > bestDamage ||
                    (damage == bestDamage && crystal.runtimeId < (bestCrystal?.runtimeId ?: Long.MAX_VALUE))
                ) {
                    bestDamage = damage
                    bestCrystal = crystal
                }
            }
        }

        val selected = bestCrystal
        if (selected == null || bestDamage < minBreakDmg.value) {
            lockedCrystalId = null
            if (rotationGate?.key?.startsWith("break:") == true) rotationGate = null
            return false
        }

        lockedCrystalId = selected.runtimeId
        val rotationKey = "break:${selected.runtimeId}"
        if (!awaitSilentRotation(rotationKey, selected.x, selected.y + 1f, selected.z, now)) return true

        // Re-read the crystal id and damage from server-tracked state after the
        // rotation wait, so a removed/moved crystal is not attacked from a stale
        // position.
        val liveCrystal = EntityTracker.getById(selected.runtimeId)
        if (liveCrystal == null || !liveCrystal.isCrystal ||
            distanceFromEye(liveCrystal.x, liveCrystal.y, liveCrystal.z) > range.value
        ) {
            lockedCrystalId = null
            rotationGate = null
            return false
        }
        val finalDamage = effectiveBreakDamage(
            simulateExplosionDamage(liveCrystal.x, liveCrystal.y, liveCrystal.z)
        )
        if (finalDamage == null || finalDamage < minBreakDmg.value) {
            lockedCrystalId = null
            rotationGate = null
            return false
        }

        attackCrystal(session, liveCrystal.runtimeId)
        lastExplodeMs = now
        lockedCrystalId = null
        rotationGate = null
        sendLog(session, "Patlatıldı - ${finalDamage.toInt()} hasar")
        return true
    }

    private fun pruneBlacklist(now: Long) {
        if (crystalBlacklist.isEmpty()) return
        val expired = crystalBlacklist.entries
            .filter { now - it.value > blacklistMs.value }
            .map { it.key }
        for (id in expired) crystalBlacklist.remove(id)
    }

    private fun checkTimedOutPlacements(session: RubidiumRelaySession, now: Long) {
        if (pendingPlaces.isEmpty()) return
        val timedOut = pendingPlaces.filter { now - it.sentAt > PENDING_TIMEOUT_MS }
        if (timedOut.isEmpty()) return
        pendingPlaces.removeAll(timedOut)
        for (p in timedOut) {
            logFail(session, "REJECTED (${p.x},${p.y},${p.z}) — no spawn in ${now - p.sentAt}ms")
        }
    }

    private fun advancePendingObsidian(
        selectedTarget: EntityTracker.TrackedEntity,
        now: Long,
        session: RubidiumRelaySession
    ): PendingBaseStatus {
        val pending = pendingObsidian ?: return PendingBaseStatus.NONE
        val target = currentServerTarget(pending.targetId)
        if (target == null || target.runtimeId != selectedTarget.runtimeId || blockCell(target) != pending.targetCell) {
            pendingObsidian = null
            if (lockedBaseTargetId == pending.targetId) {
                lockedBase = null
                lockedBaseTargetId = null
            }
            clearAutoBaseAttempt(pending.targetId, pending.targetCell)
            rotationGate = null
            logFail(session, "obsidian placement abandoned: target moved before server confirmation")
            return PendingBaseStatus.REJECTED
        }

        val (x, y, z) = pending.base
        if (WorldBlockTracker.hasData(x, y, z)) {
            val id = WorldBlockTracker.getBlockIdentifier(x, y, z)
            if (id == OBSIDIAN_ID) {
                pendingObsidian = null
                lockedBase = pending.base
                lockedBaseTargetId = pending.targetId
                clearAutoBaseAttempt(pending.targetId, pending.targetCell)
                autoBaseConfirmedKey = autoBaseKey(pending.targetId, pending.targetCell)
                autoBaseConfirmedBase = pending.base
                sendLog(session, "Server confirmed obsidian @ ${pending.base}")
                return PendingBaseStatus.CONFIRMED
            }
            if (id != null && id !in AIR_BLOCKS) {
                pendingObsidian = null
                deferAutoBaseRetry(pending.targetId, pending.targetCell, now)
                logFail(session, "obsidian destination changed before confirmation @ ${pending.base}: $id")
                return PendingBaseStatus.REJECTED
            }
        }

        if (now - pending.sentAt > OBSIDIAN_ACK_TIMEOUT_MS) {
            pendingObsidian = null
            deferAutoBaseRetry(pending.targetId, pending.targetCell, now)
            logFail(session, "obsidian placement not confirmed by server @ ${pending.base}; retrying")
            return PendingBaseStatus.REJECTED
        }
        return PendingBaseStatus.WAITING
    }

    private fun tryPlace(session: RubidiumRelaySession, now: Long, selectedTarget: EntityTracker.TrackedEntity) {
        val dbg: ((String) -> Unit)? = if (verboseLog.value) { msg -> DiagLog.log("CrystalAura", msg) } else null
        val target = currentServerTarget(selectedTarget.runtimeId) ?: run {
            rotationGate = null
            return
        }
        if (pendingObsidian != null) return

        val targetCell = blockCell(target)
        if (lockedBaseTargetId != null && lockedBaseTargetId != target.runtimeId) {
            lockedBase = null
            lockedBaseTargetId = null
        }

        // Foot-level obsidian adjacent to the server-tracked target is checked
        // first. Within that tier, the best safe damage result wins.
        val footBase = if (footPriority.value) findAdjacentFootBase(target, dbg) else null
        val locked = lockedBase?.takeIf {
            lockedBaseTargetId == target.runtimeId && isGoodBaseForTarget(it, target)
        }
        var base = footBase ?: locked ?: buildBestBase(target, dbg)

        if (base == null) {
            rotationGate = null
            if (autoObsidian.value) {
                tryPlaceObsidianBase(session, now, target, dbg)
            } else {
                logFail(session, "no usable obsidian/bedrock base near target | ${WorldBlockTracker.debugSummary()}")
            }
            return
        }

        // Re-run server block and damage checks even for a locked base: targets
        // and blocks can change while a silent rotation is being prepared.
        if (!isGoodBaseForTarget(base, target)) {
            lockedBase = null
            lockedBaseTargetId = null
            base = buildBestBase(target, dbg)
            if (base == null) {
                rotationGate = null
                if (autoObsidian.value) tryPlaceObsidianBase(session, now, target, dbg)
                return
            }
        }
        lockedBase = base
        lockedBaseTargetId = target.runtimeId

        val aimLocal = Vector3f.from(0.5f, 1.0f, 0.5f)
        val aimX = base.first + aimLocal.x
        val aimY = base.second + aimLocal.y
        val aimZ = base.third + aimLocal.z
        val rotationKey = "crystal:${target.runtimeId}:${base.first},${base.second},${base.third}"
        if (!awaitSilentRotation(rotationKey, aimX, aimY, aimZ, now)) return

        // The target, base block, clearance and damage are all rechecked after
        // the server has seen the silent rotation. Never place on ghost blocks.
        val finalTarget = currentServerTarget(target.runtimeId)
        if (finalTarget == null || blockCell(finalTarget) != targetCell || !isGoodBaseForTarget(base, finalTarget)) {
            rotationGate = null
            lockedBase = null
            lockedBaseTargetId = null
            return
        }

        val blockId = knownBlock(base.first, base.second, base.third)
        if (blockId == null || !isBaseBlock(blockId)) {
            rotationGate = null
            lockedBase = null
            lockedBaseTargetId = null
            return
        }

        val prepared = PlacementUtil.prepareItemForUse(session, CRYSTAL_ID, debugSink = dbg) ?: run {
            rotationGate = null
            logFail(session, "Envanterde kristal bulunamadı")
            lockedBase = null
            lockedBaseTargetId = null
            return
        }

        var placed = 0
        val blockPos = Vector3i.from(base.first, base.second, base.third)
        for (i in 0 until wasteAmount.value) {
            // Recheck the server-backed base immediately before every use. A
            // multi-place burst must not keep clicking a block that vanished.
            val liveTarget = currentServerTarget(target.runtimeId)
            if (liveTarget == null || blockCell(liveTarget) != targetCell ||
                !isGoodBaseForTarget(base, liveTarget) ||
                !isBaseBlock(knownBlock(base.first, base.second, base.third) ?: "")
            ) break

            val ok = PlacementUtil.sendPlacementUseRaw(
                session   = session,
                prepared  = prepared,
                blockPos  = blockPos,
                blockId   = blockId,
                blockFace = 1,
                clickPosition = aimLocal,
                debugSink = dbg
            )
            if (!ok) break
            placed++

            val sentAt = System.currentTimeMillis()
            pendingPlaces.add(PendingPlace(
                x = base.first + 0.5f, y = base.second + 1f, z = base.third + 0.5f,
                sentAt = sentAt,
                blockId = blockId,
                itemNetId = prepared.item.netId,
                hotbarSlot = prepared.slot
            ))

            if (idPredict.value) fireIdPredictions(session)
        }

        PlacementUtil.revert(session, prepared)
        rotationGate = null

        if (placed > 0) {
            lastPlaceMs = now
            sendLog(session, "Yerleştirme x$placed @ $base")
        } else {
            lockedBase = null
            lockedBaseTargetId = null
        }
    }

    private fun tryPlaceObsidianBase(
        session: RubidiumRelaySession,
        now: Long,
        target: EntityTracker.TrackedEntity,
        dbg: ((String) -> Unit)?
    ) {
        val targetCell = blockCell(target)
        val attemptKey = autoBaseKey(target.runtimeId, targetCell)

        if (autoBaseConfirmedKey == attemptKey) {
            val confirmedBase = autoBaseConfirmedBase
            val current = confirmedBase?.let { knownBlock(it.first, it.second, it.third) }
            if (current == null || isBaseBlock(current)) {
                // Keep the confirmed base; don't consume more obsidian just
                // because the damage gate currently rejects crystal use.
                dbg?.invoke("auto-obsidian: confirmed base still present/unknown; not placing a duplicate")
                return
            }
            autoBaseConfirmedKey = null
            autoBaseConfirmedBase = null
        } else if (autoBaseConfirmedKey != null) {
            autoBaseConfirmedKey = null
            autoBaseConfirmedBase = null
        }

        if (autoBaseAttemptKey == attemptKey) return
        if (autoBaseRetryKey == attemptKey && now < autoBaseRetryAtMs) return
        if (autoBaseRetryKey != attemptKey) {
            autoBaseRetryKey = null
            autoBaseRetryAtMs = 0L
        }

        val spot = findObsidianSpot(target, dbg) ?: run {
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            logFail(session, "no server-known, supported obsidian cell near target | ${WorldBlockTracker.debugSummary()}")
            return
        }
        if (PlacementUtil.findItemInInventory(OBSIDIAN_ID) == null) {
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            logFail(session, "obsidian is not available in inventory")
            return
        }

        val clickWorld = clickWorldPosition(spot.supportPos, spot.clickPosition)
        val rotationKey = "obsidian:${target.runtimeId}:${spot.base.first},${spot.base.second},${spot.base.third}"
        if (!awaitSilentRotation(rotationKey, clickWorld.first, clickWorld.second, clickWorld.third, now)) return

        val finalTarget = currentServerTarget(spot.targetId)
        if (finalTarget == null || blockCell(finalTarget) != spot.targetCell || !isObsidianSpotStillValid(spot, finalTarget)) {
            rotationGate = null
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            return
        }

        val prepared = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = OBSIDIAN_ID,
            debugSink = dbg
        ) ?: run {
            rotationGate = null
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            logFail(session, "could not prepare obsidian item")
            return
        }

        // Check the destination and clicked support again after inventory
        // preparation; both are based on the latest server block state.
        val liveTarget = currentServerTarget(spot.targetId)
        val stillValid = liveTarget != null && blockCell(liveTarget) == spot.targetCell &&
            isObsidianSpotStillValid(spot, liveTarget)
        if (!stillValid) {
            PlacementUtil.revert(session, prepared)
            rotationGate = null
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            return
        }

        val sent = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = prepared,
            blockPos = spot.supportPos,
            blockId = spot.supportId,
            blockFace = spot.face,
            clickPosition = spot.clickPosition,
            debugSink = dbg
        )
        PlacementUtil.revert(session, prepared)
        rotationGate = null

        if (!sent) {
            lastPlaceMs = now
            deferAutoBaseRetry(target.runtimeId, targetCell, now)
            logFail(session, "obsidian use transaction could not be sent")
            return
        }

        autoBaseAttemptKey = attemptKey
        autoBaseRetryKey = null
        autoBaseRetryAtMs = 0L
        pendingObsidian = PendingObsidian(
            targetId = spot.targetId,
            targetCell = spot.targetCell,
            base = spot.base,
            sentAt = System.currentTimeMillis()
        )
        lastPlaceMs = now
        sendLog(session, "obsidian requested @ ${spot.base}; waiting for server block update")
    }

    private fun findObsidianSpot(
        target: EntityTracker.TrackedEntity,
        dbg: ((String) -> Unit)?
    ): ObsidianSpot? {
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            dbg?.invoke("obsidian search skipped: no decoded sections or block updates")
            return null
        }

        val cell = blockCell(target)
        val selfFeetY = floor(playerFeetY()).toInt()
        val yOrder = (if (footPriority.value) {
            listOf(cell.second, cell.second - 1, selfFeetY, selfFeetY - 1)
        } else {
            listOf(cell.second - 1, cell.second, selfFeetY - 1, selfFeetY)
        }).distinct()

        var inspected = 0
        var clearCells = 0
        var supportedCells = 0
        var reachableCells = 0

        // First try the immediate ring, then one more block out. This covers
        // offset/jumping targets and crowded feet without inventing support
        // blocks or treating unknown world data as air.
        for (baseY in yOrder) {
            for (radius in 1..OBSIDIAN_SEARCH_RADIUS) {
                val candidates = ArrayList<Pair<ObsidianSpot, Float>>()
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (maxOf(abs(dx), abs(dz)) != radius) continue
                        inspected++
                        val x = cell.first + dx
                        val z = cell.third + dz
                        val current = knownBlock(x, baseY, z) ?: continue
                        if (current !in AIR_BLOCKS) continue
                        if (!isKnownAir(x, baseY + 1, z) || !isKnownAir(x, baseY + 2, z)) continue
                        clearCells++
                        if (playerOverlapsCell(x, baseY, z)) continue

                        val neighbor = PlacementUtil.findClickableNeighbor(x, baseY, z) ?: continue
                        val supportId = knownBlock(neighbor.first.x, neighbor.first.y, neighbor.first.z) ?: continue
                        if (supportId in NON_SOLID || supportId != neighbor.second) continue
                        supportedCells++

                        val clickPosition = clickPositionForFace(neighbor.third)
                        val clickWorld = clickWorldPosition(neighbor.first, clickPosition)
                        if (distanceFromEye(clickWorld.first, clickWorld.second, clickWorld.third) > range.value) continue
                        if (MathUtil.dist3(
                                x + 0.5f, baseY + 1f, z + 0.5f,
                                target.x, target.y + 0.9f, target.z
                            ) > range.value + 1f
                        ) continue
                        reachableCells++

                        val spot = ObsidianSpot(
                            targetId = target.runtimeId,
                            targetCell = cell,
                            base = Triple(x, baseY, z),
                            supportPos = neighbor.first,
                            supportId = supportId,
                            face = neighbor.third,
                            clickPosition = clickPosition
                        )
                        candidates.add(spot to distanceFromEye(clickWorld.first, clickWorld.second, clickWorld.third))
                    }
                }
                // Keep target-foot/floor priority, then prefer the closest
                // valid support click within that height and horizontal ring.
                candidates.minByOrNull { it.second }?.let {
                    dbg?.invoke("obsidian spot=${it.first.base} support=${it.first.supportPos}/${it.first.face}")
                    return it.first
                }
            }
        }

        dbg?.invoke(
            "obsidian search miss: targetCell=$cell yLevels=$yOrder inspected=$inspected " +
                "clear=$clearCells supported=$supportedCells inReach=$reachableCells"
        )
        return null
    }

    private fun isObsidianSpotStillValid(spot: ObsidianSpot, target: EntityTracker.TrackedEntity): Boolean {
        if (target.runtimeId != spot.targetId || blockCell(target) != spot.targetCell) return false
        val (x, y, z) = spot.base
        if (!isKnownAir(x, y, z)) return false
        if (!isKnownAir(x, y + 1, z) || !isKnownAir(x, y + 2, z)) return false
        if (playerOverlapsCell(x, y, z)) return false
        val supportId = knownBlock(spot.supportPos.x, spot.supportPos.y, spot.supportPos.z) ?: return false
        if (supportId != spot.supportId || supportId in NON_SOLID) return false
        val clickWorld = clickWorldPosition(spot.supportPos, spot.clickPosition)
        return distanceFromEye(clickWorld.first, clickWorld.second, clickWorld.third) <= range.value
    }

    /**
     * Foot-priority tiers are deliberately ordered: a valid obsidian/bedrock
     * block at the target's feet is preferred over a floor-level base; both
     * precede the general-range scan. Unknown server blocks and obstructed
     * crystal columns are never treated as free space.
     */
    private fun findAdjacentFootBase(
        target: EntityTracker.TrackedEntity,
        dbg: ((String) -> Unit)?
    ): Triple<Int, Int, Int>? {
        if (!WorldBlockTracker.hasAnyTerrainData()) return null
        val tx = floor(target.x).toInt()
        val footY = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val tiers = if (footPriority.value) listOf(footY, footY - 1) else listOf(footY - 1, footY)

        for (baseY in tiers) {
            var bestPos: Triple<Int, Int, Int>? = null
            var bestDamage = -1f
            for ((dx, dz) in ADJACENT_OFFSETS) {
                val pos = Triple(tx + dx, baseY, tz + dz)
                if (!isCrystalBaseValid(pos, target)) continue
                val damage = effectivePlaceDamage(simulateExplosionDamage(pos.first + 0.5f, pos.second + 1f, pos.third + 0.5f))
                    ?: continue
                if (damage > bestDamage) {
                    bestDamage = damage
                    bestPos = pos
                }
            }
            if (bestPos != null) {
                dbg?.invoke("FOOT PRIORITY base=$bestPos damage=${bestDamage.toInt()}")
                return bestPos
            }
        }
        return null
    }

    private fun awaitSilentRotation(key: String, x: Float, y: Float, z: Float, now: Long): Boolean {
        if (!silentRotation.value) {
            rotationGate = null
            return true
        }

        var gate = rotationGate
        val rotation = rotationTo(x, y, z)
        if (gate == null || gate.key != key) {
            gate = RotationGate(key, rotation.first, rotation.second)
            rotationGate = gate
            return false
        }

        // If either the player or target moved while waiting, send the updated
        // aim on a fresh movement packet before allowing the interaction.
        val yawDelta = abs((((gate.yaw - rotation.first) + 540f) % 360f) - 180f)
        val pitchDelta = abs(gate.pitch - rotation.second)
        if (yawDelta > 1.5f || pitchDelta > 1.5f) {
            gate.yaw = rotation.first
            gate.pitch = rotation.second
            gate.lastAppliedAtMs.set(0L)
            return false
        }

        val appliedAt = gate.lastAppliedAtMs.get()
        return appliedAt > 0L && now - appliedAt >= ROTATION_SETTLE_MS
    }

    private fun rotationTo(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val dx = x - EntityTracker.selfX
        val dy = y - playerEyeY()
        val dz = z - EntityTracker.selfZ
        val horizontal = sqrt(dx * dx + dz * dz).toDouble()
        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = Math.toDegrees(-atan2(dy.toDouble(), horizontal)).toFloat().coerceIn(-90f, 90f)
        return yaw to pitch
    }

    private fun clickPositionForFace(face: Int): Vector3f = when (face) {
        0 -> Vector3f.from(0.5f, 0f, 0.5f) // down
        1 -> Vector3f.from(0.5f, 1f, 0.5f) // up
        2 -> Vector3f.from(0.5f, 0.5f, 0f) // north
        3 -> Vector3f.from(0.5f, 0.5f, 1f) // south
        4 -> Vector3f.from(0f, 0.5f, 0.5f) // west
        5 -> Vector3f.from(1f, 0.5f, 0.5f) // east
        else -> Vector3f.from(0.5f, 1f, 0.5f)
    }

    private fun clickWorldPosition(pos: Vector3i, local: Vector3f): Triple<Float, Float, Float> =
        Triple(pos.x + local.x, pos.y + local.y, pos.z + local.z)

    private fun playerFeetY(): Float =
        if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY - 1.62f else EntityTracker.selfY

    private fun playerEyeY(): Float =
        if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY else EntityTracker.selfY + 1.62f

    private fun distanceFromEye(x: Float, y: Float, z: Float): Float =
        MathUtil.dist3(x, y, z, EntityTracker.selfX, playerEyeY(), EntityTracker.selfZ)

    private fun blockCell(target: EntityTracker.TrackedEntity): Triple<Int, Int, Int> =
        Triple(floor(target.x).toInt(), floor(target.y).toInt(), floor(target.z).toInt())

    private fun playerOverlapsCell(x: Int, y: Int, z: Int): Boolean {
        val minX = x.toFloat(); val maxX = x + 1f
        val minY = y.toFloat(); val maxY = y + 1f
        val minZ = z.toFloat(); val maxZ = z + 1f

        fun overlaps(px: Float, py: Float, pz: Float): Boolean {
            val pMinX = px - PLAYER_HALF_WIDTH
            val pMaxX = px + PLAYER_HALF_WIDTH
            val pMinY = py
            val pMaxY = py + PLAYER_HEIGHT
            val pMinZ = pz - PLAYER_HALF_WIDTH
            val pMaxZ = pz + PLAYER_HALF_WIDTH
            return pMaxX > minX && pMinX < maxX &&
                pMaxY > minY && pMinY < maxY &&
                pMaxZ > minZ && pMinZ < maxZ
        }

        if (overlaps(EntityTracker.selfX, playerFeetY(), EntityTracker.selfZ)) return true
        return EntityTracker.getPlayers(Float.MAX_VALUE).any { player ->
            player.runtimeId != EntityTracker.selfRuntimeId && overlaps(player.x, player.y, player.z)
        }
    }

    private fun isGoodBaseForTarget(pos: Triple<Int, Int, Int>, target: EntityTracker.TrackedEntity): Boolean {
        if (!isCrystalBaseValid(pos, target)) return false
        return effectivePlaceDamage(simulateExplosionDamage(pos.first + 0.5f, pos.second + 1f, pos.third + 0.5f)) != null
    }

    private fun effectivePlaceDamage(dmg: ExplosionResult): Float? {
        if (!suicide.value) {
            if (dmg.selfDamage > maxSelfDmg.value) return null
            if (dmg.selfDamage > dmg.mostDamage) return null
            if (dmg.mostDamage < minPlaceDmg.value) return null
        }
        return dmg.mostDamage
    }

    private fun effectiveBreakDamage(dmg: ExplosionResult): Float? {
        if (!suicide.value) {
            if (dmg.selfDamage > maxSelfDmg.value) return null
            if (dmg.selfDamage > dmg.mostDamage) return null
        }
        return dmg.mostDamage.takeIf { it >= minBreakDmg.value }
    }

    private fun fireIdPredictions(session: RubidiumRelaySession) {
        var i = 1L
        var fired = 0
        while (fired < idPackets.value && i <= PREDICT_HORIZON) {
            val predicted = highestCrystalId + i
            if (!crystalBlacklist.containsKey(predicted)) {
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, predicted)
                crystalBlacklist[predicted] = System.currentTimeMillis()
                fired++
            }
            i++
        }
    }

    private fun isCrystalBaseValid(pos: Triple<Int, Int, Int>, target: EntityTracker.TrackedEntity?): Boolean {
        val blockId = knownBlock(pos.first, pos.second, pos.third) ?: return false
        if (!isBaseBlock(blockId)) return false
        if (!isKnownAir(pos.first, pos.second + 1, pos.third) ||
            !isKnownAir(pos.first, pos.second + 2, pos.third)
        ) return false

        val crystalX = pos.first + 0.5f
        val crystalY = pos.second + 1f
        val crystalZ = pos.third + 0.5f
        if (distanceFromEye(crystalX, crystalY, crystalZ) > range.value) return false
        if (target != null && MathUtil.dist3(crystalX, crystalY, crystalZ, target.x, target.y, target.z) > range.value + 1f) return false
        return true
    }

    private fun isBaseBlock(identifier: String): Boolean = identifier == OBSIDIAN_ID || identifier == BEDROCK_ID

    private fun knownBlock(x: Int, y: Int, z: Int): String? {
        if (!WorldBlockTracker.hasData(x, y, z)) return null
        return WorldBlockTracker.getBlockIdentifier(x, y, z)
    }

    private fun isKnownAir(x: Int, y: Int, z: Int): Boolean =
        knownBlock(x, y, z)?.let { it in AIR_BLOCKS } == true

    private fun buildBestBase(
        target: EntityTracker.TrackedEntity,
        dbg: ((String) -> Unit)?
    ): Triple<Int, Int, Int>? {
        if (!WorldBlockTracker.hasAnyTerrainData()) return null

        val targetCell = blockCell(target)
        val candidates = LinkedHashSet<Triple<Int, Int, Int>>()
        candidates.addAll(searchPlaceBase())
        for (baseY in listOf(targetCell.second, targetCell.second - 1)) {
            for ((dx, dz) in ADJACENT_OFFSETS) {
                candidates.add(Triple(targetCell.first + dx, baseY, targetCell.third + dz))
            }
        }

        val scored = candidates.mapNotNull { pos ->
            if (!isCrystalBaseValid(pos, target)) return@mapNotNull null
            val eff = effectivePlaceDamage(
                simulateExplosionDamage(pos.first + 0.5f, pos.second + 1f, pos.third + 0.5f)
            ) ?: return@mapNotNull null
            pos to eff
        }
        val best = scored.maxByOrNull { it.second }
        dbg?.invoke("scanned ${candidates.size} bases; best=${best?.first} damage=${best?.second?.toInt()}")
        return best?.first
    }

    private fun searchPlaceBase(): List<Triple<Int, Int, Int>> {
        if (!WorldBlockTracker.hasAnyTerrainData()) return emptyList()
        val r  = floor(range.value).toInt()
        val cx = floor(EntityTracker.selfX).toInt()
        val cy = floor(playerFeetY()).toInt()
        val cz = floor(EntityTracker.selfZ).toInt()
        val bases = ArrayList<Triple<Int, Int, Int>>()
        for (x in cx - r..cx + r) {
            for (y in cy - r..cy + r) {
                for (z in cz - r..cz + r) {
                    val id = knownBlock(x, y, z) ?: continue
                    if (!isBaseBlock(id)) continue
                    if (!isKnownAir(x, y + 1, z) || !isKnownAir(x, y + 2, z)) continue
                    bases.add(Triple(x, y, z))
                }
            }
        }
        return bases
    }

    private fun simulateExplosionDamage(cx: Float, cy: Float, cz: Float): ExplosionResult {
        val diameter = EXPLOSION_SIZE * 2f
        var selfDamage = 0f
        var mostDamage = 0f

        val selfX = EntityTracker.selfX
        val selfY = playerFeetY()
        val selfZ = EntityTracker.selfZ
        val selfDist = MathUtil.dist3(cx, cy, cz, selfX, selfY + 0.9f, selfZ)
        if (selfDist <= diameter) {
            val exposure = exposureTo(cx, cy, cz, selfX, selfY, selfZ)
            selfDamage = explosionDamage(selfDist, diameter, exposure)
        }

        // EntityTracker is updated from server packets; inspect all tracked
        // players so a target on the far side of a valid base is not omitted
        // by a range query centered on the local player.
        for (p in EntityTracker.getAll()) {
            if (!p.isPlayer || p.runtimeId == EntityTracker.selfRuntimeId) continue
            val dist = MathUtil.dist3(cx, cy, cz, p.x, p.y + 0.9f, p.z)
            if (dist > diameter) continue
            val exposure = exposureTo(cx, cy, cz, p.x, p.y, p.z)
            val dmg = explosionDamage(dist, diameter, exposure)
            if (dmg > mostDamage) mostDamage = dmg
        }

        return ExplosionResult(mostDamage, selfDamage)
    }

    private fun explosionDamage(distance: Float, diameter: Float, exposure: Float): Float {
        if (distance > diameter) return 0f
        val impact = (1f - distance / diameter) * exposure
        return (impact * impact + impact) / 2f * 7f * diameter + 1f
    }

    private fun exposureTo(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        if (!WorldBlockTracker.hasAnyTerrainData()) return 1f
        val samples = arrayOf(
            Triple(tx, ty + 0.1f, tz),
            Triple(tx, ty + 0.9f, tz),
            Triple(tx, ty + 1.6f, tz),
            Triple(tx + 0.3f, ty + 0.9f, tz),
            Triple(tx - 0.3f, ty + 0.9f, tz)
        )
        var clear = 0
        for ((sx, sy, sz) in samples) {
            if (!isRayBlocked(cx, cy, cz, sx, sy, sz)) clear++
        }
        return clear.toFloat() / samples.size
    }

    private fun isRayBlocked(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        val dist = MathUtil.dist3(x0, y0, z0, x1, y1, z1)
        if (dist < 0.01f) return false
        val steps = (dist * 2f).toInt().coerceIn(1, 40)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val bx = floor(x0 + (x1 - x0) * t).toInt()
            val by = floor(y0 + (y1 - y0) * t).toInt()
            val bz = floor(z0 + (z1 - z0) * t).toInt()
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id !in NON_SOLID) return true
        }
        return false
    }

    private fun attackCrystal(session: RubidiumRelaySession, runtimeId: Long) {
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, runtimeId)
        crystalBlacklist[runtimeId] = System.currentTimeMillis()
    }

    private fun sendLog(session: RubidiumRelaySession, message: String) {
        if (!log.value) return
        try {
            session.sendToClient(TextPacket().apply {
                type               = TextPacket.Type.RAW
                isNeedsTranslation = false
                sourceName         = ""
                xuid               = ""
                platformChatId     = ""
                setMessage("§b[CrystalAura]§f $message")
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }

    private fun logFail(session: RubidiumRelaySession, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailLogMs >= LOG_FAIL_INTERVAL_MS) {
            lastFailLogMs = now
            DiagLog.log("CrystalAura", "⚠ $message")
        }
        if (!log.value) return
        if (now - lastChatFailMs < CHAT_FAIL_INTERVAL_MS) return
        lastChatFailMs = now
        sendLog(session, "⚠ $message")
    }
}
