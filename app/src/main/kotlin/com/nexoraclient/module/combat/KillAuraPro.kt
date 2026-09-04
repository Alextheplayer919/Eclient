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
import kotlin.random.Random

class KillAuraV3 : BaseModule(
    name        = "AuraV3",
    category    = ModuleCategory.COMBAT,
    description = "2b2tpe tarzı aura portu"
), PacketEventBus.PacketListener {

    enum class HitType   { Single, Multi }
    enum class DelayMode { Interval, Cps }
    enum class RotMode   { None, Rotation }
    enum class CritMode  { None, Fast, UltraFast }

    private val range          = float("Range",          32f,  1f,   32f)
    private val hitType        = enum ("Hit Type",       HitType.Multi)
    private val delayMode      = enum ("Delay Method",   DelayMode.Cps)
    private val hitChance      = int  ("Hit Chance",     100,  0,    100)
    private val intervalTicks  = int  ("Interval",         1,   0,    20)
    private val cpsMin         = int  ("CPS Min",         40,   1,    40)
    private val cpsMax         = int  ("CPS Max",         40,   1,    40)
    private val attempts       = int  ("Attempts",        10,   1,    10)
    private val boost          = bool ("Boost",          true)
    private val boostAmount    = float("Boost Amount",   10f,   1f,   10f)
    private val boostDelay     = float("Boost Delay",    0.1f, 0.1f,  60f)
    private val boostAttempts  = int  ("Boost Attempts", 20,    1,    20)
    private val rotMode        = enum ("Rotation",        RotMode.Rotation)
    private val rotationSpeed  = float("Rotation Speed", 30f,   0f,   30f)
    private val osuRots        = bool ("Osu Rots",       false)
    private val ignoreFriends  = bool ("Ignore Friends",  true)
    private val antiBot        = bool ("Anti Bot",        true)
    private val includeMobs    = bool ("Mobs",           false)
    private val shortcut       = bool ("Shortcut",       false)

    private val keepDistance            = bool ("Keep Distance",           false)
    private val teleportBehind          = bool ("Teleport Behind",         false)
    private val strafe                  = bool ("Strafe",                 false)
    private val strafeSpeed             = float("Strafe Speed",           30f,  1f,  30f)
    private val keepDistanceRange       = float("Keep Distance",           4f,   1f,  10f)
    private val keepDistanceTolerance   = float("Keep Distance Tolerance", 0.4f, 0.1f, 3f)
    private val keepDistanceSpeed       = float("Keep Distance Speed",     8f,   1f,  8f)
    private val keepDistanceYOffset     = float("Keep Distance Y Offset",  0f,  -10f, 10f)
    private val keepDistanceRetreatOnly = bool ("Retreat Only",            false)

    private val critMode = enum("Crit Mode", CritMode.UltraFast)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
        private const val INPUT_TICK_MS = 50L
    }

    @Volatile private var pendingAttacks = 0
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var isBoosting     = false
    @Volatile private var boostTimerMs   = 0L
    @Volatile private var lastBoostMs    = 0L
    @Volatile private var strafeAngle    = 0f

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        pendingAttacks = 0
        lastAttackMs   = 0L
        lastScanMs     = 0L
        cachedTargets  = emptyList()
        headLockYaw    = EntityTracker.selfYaw
        headLockPitch  = EntityTracker.selfPitch
        isBoosting     = false
        boostTimerMs   = 0L
        lastBoostMs    = System.currentTimeMillis()
        strafeAngle    = 0f
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

        val targets = cachedTargets
        val primary = targets.firstOrNull()

        if (rotMode.value == RotMode.Rotation && primary != null) {
            val rot = RotationUtil.toEntity(primary)
            var targetYaw = rot.yaw
            if (osuRots.value) {
                val snap = 36.4f
                targetYaw = Math.round(targetYaw / snap) * snap
            }
            val f = (rotationSpeed.value / 30f).coerceIn(0.02f, 1f)
            headLockYaw   = smoothYaw(headLockYaw, targetYaw, f)
            headLockPitch = smoothPitch(headLockPitch, rot.pitch, f)
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            EntityTracker.selfYaw   = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }

        if (keepDistance.value && primary != null) {
            pkt.position = applyKeepDistance(pkt.position, primary)
            EntityTracker.selfX = pkt.position.x
            EntityTracker.selfY = pkt.position.y
            EntityTracker.selfZ = pkt.position.z
        }

        if (pendingAttacks <= 0 || primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        val session = event.session

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val toAttack = if (hitType.value == HitType.Single) listOfNotNull(primary)
                       else targets.take(attempts.value.coerceAtLeast(1))
        val attackCount = pendingAttacks

        val needsCritInjection = !keepDistance.value &&
                                  critMode.value != CritMode.None &&
                                  !tpAuraRecentlyMoved()

        if (needsCritInjection && EntityTracker.selfSprinting) {
            pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }

        scope.launch {
            if (needsCritInjection) {
                CritLock.tryRunWait(CRIT_LOCK_KEY, timeoutMs = 30L) {
                    when (critMode.value) {
                        CritMode.Fast      -> injectCritFastUp(session)
                        CritMode.UltraFast -> injectCritUltraFastUp(session)
                        CritMode.None      -> {}
                    }

                    repeat(attackCount) {
                        PacketUtil.sendSwing(session)
                        toAttack.forEach { t ->
                            if (Random.nextInt(100) < hitChance.value) {
                                val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                                PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                            }
                        }
                    }

                    if (critMode.value != CritMode.None) {
                        PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
                    }
                }
            } else {
                repeat(attackCount) {
                    PacketUtil.sendSwing(session)
                    toAttack.forEach { t ->
                        if (Random.nextInt(100) < hitChance.value) {
                            val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                            PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                        }
                    }
                }
            }
        }

        pendingAttacks = 0
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
            if (isEnabled) tick()
            delay(20L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        if (boost.value) {
            if (!isBoosting) {
                if (now - lastBoostMs >= (boostDelay.value * 1000).toLong()) {
                    isBoosting   = true
                    boostTimerMs = now
                    lastBoostMs  = now
                }
            } else if (now - boostTimerMs >= 1000L) {
                isBoosting = false
            }
        }

        var delayMs = when (delayMode.value) {
            DelayMode.Interval -> intervalTicks.value * 50L
            DelayMode.Cps      -> MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        }
        if (isBoosting) delayMs = (delayMs / boostAmount.value).toLong().coerceAtLeast(1L)

        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= 50L) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }

        if (cachedTargets.isEmpty()) return

        lastAttackMs   = now
        pendingAttacks = if (isBoosting) boostAttempts.value else attempts.value
    }

    private fun applyKeepDistance(pos: Vector3f, target: EntityTracker.TrackedEntity): Vector3f {
        val maxStep = keepDistanceSpeed.value * (INPUT_TICK_MS / 1000f)

        val desiredY = target.y + keepDistanceYOffset.value
        val diffY = desiredY - pos.y
        val newY = if (kotlin.math.abs(diffY) <= keepDistanceTolerance.value) pos.y
                   else pos.y + diffY.coerceIn(-maxStep, maxStep)

        if (teleportBehind.value) {
            val yawRad = Math.toRadians(target.yaw.toDouble()).toFloat()
            val behindX = target.x + kotlin.math.sin(yawRad) * keepDistanceRange.value
            val behindZ = target.z - kotlin.math.cos(yawRad) * keepDistanceRange.value
            return stepToward(pos, newY, behindX, behindZ, maxStep)
        }

        if (strafe.value) {
            strafeAngle = (strafeAngle + strafeSpeed.value) % 360f
            val rad = Math.toRadians(strafeAngle.toDouble())
            val orbitX = target.x + (kotlin.math.sin(rad) * keepDistanceRange.value).toFloat()
            val orbitZ = target.z + (kotlin.math.cos(rad) * keepDistanceRange.value).toFloat()
            return stepToward(pos, newY, orbitX, orbitZ, maxStep)
        }

        val dx = pos.x - target.x
        val dz = pos.z - target.z
        val horizDist = MathUtil.dist2(pos.x, pos.z, target.x, target.z)
        if (horizDist < 0.05f) return Vector3f.from(pos.x, newY, pos.z)

        val desired = keepDistanceRange.value
        val tol = keepDistanceTolerance.value
        val diff = horizDist - desired
        if (kotlin.math.abs(diff) <= tol) return Vector3f.from(pos.x, newY, pos.z)

        if (keepDistanceRetreatOnly.value && diff > 0f) return Vector3f.from(pos.x, newY, pos.z)

        val dirX = dx / horizDist; val dirZ = dz / horizDist
        val correction = (-diff).coerceIn(-maxStep, maxStep)

        return Vector3f.from(pos.x + dirX * correction, newY, pos.z + dirZ * correction)
    }

    private fun stepToward(pos: Vector3f, newY: Float, targetX: Float, targetZ: Float, maxStep: Float): Vector3f {
        val dx = targetX - pos.x; val dz = targetZ - pos.z
        val dist = MathUtil.dist2(pos.x, pos.z, targetX, targetZ)
        if (dist < 0.05f) return Vector3f.from(pos.x, newY, pos.z)
        val step = dist.coerceAtMost(maxStep)
        return Vector3f.from(pos.x + (dx / dist) * step, newY, pos.z + (dz / dist) * step)
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val raw = EntityTracker.getEntitiesInRange(range.value)
        data class Scored(val entity: EntityTracker.TrackedEntity, val distSq: Float)
        val scored = ArrayList<Scored>(raw.size)
        for (e in raw) {
            if (!includeMobs.value && !e.isPlayer) continue
            if (e.runtimeId == EntityTracker.selfRuntimeId) continue
            if (ignoreFriends.value && e.isFriendEntity) continue
            if (antiBot.value && e.isLikelyBot()) continue
            scored.add(Scored(e, MathUtil.dist3sq(e.x, e.y, e.z, sx, sy, sz)))
        }
        scored.sortBy { it.distSq }
        return scored.map { it.entity }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    private fun smoothYaw(cur: Float, tgt: Float, f: Float): Float {
        var d = tgt - cur
        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        var r = (cur + d * f) % 360f
        if (r > 180f) r -= 360f; if (r < -180f) r += 360f
        return r
    }

    private fun smoothPitch(cur: Float, tgt: Float, f: Float) =
        (cur + (tgt - cur) * f).coerceIn(-90f, 90f)
}
