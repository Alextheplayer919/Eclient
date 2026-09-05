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
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.random.Random
import kotlin.math.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "WAura combat + random-direction orbit + guaranteed crits"
), PacketEventBus.PacketListener {

    enum class TargetMode { Single, Switch, Multi }
    enum class CritMode { None, Fast, UltraFast }

    // ── WAura settings ──────────────────────────────────
    private val playersOnly     = bool("Players Only",   true)
    private val mobsOnly        = bool("Mobs Only",      false)
    private val range           = float("Range",         50f,  2f,   50f)
    private val cps             = int  ("CPS",           25,   1,    50)
    private val boost           = int  ("Packets per hit", 2, 1,    5)
    private val targetMode      = enum("Target Mode",    TargetMode.Single)
    private val switchDelay     = int  ("Switch Delay",  100,  20,   1000)

    // ── Orbit & Chase ──────────────────────────────────
    private val orbitEnabled        = bool("Orbit",          true)
    private val orbitRange          = float("Orbit Range",   8f,   2f,   20f)
    private val orbitChaseDist      = float("Chase Distance",10f,  3f,   30f)
    private val orbitSpeed          = float("Orbit Speed",   30f,  5f,   120f)
    private val orbitHorizSpeed     = float("Orbit H Speed", 25f,  10f,  60f)
    private val orbitVertSpeed      = float("Orbit V Speed", 8f,   2f,   30f)
    private val orbitHeight         = float("Orbit Height",  0.2f, 0f,  2f)
    private val orbitRandomDir      = bool("Random Direction", false)      // new: random direction switching
    private val orbitSwitchInterval = int("Switch Interval (s)", 3, 1, 10) // seconds between direction flips

    // ── Rotation ──────────────────────────────────────
    private val silentRot       = bool("Silent Rotation", true)
    private val ignoreFriends   = bool("Ignore Friends",  true)
    private val antiBot         = bool("Anti Bot",        true)
    private val shortcut        = bool("Shortcut",       false)

    // ── Crit ──────────────────────────────────────────
    private val critMode        = enum("Crit Mode", CritMode.UltraFast)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
        private const val TARGET_SCAN_INTERVAL = 50L
        private const val ORBIT_TOLERANCE = 0.5f
        private const val MAX_ATTACK_BOOST = 3
    }

    // ── State ──────────────────────────────────────────
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastSwitchMs   = 0L
    @Volatile private var switchIndex    = 0
    @Volatile private var currentTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastScanMs     = 0L
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var orbitAngle     = 0f
    private var lastOrbitPos = Vector3f.ZERO
    private var orbitDirection = 1f                     // 1 = clockwise, -1 = counterclockwise
    private var lastDirSwitchMs = 0L

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs   = 0L
        lastSwitchMs   = 0L
        switchIndex    = 0
        currentTarget  = null
        cachedTargets  = emptyList()
        lastScanMs     = 0L
        headLockYaw    = EntityTracker.selfYaw
        headLockPitch  = EntityTracker.selfPitch
        orbitAngle     = Random.nextFloat() * 360f
        lastOrbitPos   = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        orbitDirection = if (Random.nextBoolean()) 1f else -1f
        lastDirSwitchMs = System.currentTimeMillis()
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

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val session = event.session
        val nowMs = System.currentTimeMillis()

        if (cachedTargets.isEmpty()) {
            currentTarget = null
            event.cancelAndReplace(pkt)
            return
        }

        // ── Target selection ──
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
        if (primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Silent rotation ──
        val rot = RotationUtil.toEntity(primary)
        headLockYaw   = rot.yaw
        headLockPitch = rot.pitch
        pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
        if (!silentRot.value) {
            EntityTracker.selfYaw   = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }

        // ── Movement: chase OR orbit ──
        if (orbitEnabled.value) {
            val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

            // Anti‑rubberband check
            val dx = selfPos.x - lastOrbitPos.x
            val dy = selfPos.y - lastOrbitPos.y
            val dz = selfPos.z - lastOrbitPos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > 4.5f) {
                lastOrbitPos = selfPos
                // continue to allow recovery
            }

            // Distance to target
            val distToTarget = MathUtil.dist3(selfPos.x, selfPos.y, selfPos.z, primary.x, primary.y, primary.z)

            // Decide: chase if too far, else orbit
            val shouldOrbit = distToTarget <= orbitChaseDist.value

            // ── Random direction switching ──
            if (orbitRandomDir.value) {
                if (nowMs - lastDirSwitchMs >= orbitSwitchInterval.value * 1000L) {
                    orbitDirection = -orbitDirection  // flip direction
                    lastDirSwitchMs = nowMs
                }
            }

            // Target position for movement
            val targetX: Float
            val targetZ: Float
            val targetY: Float

            if (shouldOrbit) {
                // Orbit: circle around target
                orbitAngle += orbitSpeed.value * orbitDirection
                orbitAngle %= 360f
                val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
                targetX = primary.x + cos(rad) * orbitRange.value
                targetZ = primary.z + sin(rad) * orbitRange.value
                targetY = primary.y + orbitHeight.value
            } else {
                // Chase: move directly towards target (no orbit)
                targetX = primary.x
                targetZ = primary.z
                targetY = primary.y + orbitHeight.value
            }

            // Compute velocity towards target position
            val dirX = targetX - selfPos.x
            val dirZ = targetZ - selfPos.z
            val dirY = targetY - selfPos.y
            val horizDist = sqrt(dirX * dirX + dirZ * dirZ)

            if (horizDist > ORBIT_TOLERANCE || abs(dirY) > ORBIT_TOLERANCE) {
                val speed = orbitHorizSpeed.value / 20f
                val maxHoriz = sqrt(5.99f)
                val clampedSpeed = min(speed, maxHoriz)

                // Horizontal motion
                var motionX = 0f
                var motionZ = 0f
                if (horizDist > 0.1f) {
                    val normX = dirX / horizDist
                    val normZ = dirZ / horizDist
                    motionX = normX * clampedSpeed
                    motionZ = normZ * clampedSpeed
                }

                // Vertical motion
                val vertSpeed = when {
                    dirY > 0.2f -> orbitVertSpeed.value / 20f
                    dirY < -0.2f -> -orbitVertSpeed.value / 20f
                    else -> 0f
                }.coerceIn(-0.5f, 0.5f)

                val motionPacket = SetEntityMotionPacket()
                motionPacket.runtimeEntityId = EntityTracker.selfRuntimeId
                motionPacket.motion = Vector3f.from(motionX, vertSpeed, motionZ)
                session.clientBound(motionPacket)

                // Update last position to current (so next check sees smooth movement)
                lastOrbitPos = selfPos
            }
        }

        // ── Attack logic ──
        val attackDelay = 1000L / cps.value
        if (nowMs - lastAttackMs < attackDelay) {
            event.cancelAndReplace(pkt)
            return
        }

        val targetsToHit = when (targetMode.value) {
            TargetMode.Single, TargetMode.Switch -> listOf(primary)
            TargetMode.Multi -> cachedTargets
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

        val needsCrit = critMode.value != CritMode.None && !tpAuraRecentlyMoved()
        if (needsCrit && EntityTracker.selfSprinting) {
            pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val attackCount = min(boost.value, MAX_ATTACK_BOOST)

        scope.launch {
            if (needsCrit) {
                if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                    try {
                        when (critMode.value) {
                            CritMode.Fast      -> injectCritFastUp(session)
                            CritMode.UltraFast -> injectCritUltraFastUp(session)
                            CritMode.None      -> {}
                        }
                        repeat(attackCount) {
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
                repeat(attackCount) {
                    PacketUtil.sendSwing(session)
                    inRange.forEach { t ->
                        val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                        PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                    }
                }
            }
        }

        lastAttackMs = nowMs
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
}
