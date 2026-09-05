package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.CritLock
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.random.Random
import kotlin.math.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "WAura-based combat with silent aim + orbit"
), PacketEventBus.PacketListener {

    enum class TargetMode { Single, Switch, Multi }
    enum class CritMode { None, Fast, UltraFast }

    // ── WAura settings ──────────────────────────────────
    private val playersOnly     = bool("Players Only",   true)
    private val mobsOnly        = bool("Mobs Only",      false)
    private val range           = float("Range",         50f,  2f,   50f)
    private val cps             = int  ("CPS",           25,   1,    50)
    private val boost           = int  ("Packets per hit", 2, 1,    10)
    private val targetMode      = enum("Target Mode",    TargetMode.Single)
    private val switchDelay     = int  ("Switch Delay",  100,  20,   1000)

    // ── Orbit & rotation ──────────────────────────────
    private val orbitEnabled    = bool("Orbit",          true)
    private val orbitRange      = float("Orbit Range",   4.5f,  1f,   12f)
    private val orbitSpeed      = float("Orbit Speed",   30f,   5f,   120f)
    private val silentRot       = bool("Silent Rotation", true)   // server‑only lock
    private val osuRots         = bool("Osu Rots",       false)
    private val ignoreFriends   = bool("Ignore Friends",  true)
    private val antiBot         = bool("Anti Bot",        true)

    // ── Shortcut (floating button) ────────────────────
    private val shortcut        = bool("Shortcut",       false)   // appears in shortcut bar

    // ── Crit ──────────────────────────────────────────
    private val critMode        = enum("Crit Mode", CritMode.UltraFast)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
    }

    // ── State ──────────────────────────────────────────
    @Volatile private var lastAttackNs   = 0L
    @Volatile private var lastSwitchMs   = 0L
    @Volatile private var switchIndex    = 0
    @Volatile private var currentTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastScanMs     = 0L
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var orbitAngle     = 0f
    @Volatile private var lastOrbitMoveMs = 0L

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
        lastOrbitMoveMs = 0L
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val session = event.session
        val nowNs = System.nanoTime()
        val nowMs = System.currentTimeMillis()

        // ── Update targets ──────────────────────────────
        if (nowMs - lastScanMs >= 50L) {
            cachedTargets = selectTargets()
            lastScanMs = nowMs
        }

        if (cachedTargets.isEmpty()) {
            currentTarget = null
            event.cancelAndReplace(pkt)
            return
        }

        // ── Select current target ────────────────────────
        val target = when (targetMode.value) {
            TargetMode.Single -> {
                if (currentTarget == null || !cachedTargets.contains(currentTarget)) {
                    currentTarget = cachedTargets.firstOrNull()
                }
                currentTarget
            }
            TargetMode.Switch -> {
                if (nowMs - lastSwitchMs >= switchDelay.value) {
                    switchIndex = (switchIndex + 1) % cachedTargets.size
                    currentTarget = cachedTargets[switchIndex]
                    lastSwitchMs = nowMs
                }
                currentTarget
            }
            TargetMode.Multi -> null
        }

        val primary = target ?: cachedTargets.firstOrNull()

        // ── Silent rotation ──────────────────────────────
        if (primary != null) {
            val rot = RotationUtil.toEntity(primary)
            var targetYaw = rot.yaw
            if (osuRots.value) {
                val snap = 36.4f
                targetYaw = round(targetYaw / snap) * snap
            }
            headLockYaw   = targetYaw
            headLockPitch = rot.pitch
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            if (!silentRot.value) {
                EntityTracker.selfYaw   = headLockYaw
                EntityTracker.selfPitch = headLockPitch
            }
        }

        // ── Orbit movement ──────────────────────────────
        if (orbitEnabled.value && primary != null) {
            val nowMove = System.currentTimeMillis()
            if (nowMove - lastOrbitMoveMs >= 20L) {
                lastOrbitMoveMs = nowMove
                orbitAngle += orbitSpeed.value
                orbitAngle %= 360f
                val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
                val dx = sin(rad) * orbitRange.value
                val dz = cos(rad) * orbitRange.value
                val targetPos = Vector3f.from(
                    primary.x + dx,
                    primary.y + orbitRange.value * 0.5f,
                    primary.z + dz
                )
                val curPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                val step = 0.5f
                val delta = targetPos.sub(curPos)
                val dist = delta.length()
                val newPos = if (dist > step) {
                    curPos.add(delta.mul(step / dist))
                } else {
                    targetPos
                }
                try {
                    val movePacket = MovePlayerPacket().apply {
                        runtimeEntityId = EntityTracker.selfRuntimeId
                        position = newPos
                        rotation = pkt.rotation
                        mode = MovePlayerPacket.Mode.NORMAL
                        isOnGround = false
                        ridingRuntimeEntityId = 0L
                    }
                    session.serverBound(movePacket)
                    session.clientBound(movePacket)
                    TPAura.notifyExternalPositionOverride()
                    EntityTracker.selfX = newPos.x
                    EntityTracker.selfY = newPos.y
                    EntityTracker.selfZ = newPos.z
                } catch (_: Exception) {}
            }
        }

        // ── Attack logic (WAura style) ─────────────────
        val attackDelay = 1_000_000_000L / cps.value
        if (nowNs - lastAttackNs < attackDelay) {
            event.cancelAndReplace(pkt)
            return
        }

        val targetsToHit = when (targetMode.value) {
            TargetMode.Single, TargetMode.Switch -> {
                if (primary != null) listOf(primary) else emptyList()
            }
            TargetMode.Multi -> cachedTargets
        }

        if (targetsToHit.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        // Range check
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

        val needsCrit = critMode.value != CritMode.None && !tpAuraRecentlyMoved()
        if (needsCrit && EntityTracker.selfSprinting) {
            pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        scope.launch {
            if (needsCrit) {
                if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                    try {
                        when (critMode.value) {
                            CritMode.Fast      -> injectCritFastUp(session)
                            CritMode.UltraFast -> injectCritUltraFastUp(session)
                            CritMode.None      -> {}
                        }
                        repeat(boost.value) {
                            PacketUtil.sendSwing(session)
                            inRange.forEach { t ->
                                val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                                PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                            }
                        }
                        if (critMode.value != CritMode.None) {
                            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
                        }
                    } finally {
                        CritLock.release(CRIT_LOCK_KEY)
                    }
                }
            } else {
                repeat(boost.value) {
                    PacketUtil.sendSwing(session)
                    inRange.forEach { t ->
                        val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                        PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                    }
                }
            }
        }

        lastAttackNs = nowNs
        event.cancelAndReplace(pkt)
    }

    private fun tpAuraRecentlyMoved(): Boolean =
        System.currentTimeMillis() - TPAura.lastPositionOverrideMs < TPAURA_CONFLICT_WINDOW_MS

    private suspend fun injectCritFastUp(s: RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(10L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = false)
        delay(5L)
    }

    private suspend fun injectCritUltraFastUp(s: RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(5L)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) delay(20L)
        }
    }

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
        // WAura logic
        return when {
            playersOnly.value && mobsOnly.value -> false
            playersOnly.value && !isPlayer -> false
            mobsOnly.value && isPlayer -> false
            else -> true
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
