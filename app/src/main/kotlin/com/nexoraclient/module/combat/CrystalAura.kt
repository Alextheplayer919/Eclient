package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Hasar simülasyonuna göre en iyi noktaya kristal yerleştirir/kırar"
) {

    companion object {
        private const val EXPLOSION_SIZE   = 6f
        private const val TICK_INTERVAL_MS = 10L
        private const val CRYSTAL_ID       = "minecraft:end_crystal"
        private const val LOG_FAIL_INTERVAL_MS  = 1000L
        private const val CHAT_FAIL_INTERVAL_MS = 10000L
        private const val PENDING_TIMEOUT_MS = 450L
        private const val PENDING_MATCH_RADIUS = 1.5f
        private const val PREDICT_HORIZON  = 32L

        // Vanilla Bedrock sword item IDs (used by AutoCrystal's requireWeapon path).
        // Not a hard filter — if your client adds custom weapons you can extend this.
        private val SWORD_ITEM_IDS = intArrayOf(622, 276, 267, 272, 268, 325, 625, 326, 562)

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }

    // ---------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------
    private val range           = float("Range",           5f,   3f,  10f)
    private val suicide         = bool ("Suicide",         false)
    private val whileEating     = int  ("WhileEating",     0,    0,   3) // 0 none, 1 attack, 2 place, 3 both

    private val place           = bool ("Place",           true)
    private val placeCount      = int  ("PlaceCount",      1,    1,   8)   // kept for UI compat; only 1 base used now
    private val placeDelayMs    = int  ("PlaceDelay",      20,   0,   500)
    private val wasteAmount     = int  ("WasteAmount",     1,    1,   5)

    private val explode         = bool ("Explode",         true)
    private val explodeDelayMs  = int  ("ExplodeDelay",    10,   0,   300)
    private val idPredict       = bool ("IDPredict",       true)
    private val idPackets       = int  ("IDPackets",       3,    1,   15)
    private val blacklistMs     = int  ("BlacklistMs",     500,  0,   2000)

    private val switchMode      = int  ("SwitchMode",      0,    0,   3)   // 0 none, 1 normal, 2 silent, 3 spoof
    private val requireWeapon   = bool ("RequireWeapon",   false)

    private val removeParticles = bool ("RemoveParticles", true)
    private val shortcut        = bool ("Shortcut",        false)
    private val log             = bool ("Log",             false)
    private val verboseLog      = bool ("VerboseLog",      false)

    // ---------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------
    @Volatile private var lastExplodeMs = 0L
    @Volatile private var lastPlaceMs   = 0L
    @Volatile private var lastFailLogMs = 0L
    @Volatile private var lastChatFailMs = 0L

    /** Highest crystal runtime ID we've seen the server hand out. Drives ID prediction. */
    @Volatile private var highestCrystalId = 0L

    private var tickJob: Job? = null

    /** Sticky single base. Rebuilt only when invalid. */
    @Volatile private var lockedBase: Triple<Int, Int, Int>? = null
    /** Sticky single target. Rebuilt only when out of range / dead / not visible. */
    @Volatile private var lockedTargetId: Long? = null

    /** Crystal runtimeId -> first-attack timestamp. Prevents re-attacking unkillable crystals. */
    private val crystalBlacklist = ConcurrentHashMap<Long, Long>()

    private data class PendingPlace(
        val x: Float, val y: Float, val z: Float,
        val sentAt: Long,
        val blockId: String,
        val itemNetId: Int,
        val hotbarSlot: Int
    )
    private val pendingPlaces = CopyOnWriteArrayList<PendingPlace>()

    private data class ExplosionResult(val mostDamage: Float, val selfDamage: Float)

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------
    override fun onEnable() {
        super.onEnable()
        lastExplodeMs = 0L
        lastPlaceMs   = 0L
        highestCrystalId = 0L
        lockedBase = null
        lockedTargetId = null
        crystalBlacklist.clear()
        pendingPlaces.clear()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
        if (verboseLog.value) {
            PacketEventBus.currentSession?.let { logSessionSnapshot(it) }
        }
    }

    private fun logSessionSnapshot(session: RubidiumRelaySession) {
        try {
            val blockDefs = session.clientSession.peer.codecHelper.blockDefinitions
            val itemDefs  = session.clientSession.peer.codecHelper.itemDefinitions
            val protocol  = session.activeCodec.protocolVersion
            DiagLog.log("CrystalAura", "SESSION: protocol=$protocol blockDefRegistry=${blockDefs?.let { it::class.simpleName } ?: "null"} itemDefRegistry=${itemDefs?.let { it::class.simpleName } ?: "null"} inventoriesServerAuthoritative=${EntityTracker.inventoriesServerAuthoritative}")
        } catch (e: Exception) {
            DiagLog.log("CrystalAura", "SESSION snapshot exception: ${e.message}")
        }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        pendingPlaces.clear()
        crystalBlacklist.clear()
        lockedBase = null
        lockedTargetId = null
        super.onDisable()
    }

    // ---------------------------------------------------------------
    // Packet hook — sync predictor + non-predictive attack path
    // ---------------------------------------------------------------
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (!pkt.identifier.contains("crystal", ignoreCase = true)) return

                // Sync predictor to the real runtime ID the server assigned.
                if (pkt.runtimeEntityId > highestCrystalId) {
                    highestCrystalId = pkt.runtimeEntityId
                }

                val now = System.currentTimeMillis()
                val cx = pkt.position.x; val cy = pkt.position.y; val cz = pkt.position.z

                // Clear diagnostics for pending placements at this location.
                val matched = pendingPlaces.filter {
                    MathUtil.dist3(it.x, it.y, it.z, cx, cy, cz) <= PENDING_MATCH_RADIUS
                }
                if (matched.isNotEmpty()) {
                    pendingPlaces.removeAll(matched)
                    if (verboseLog.value) {
                        PacketEventBus.currentSession?.let {
                            sendLog(it, "✓ Onaylandı: (${cx},${cy},${cz}) crystal spawn oldu (${now - matched.first().sentAt}ms)")
                        }
                    }
                }

                // Non-predictive attack path: only fire here if ID prediction is off.
                if (!idPredict.value) {
                    val distToSelf = MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    if (now - lastExplodeMs < explodeDelayMs.value) return
                    if (distToSelf > range.value) return
                    if (crystalBlacklist.containsKey(pkt.runtimeEntityId)) return

                    val dmg = simulateExplosionDamage(cx, cy, cz)
                    val hasRealTarget = dmg.mostDamage > 0f
                    if ((hasRealTarget && dmg.selfDamage <= dmg.mostDamage) || suicide.value) {
                        val session = PacketEventBus.currentSession ?: return
                        attackCrystal(session, pkt.runtimeEntityId)
                        lastExplodeMs = now
                        sendLog(session, "Patlatıldı (anlık) - ${dmg.mostDamage.toInt()} hasar")
                    } else {
                        PacketEventBus.currentSession?.let {
                            logFail(it, "Atlandı (anlık) - kendi hasarım yüksek (${dmg.selfDamage.toInt()} > ${dmg.mostDamage.toInt()})")
                        }
                    }
                }
            }

            is LevelEventPacket -> {
                if (removeParticles.value && pkt.type == LevelEvent.PARTICLE_EXPLOSION) event.cancel()
            }

            else -> {}
        }
    }

    // ---------------------------------------------------------------
    // Tick loop
    // ---------------------------------------------------------------
    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                try { tick() }
                catch (e: Exception) { DiagLog.log("CrystalAura", "tick exception: ${e.message}") }
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    @Volatile private var loggedSessionOnce = false

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return

        if (verboseLog.value && !loggedSessionOnce) {
            loggedSessionOnce = true
            logSessionSnapshot(session)
        }
        if (!verboseLog.value) loggedSessionOnce = false

        val now = System.currentTimeMillis()
        pruneBlacklist(now)
        checkTimedOutPlacements(session, now)

        // Eat detection: selfUsingItem covers eating, drinking, drawing a bow, etc.
        val eating = EntityTracker.selfUsingItem
        val allowPlace  = !eating || whileEating.value == 2 || whileEating.value == 3
        val allowAttack = !eating || whileEating.value == 1 || whileEating.value == 3

        val target = pickTarget() ?: run {
            lockedTargetId = null
            lockedBase = null
            return
        }
        lockedTargetId = target.runtimeId

        if (explode.value && allowAttack && now - lastExplodeMs >= explodeDelayMs.value) {
            tryExplodeBest(session, now)
        }
        if (place.value && allowPlace && now - lastPlaceMs >= placeDelayMs.value) {
            tryPlace(session, now, target)
        }
    }

    // ---------------------------------------------------------------
    // Target selection (sticky)
    // ---------------------------------------------------------------
    private fun pickTarget(): EntityTracker.TrackedEntity? {
        val locked = lockedTargetId
        if (locked != null) {
            val still = EntityTracker.getPlayers(range.value).firstOrNull { it.runtimeId == locked }
            if (still != null) return still
        }
        return EntityTracker.getPlayers(range.value)
            .minByOrNull {
                MathUtil.dist3(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            }
    }

    // ---------------------------------------------------------------
    // Breaking
    // ---------------------------------------------------------------
    private fun tryExplodeBest(session: RubidiumRelaySession, now: Long) {
        val crystals = EntityTracker.getCrystals(range.value)
        if (crystals.isEmpty()) return

        var bestId: Long? = null
        var bestDamage = -1f
        for (c in crystals) {
            if (crystalBlacklist.containsKey(c.runtimeId)) continue
            val dmg = simulateExplosionDamage(c.x, c.y, c.z)
            val effective = if (dmg.selfDamage > dmg.mostDamage && !suicide.value) -1f else dmg.mostDamage
            if (effective > bestDamage) { bestDamage = effective; bestId = c.runtimeId }
        }

        if (bestId != null && bestDamage > 0f) {
            attackCrystal(session, bestId)
            lastExplodeMs = now
            sendLog(session, "Patlatıldı - ${bestDamage.toInt()} hasar")
        } else if (verboseLog.value) {
            logFail(session, "${crystals.size} kristal görünüyor ama hiçbiri güvenli/hasarlı değil")
        }
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
            logFail(session, "REDDEDİLDİ (${p.x},${p.y},${p.z}) blockId=${p.blockId} itemNetId=${p.itemNetId} hotbarSlot=${p.hotbarSlot} — ${now - p.sentAt}ms içinde AddEntityPacket gelmedi")
        }
    }

    // ---------------------------------------------------------------
    // Placing — sticky single base, multi-place, ID predict
    // ---------------------------------------------------------------
    private fun tryPlace(session: RubidiumRelaySession, now: Long, target: EntityTracker.TrackedEntity) {
        val dbg: ((String) -> Unit)? = if (verboseLog.value) { msg -> DiagLog.log("CrystalAura", msg) } else null

        // Sticky: keep the same base until it stops being valid.
        var base = lockedBase?.takeIf { isBaseStillValid(it) }
        if (base == null) {
            base = buildBestBase(target, dbg)
            lockedBase = base
            if (base == null) {
                logFail(session, "Uygun zemin bulunamadı (hasTerrainData=${WorldBlockTracker.hasAnyTerrainData()}) | ${WorldBlockTracker.debugSummary()}")
                return
            }
            dbg?.invoke("LOCKED base=$base")
        }

        if (verboseLog.value) {
            val realBlockId = WorldBlockTracker.getBlockIdentifier(base.first, base.second, base.third)
            val aboveId = WorldBlockTracker.getBlockIdentifier(base.first, base.second + 1, base.third)
            dbg?.invoke("TARGET base=$base realBlockId=$realBlockId above=$aboveId")
        }

        val prepared = PlacementUtil.prepareItemForUse(session, CRYSTAL_ID, debugSink = dbg) ?: run {
            logFail(session, "Envanterde kristal bulunamadı")
            lockedBase = null
            return
        }

        val blockId = WorldBlockTracker.getBlockIdentifier(base.first, base.second, base.third)
        if (blockId == null) {
            dbg?.invoke("ATLANDI: WorldBlockTracker (${base.first},${base.second},${base.third}) için veri döndürmedi")
            PlacementUtil.revert(session, prepared)
            lockedBase = null
            return
        }

        val blockPos = Vector3i.from(base.first, base.second, base.third)
        var placed = 0

        // Multi-place: place wasteAmount crystals at the same base in one tick.
        for (i in 0 until wasteAmount.value) {
            val ok = PlacementUtil.sendPlacementUseRaw(
                session   = session,
                prepared  = prepared,
                blockPos  = blockPos,
                blockId   = blockId,
                blockFace = 1,
                debugSink = dbg
            )
            if (!ok) break
            placed++

            pendingPlaces.add(PendingPlace(
                x = base.first + 0.5f, y = base.second + 1f, z = base.third + 0.5f,
                sentAt = now,
                blockId = blockId,
                itemNetId = prepared.item.netId,
                hotbarSlot = prepared.slot
            ))

            // ID prediction: attack the runtime IDs we expect the server to hand out
            // for the crystals we just placed, BEFORE waiting for AddEntityPacket.
            if (idPredict.value) fireIdPredictions(session)
        }

        PlacementUtil.revert(session, prepared)

        if (placed > 0) {
            lastPlaceMs = now
            sendLog(session, "Yerleştirme x$placed @ $base")
        } else {
            logFail(session, "Yerleştirme paketi gönderilemedi (exception)")
            lockedBase = null
        }
    }

    /**
     * Blind-fire attack transactions at the runtime IDs we expect the crystals we just
     * placed to occupy. Bedrock assigns entity IDs monotonically on most servers, so
     * highestCrystalId+1..+N usually covers the newly spawned crystals.
     */
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

    // ---------------------------------------------------------------
    // Base validity + search
    // ---------------------------------------------------------------
    private fun isBaseStillValid(pos: Triple<Int, Int, Int>): Boolean {
        val id = WorldBlockTracker.getBlockIdentifier(pos.first, pos.second, pos.third) ?: return false
        if (id != "minecraft:obsidian" && id != "minecraft:bedrock") return false
        val above = WorldBlockTracker.getBlockIdentifier(pos.first, pos.second + 1, pos.third) ?: return true
        if (above !in NON_SOLID) return false
        val d = MathUtil.dist3(
            EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
            pos.first + 0.5f, pos.second + 1f, pos.third + 0.5f
        )
        return d <= range.value
    }

    /**
     * Returns the SINGLE best base for this target. No queue, no placeCount-style spread.
     */
    private fun buildBestBase(target: EntityTracker.TrackedEntity, dbg: ((String) -> Unit)?): Triple<Int, Int, Int>? {
    if (!WorldBlockTracker.hasAnyTerrainData()) return null

    val tx = floor(target.x).toInt()
    val ty = floor(target.y).toInt() - 1
    val tz = floor(target.z).toInt()

    // PRIORITY: scan the 8 blocks AROUND the target at feet-1 level.
    // Crystal goes NEXT TO the player, not inside their hitbox.
    var bestPos: Triple<Int, Int, Int>? = null
    var bestDmg = -1f
    for ((dx, dz) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
        val bx = tx + dx; val bz = tz + dz
        val id = WorldBlockTracker.getBlockIdentifier(bx, ty, bz) ?: continue
        if (id != "minecraft:obsidian" && id != "minecraft:bedrock") continue
        val above = WorldBlockTracker.getBlockIdentifier(bx, ty + 1, bz)
        if (above != null && above !in NON_SOLID) continue

        val dmg = simulateExplosionDamage(bx + 0.5f, ty + 2f, bz + 0.5f)
        val eff = if (dmg.selfDamage > dmg.mostDamage && !suicide.value) -1f else dmg.mostDamage
        if (eff > bestDmg) {
            bestDmg = eff
            bestPos = Triple(bx, ty, bz)
        }
    }
    if (bestPos != null && bestDmg > 0f) {
        dbg?.invoke("PRIORITY adjacent base $bestPos dmg=${bestDmg.toInt()}")
        return bestPos
    }

    // Fallback: self sphere + 8-neighbor ring around target's feet.
    val candidates = LinkedHashSet<Triple<Int, Int, Int>>()
    candidates.addAll(searchPlaceBase())
    dbg?.invoke("Self scan: ${candidates.size}")

    var tFound = 0
    for ((dx, dz) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
        val bx = tx + dx; val bz = tz + dz
        val id = WorldBlockTracker.getBlockIdentifier(bx, ty, bz) ?: continue
        if (id != "minecraft:obsidian" && id != "minecraft:bedrock") continue
        val above = WorldBlockTracker.getBlockIdentifier(bx, ty + 1, bz)
        if (above != null && above !in NON_SOLID) continue
        if (candidates.add(Triple(bx, ty, bz))) tFound++
    }
    dbg?.invoke("Target ring: +$tFound")

    if (candidates.isEmpty()) {
        dbg?.invoke("FEET DEBUG: hasTerrain=${WorldBlockTracker.hasAnyTerrainData()} | ${WorldBlockTracker.debugSummary()}")
        return null
    }

    val scored = candidates.mapNotNull { p ->
        val cx = p.first + 0.5f; val cy = p.second + 2f; val cz = p.third + 0.5f
        val dmg = simulateExplosionDamage(cx, cy, cz)
        val eff = if (dmg.selfDamage > dmg.mostDamage && !suicide.value) -1f else dmg.mostDamage
        if (eff > 0f || suicide.value) Pair(p, eff) else null
    }
    dbg?.invoke("Hasar hesaplanan ${scored.size}/${candidates.size}")

    return scored.maxByOrNull { it.second }?.first
    }    private fun searchPlaceBase(): List<Triple<Int, Int, Int>> {
        if (!WorldBlockTracker.hasAnyTerrainData()) return emptyList()
        val r  = floor(range.value).toInt()
        val cx = floor(EntityTracker.selfX).toInt()
        val cy = floor(EntityTracker.selfY).toInt()
        val cz = floor(EntityTracker.selfZ).toInt()
        val bases = ArrayList<Triple<Int, Int, Int>>()
        for (x in cx - r..cx + r) {
            for (y in cy - r..cy + r) {
                for (z in cz - r..cz + r) {
                    val id = WorldBlockTracker.getBlockIdentifier(x, y, z) ?: continue
                    if (id != "minecraft:obsidian" && id != "minecraft:bedrock") continue
                    val above = WorldBlockTracker.getBlockIdentifier(x, y + 1, z)
                    if (above != null && above !in NON_SOLID) continue
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

        val selfDist = MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 0.9f, EntityTracker.selfZ)
        if (selfDist <= diameter) {
            val exposure = exposureTo(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            selfDamage = explosionDamage(selfDist, diameter, exposure)
        }

        for (p in EntityTracker.getPlayers(diameter)) {
            if (p.runtimeId == EntityTracker.selfRuntimeId) continue
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
private fun attackCrystal(session: RubidiumRelaySession, runtimeId: Long) {
    PacketUtil.sendSwing(session)
    PacketUtil.sendAttack(session, runtimeId)
    crystalBlacklist[runtimeId] = System.currentTimeMillis()
}
}
