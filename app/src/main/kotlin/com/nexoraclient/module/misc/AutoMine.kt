package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.OreTracker
import com.rubidiumclient.utils.OreTracker.OreType
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.math.*

class AutoMine : BaseModule(
    name        = "AutoMining",
    category    = ModuleCategory.MISC,
    description = "Xray'in bulduğu cevherlere otomatik yürür, yoldaki blokları kazarak ilerler, doğru kazmaya geçer ve hedefi kazar"
) {
    enum class TargetMode { Nearest, HighestTier, Manual }

    private val targetMode         = enum ("Target Mode",         TargetMode.Nearest)
    private val manualOre          = enum ("Manual Ore",          OreType.DIAMOND)
    private val scanRange          = int  ("Search Range",        96,   0,  256)
    private val walkSpeed          = float("Walk Speed (blok/s)", 4.3f, 1f,  8f)
    private val mineRange          = float("Mine Range",          4.5f, 2f,  6f)
    private val mineDelayMs        = int  ("Mine Delay (ms)",     400,  50, 3000)
    private val tunnelMineDelayMs  = int  ("Tunnel Mine Delay (ms)", 250, 50, 2000)
    private val autoSwitchPickaxe  = bool ("Auto Switch Pickaxe", true)
    private val requirePickaxe     = bool ("Require Pickaxe",     true)
    private val digThroughObstacles = bool("Dig Through Obstacles", true)
    private val stopWhenNoTarget   = bool ("Stop If No Target",   false)
    private val log                = bool ("Log",                 true)
    private val verboseLog         = bool ("Verbose Log",         false)
    private val shortcut           = bool ("Shortcut",            false)

    private enum class State { IDLE, WALKING, DIGGING_OBSTACLE, MINING_TARGET }

    @Volatile private var state: State = State.IDLE
    @Volatile private var currentTarget: Vector3i? = null
    @Volatile private var obstruction: Vector3i? = null
    @Volatile private var breakingPos: Vector3i? = null
    @Volatile private var breakStartMs = 0L
    @Volatile private var lastFailLogMs = 0L
    @Volatile private var lastChatFailMs = 0L

    private var tickJob: Job? = null
    private var scanJob: Job? = null

    companion object {
        private const val TICK_MS = 100L
        private const val SCAN_INTERVAL_MS = 1500L
        private const val LOG_FAIL_INTERVAL_MS  = 1000L
        private const val CHAT_FAIL_INTERVAL_MS = 10000L

        private val PASSABLE = setOf(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:flowing_water",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:grass",
            "minecraft:fern", "minecraft:large_fern",
            "minecraft:snow_layer", "minecraft:vine", "minecraft:ladder",
            "minecraft:torch", "minecraft:redstone_torch", "minecraft:soul_torch",
            "minecraft:dead_bush", "minecraft:sapling", "minecraft:seagrass",
            "minecraft:kelp", "minecraft:bubble_column", "minecraft:web"
        )

        private val PICKAXE_TIERS = listOf(
            "minecraft:netherite_pickaxe" to 5,
            "minecraft:diamond_pickaxe"   to 4,
            "minecraft:iron_pickaxe"      to 3,
            "minecraft:golden_pickaxe"    to 2,
            "minecraft:stone_pickaxe"     to 2,
            "minecraft:wooden_pickaxe"    to 1
        )

        private val FACE_OFFSETS = listOf(
            1 to Triple(0, 1, 0),
            2 to Triple(0, 0, -1),
            3 to Triple(0, 0, 1),
            4 to Triple(-1, 0, 0),
            5 to Triple(1, 0, 0),
            0 to Triple(0, -1, 0)
        )
    }

    override fun onEnable() {
        super.onEnable()
        state = State.IDLE
        currentTarget = null
        obstruction = null
        breakingPos = null
        tickJob?.cancel()
        tickJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                if (isEnabled) runCatching { tick() }.onFailure {
                    DiagLog.log("AutoMine", "tick exception: ${it::class.simpleName}: ${it.message}")
                }
                delay(TICK_MS)
            }
        }
        scanJob?.cancel()
        scanJob = scope.launch { scanLoop() }
    }

    private suspend fun scanLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled && !OreTracker.isScanning()) {
                val cx = floor(EntityTracker.selfX).toInt()
                val cy = floor(EntityTracker.selfY).toInt()
                val cz = floor(EntityTracker.selfZ).toInt()
                val types = if (targetMode.value == TargetMode.Manual) setOf(manualOre.value) else OreType.values().toSet()
                OreTracker.scan(cx, cy, cz, scanRange.value, types)
            }
            delay(SCAN_INTERVAL_MS)
        }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        scanJob?.cancel(); scanJob = null
        val session = PacketEventBus.currentSession
        if (session != null) cancelBreak(session)
        state = State.IDLE
        currentTarget = null
        obstruction = null
        breakingPos = null
        super.onDisable()
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return

        val target = currentTarget
        if (target == null || !isStillOre(target)) {
            if (breakingPos != null) cancelBreak(session)
            obstruction = null
            currentTarget = pickNextTarget()
            if (currentTarget == null) {
                if (state != State.IDLE) {
                    state = State.IDLE
                    if (log.value) sendLog(session, "Hedef bulunamadı, bekleniyor" + if (stopWhenNoTarget.value) " (durduruluyor)" else "")
                }
                return
            }
            if (log.value) sendLog(session, "Yeni hedef: ${currentTarget}")
        }
        val t = currentTarget ?: return

        val obs = obstruction
        if (obs != null) {
            if (!isSolidAt(obs)) {
                obstruction = null
                if (breakingPos == obs) cancelBreak(session)
            } else {
                state = State.DIGGING_OBSTACLE
                mineBlock(session, obs, tunnelMineDelayMs.value)
                return
            }
        }

        val tx = t.x + 0.5f; val ty = t.y + 0.5f; val tz = t.z + 0.5f
        val eyeY = EntityTracker.selfY + 1.62f
        val dist = MathUtil.dist3(EntityTracker.selfX, eyeY, EntityTracker.selfZ, tx, ty, tz)

        if (dist <= mineRange.value) {
            state = State.MINING_TARGET
            mineBlock(session, t, mineDelayMs.value)
            return
        }

        state = State.WALKING
        val found: Vector3i? = if (digThroughObstacles.value) findObstacleAlongPath(t) else null
        if (found != null) {
            obstruction = found
            if (log.value && verboseLog.value) sendLog(session, "Yolda engel: $found - kazılıyor")
            return
        }

        walkToward(session, t)
    }

    private fun findObstacleAlongPath(target: Vector3i): Vector3i? {
        val selfX = EntityTracker.selfX; val selfY = EntityTracker.selfY; val selfZ = EntityTracker.selfZ
        val dx = (target.x + 0.5f) - selfX
        val dy = target.y.toFloat() - selfY
        val dz = (target.z + 0.5f) - selfZ
        val dist3 = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist3 < 0.6f) return null
        val nx = dx / dist3; val ny = dy / dist3; val nz = dz / dist3

        for (step in 1..2) {
            val checkX = floor(selfX + nx * step).toInt()
            val checkY = floor(selfY + ny * step).toInt()
            val checkZ = floor(selfZ + nz * step).toInt()

            val feetPos = Vector3i.from(checkX, checkY, checkZ)
            val headPos = Vector3i.from(checkX, checkY + 1, checkZ)

            if (isSolidAt(feetPos)) return feetPos
            if (isSolidAt(headPos)) return headPos
        }
        return null
    }

    private fun isSolidAt(pos: Vector3i): Boolean {
        if (!WorldBlockTracker.hasData(pos.x, pos.y, pos.z)) return false
        val id = WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) ?: return false
        return id !in PASSABLE
    }

    private fun isStillOre(pos: Vector3i): Boolean {
        if (!WorldBlockTracker.hasData(pos.x, pos.y, pos.z)) return true
        val id = WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) ?: return false
        return id.contains("_ore") || id.contains("ancient_debris")
    }

    private fun pickNextTarget(): Vector3i? {
        val selfX = EntityTracker.selfX; val selfY = EntityTracker.selfY; val selfZ = EntityTracker.selfZ
        val range = scanRange.value.toFloat()

        val candidates = when (targetMode.value) {
            TargetMode.Manual -> OreTracker.getAllInRange(selfX, selfY, selfZ, range)
                .filter { it.type == manualOre.value }
            else -> OreTracker.getAllInRange(selfX, selfY, selfZ, range)
        }
        if (candidates.isEmpty()) return null

        val sorted = when (targetMode.value) {
            TargetMode.HighestTier -> candidates.sortedWith(Comparator { a, b ->
                val tierCmp = b.type.tier.compareTo(a.type.tier)
                if (tierCmp != 0) tierCmp else {
                    val da = MathUtil.dist3(a.pos.x + 0.5f, a.pos.y + 0.5f, a.pos.z + 0.5f, selfX, selfY, selfZ)
                    val db = MathUtil.dist3(b.pos.x + 0.5f, b.pos.y + 0.5f, b.pos.z + 0.5f, selfX, selfY, selfZ)
                    da.compareTo(db)
                }
            })
            else -> candidates.sortedBy {
                MathUtil.dist3(it.pos.x + 0.5f, it.pos.y + 0.5f, it.pos.z + 0.5f, selfX, selfY, selfZ)
            }
        }
        val best = sorted.firstOrNull() ?: return null
        return Vector3i.from(best.pos.x, best.pos.y, best.pos.z)
    }

    private fun walkToward(session: RubidiumRelaySession, target: Vector3i) {
        val selfX = EntityTracker.selfX; val selfY = EntityTracker.selfY; val selfZ = EntityTracker.selfZ
        val dx = (target.x + 0.5f) - selfX
        val dz = (target.z + 0.5f) - selfZ
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist < 0.05f) return

        val stepLen = (walkSpeed.value * (TICK_MS / 1000f)).coerceAtMost(horizDist)
        val nx = dx / horizDist; val nz = dz / horizDist
        val newX = selfX + nx * stepLen
        val newZ = selfZ + nz * stepLen

        val dy = target.y.toFloat() - selfY
        val stepY = dy.coerceIn(-stepLen, stepLen)
        val newY = selfY + stepY

        val rotation = RotationUtil.toPoint(target.x + 0.5f, target.y + 0.5f, target.z + 0.5f)

        PacketUtil.sendMove(
            session  = session,
            x = newX, y = newY, z = newZ,
            yaw = rotation.yaw, pitch = rotation.pitch,
            onGround = true,
            teleport = false,
            mirrorToClient = true
        )
    }

    private fun mineBlock(session: RubidiumRelaySession, pos: Vector3i, delayMs: Int) {
        if (autoSwitchPickaxe.value) ensurePickaxe(session)
        if (requirePickaxe.value && !hasPickaxeEquipped()) {
            logFail(session, "Uygun kazma yok - kazma durduruldu")
            return
        }

        val rotation = RotationUtil.toPoint(pos.x + 0.5f, pos.y + 0.5f, pos.z + 0.5f)
        val face = faceFor(pos)

        if (breakingPos != pos) {
            cancelBreak(session)
            breakingPos = pos
            breakStartMs = System.currentTimeMillis()
            sendPlayerAction(session, PlayerActionType.START_BREAK, pos, face)
            if (verboseLog.value) DiagLog.log("AutoMine", "START_BREAK pos=$pos face=$face")
            PacketUtil.sendMove(
                session, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
                rotation.yaw, rotation.pitch, onGround = true, teleport = false, mirrorToClient = true
            )
            return
        }

        val elapsed = System.currentTimeMillis() - breakStartMs
        if (elapsed < delayMs) return

        sendPlayerAction(session, PlayerActionType.STOP_BREAK, pos, face)
        if (verboseLog.value) DiagLog.log("AutoMine", "STOP_BREAK pos=$pos elapsed=$elapsed")

        val stillSolid = isSolidAt(pos)
        if (stillSolid) {
            breakingPos = null
        } else {
            if (log.value) sendLog(session, "Kazıldı: $pos")
            breakingPos = null
            if (pos == currentTarget) currentTarget = null
            if (pos == obstruction) obstruction = null
        }
    }

    private fun cancelBreak(session: RubidiumRelaySession) {
        val pos = breakingPos ?: return
        val face = faceFor(pos)
        sendPlayerAction(session, PlayerActionType.ABORT_BREAK, pos, face)
        breakingPos = null
    }

    private fun sendPlayerAction(session: RubidiumRelaySession, action: PlayerActionType, pos: Vector3i, face: Int) {
        try {
            session.serverBound(PlayerActionPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                this.action = action
                blockPosition = pos
                resultPosition = pos
                this.face = face
            })
        } catch (e: Exception) {
            DiagLog.log("AutoMine", "sendPlayerAction exception action=$action pos=$pos: ${e.message}")
        }
    }

    private fun faceFor(pos: Vector3i): Int {
        val dx = EntityTracker.selfX - (pos.x + 0.5f)
        val dy = (EntityTracker.selfY + 1.62f) - (pos.y + 0.5f)
        val dz = EntityTracker.selfZ - (pos.z + 0.5f)
        val adx = abs(dx); val ady = abs(dy); val adz = abs(dz)
        return when {
            ady >= adx && ady >= adz -> if (dy > 0) 1 else 0
            adx >= adz -> if (dx > 0) 5 else 4
            else -> if (dz > 0) 3 else 2
        }
    }

    private fun ensurePickaxe(session: RubidiumRelaySession) {
        val held = EntityTracker.getHeldItem()
        val heldId = held?.let { InventoryUtil.resolveIdentifier(it) }
        if (heldId != null && PICKAXE_TIERS.any { it.first == heldId }) return

        var bestSlot = -1
        var bestTier = -1
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
            val tier = PICKAXE_TIERS.firstOrNull { it.first == id }?.second ?: continue
            if (tier > bestTier) { bestTier = tier; bestSlot = slot }
        }
        if (bestSlot >= 0 && bestSlot != EntityTracker.selfHotbarSlot) {
            InventoryUtil.sendHotbarSelect(session, bestSlot)
            if (verboseLog.value) DiagLog.log("AutoMine", "Kazma değiştirildi -> slot=$bestSlot tier=$bestTier")
        }
    }

    private fun hasPickaxeEquipped(): Boolean {
        val held = EntityTracker.getHeldItem() ?: return false
        val id = InventoryUtil.resolveIdentifier(held) ?: return false
        return PICKAXE_TIERS.any { it.first == id }
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
                setMessage("§b[AutoMine]§f $message")
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }

    private fun logFail(session: RubidiumRelaySession, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailLogMs >= LOG_FAIL_INTERVAL_MS) {
            lastFailLogMs = now
            DiagLog.log("AutoMine", "⚠ $message")
        }
        if (!log.value) return
        if (now - lastChatFailMs < CHAT_FAIL_INTERVAL_MS) return
        lastChatFailMs = now
        sendLog(session, "⚠ $message (detay: baba.txt)")
    }
}
