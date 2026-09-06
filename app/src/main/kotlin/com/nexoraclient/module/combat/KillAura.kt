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
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.*
import kotlin.random.Random

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "WAura attack + manual orbit (reliable)"
), PacketEventBus.PacketListener {

    // ── WAura attack settings ──────────────────────────────
    private val playersOnly   = bool("Players Only", true)
    private val mobsOnly      = bool("Mobs Only",    false)
    private val range         = float("Range",       50f,  2f,  50f)
    private val cps           = int  ("CPS",         25,   1,   50)
    private val boost         = int  ("Packets",     2,    1,   10)
    private val targetMode    = int  ("Target Mode", 2,    0,   2)   // 0=Single, 1=Switch, 2=Multi
    private val switchDelay   = int  ("Switch Delay",100,  20,  1000)

    // ── Orbit settings ──────────────────────────────────────
    private val orbitEnabled     = bool("Orbit",          false)
    private val orbitRange       = float("Orbit Range",   6f,   1.5f, 25f)   // ⬅️ ENFORCED
    private val orbitSpeed       = float("Orbit Speed",   8f,   1f,   30f)   // degrees per tick when pressing A/D
    private val orbitMoveSpeed   = float("Move Speed",    2.0f,  0.5f, 6f)   // blocks per tick (higher = faster snap)
    private val orbitStopDist    = float("Stop Distance", 100f,  10f,  200f) // max distance to orbit

    // ── Rotation ────────────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val POSITION_TOLERANCE = 0.05f   // tight tolerance
    }

    // ── State ─────────────────────────────────────────────────
    @Volatile private var lastAttackNs   = 0L
    @Volatile private var lastSwitchMs   = 0L
    @Volatile private var switchIndex    = 0
    @Volatile private var currentTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastScanMs     = 0L
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var orbitAngle     = 0f

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackNs   = 0L
        lastSwitchMs   = 0L
        switchIndex    = 0
        currentTarget  = null
        cachedTargets  = emptyList()
        lastScanMs     = 0L
        headLockYaw    = EntityTracker.selfYaw
        headLockPitch  = EntityTracker.selfPitch
        orbitAngle     = Random.nextFloat() * 360f
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                if (System.currentTimeMillis() - lastScanMs >= TARGET_SCAN_INTERVAL) {
                    cachedTargets = selectTargets()
                    lastScanMs = System.currentTimeMillis()
                }
            }
            delay(20L)
        }
    }

    // ── Target selection ──────────────────────────────────────
    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val raw = EntityTracker.getEntitiesInRange(range.value)

        return raw
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { isTarget(it) }
            .filterNot { ignoreFriends.value && it.isFriendEntity }
            .filterNot { antiBot.value && it.isLikelyBot() }
            .sortedBy { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun isTarget(entity: EntityTracker.TrackedEntity): Boolean {
        val isPlayer = entity.isPlayer
        return when {
            playersOnly.value && mobsOnly.value -> false
            playersOnly.value && !isPlayer -> false
            mobsOnly.value && isPlayer -> false
            else -> true
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    // ── Rotation ──────────────────────────────────────────────
    private fun updateRotation(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val rot = RotationUtil.toEntity(target)
        headLockYaw = rot.yaw
        headLockPitch = rot.pitch
        pkt.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)

        if (!silentRot.value) {
            EntityTracker.selfYaw = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }
    }

    // ── Orbit (aggressive distance enforcement) ──────────────
    private fun applyOrbit(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val distToTarget = MathUtil.dist3(selfPos.x, selfPos.y, selfPos.z, target.x, target.y, target.z)

        // If target is too far, stop orbiting (but allow recovery)
        if (distToTarget > orbitStopDist.value) {
            return
        }

        // Read A/D input (immune to Fly)
        var strafeInput = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT)) strafeInput = -1f
        else if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) strafeInput = 1f

        // Update orbit angle only when input is pressed
        if (strafeInput != 0f) {
            orbitAngle += strafeInput * orbitSpeed.value
            orbitAngle %= 360f
        }

        // Compute desired position on the circle at EXACT orbitRange
        val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
        val desiredX = target.x + cos(rad) * orbitRange.value
        val desiredZ = target.z + sin(rad) * orbitRange.value
        val desiredY = target.y + 0.2f   // slightly above feet

        // Move toward desired position (or snap if close)
        val dx = desiredX - selfPos.x
        val dy = desiredY - selfPos.y
        val dz = desiredZ - selfPos.z
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)

        val newPos = if (totalDist <= POSITION_TOLERANCE) {
            // Exactly at desired – stay there
            Vector3f.from(desiredX, desiredY, desiredZ)
        } else {
            // Step toward desired position
            val step = min(orbitMoveSpeed.value, totalDist)
            Vector3f.from(
                selfPos.x + dx / totalDist * step,
                selfPos.y + dy / totalDist * step,
                selfPos.z + dz / totalDist * step
            )
        }

        // Override packet position and update tracker
        pkt.position = newPos
        EntityTracker.selfX = newPos.x
        EntityTracker.selfY = newPos.y
        EntityTracker.selfZ = newPos.z
    }

    // ── Attack ─────────────────────────────────────────────────
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        val session = event.session

        if (cachedTargets.isEmpty()) {
            currentTarget = null
            event.cancelAndReplace(pkt)
            return
        }

        val nowNs = System.nanoTime()
        val nowMs = System.currentTimeMillis()

        val target = when (targetMode.value) {
            0 -> {
                if (currentTarget == null || !cachedTargets.contains(currentTarget)) {
                    currentTarget = cachedTargets.firstOrNull()
                }
                currentTarget
            }
            1 -> {
                if (nowMs - lastSwitchMs >= switchDelay.value) {
                    switchIndex = (switchIndex + 1) % cachedTargets.size
                    currentTarget = cachedTargets[switchIndex]
                    lastSwitchMs = nowMs
                }
                currentTarget
            }
            else -> null
        }

        val primary = target ?: cachedTargets.firstOrNull()
        if (primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Rotation ──────────────────────────────────────────
        updateRotation(primary, pkt)

        // ── Orbit ──────────────────────────────────────────────
        if (orbitEnabled.value) {
            applyOrbit(session, primary, pkt)
        }

        // ── Attack timing ────────────────────────────────────
        val attackDelay = 1_000_000_000L / cps.value
        if (nowNs - lastAttackNs < attackDelay) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Attack ─────────────────────────────────────────────
        val targetsToHit = when (targetMode.value) {
            0, 1 -> listOfNotNull(primary)
            else -> cachedTargets
        }

        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val inRange = targetsToHit.filter {
            MathUtil.dist3(sx, sy, sz, it.x, it.y, it.z) <= range.value
        }
        if (inRange.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        repeat(boost.value) {
            inRange.forEach { targetEntity ->
                PacketUtil.sendSwing(session)
                val clickPos = Vector3f.from(targetEntity.x, targetEntity.y + 1.5f, targetEntity.z)
                PacketUtil.sendAttack(session, targetEntity.runtimeId, slot, clickPos)
            }
        }

        lastAttackNs = nowNs
        event.cancelAndReplace(pkt)
    }
}
