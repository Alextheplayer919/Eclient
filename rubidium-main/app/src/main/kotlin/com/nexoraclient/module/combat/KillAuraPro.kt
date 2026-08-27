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
    private val hitType        = enum("Hit Type",        HitType.Multi)
    private val delayMode      = enum("Delay Method",    DelayMode.Cps)
    private val hitChance      = int("Hit Chance",       100,  0,    100)
    private val intervalTicks  = int("Interval",         1,    0,    20)
    private val cpsMin         = int("CPS Min",          40,   1,    40)
    private val cpsMax         = int("CPS Max",          40,   1,    40)
    private val attempts       = int("Attempts",         10,   1,    10)
    private val boost          = bool("Boost",           true)
    private val boostAmount    = float("Boost Amount",   10f,  1f,   10f)
    private val boostDelay     = float("Boost Delay",    0.1f, 0.1f,  60f)
    private val boostAttempts  = int("Boost Attempts",   20,   1,    20)
    private val rotMode        = enum("Rotation",        RotMode.Rotation)
    private val rotationSpeed  = float("Rotation Speed", 30f,  0f,   30f)
    private val speedJitter    = float("Speed Jitter",   0.08f, 0f,  0.5f)
    private val yawJitter      = float("Yaw Jitter",     0.6f,  0f,  5f)
    private val pitchJitter    = float("Pitch Jitter",   0.3f,  0f,  5f)
    private val osuRots        = bool("Osu Rots",        false)
    private val ignoreFriends  = bool("Ignore Friends",  true)
    private val antiBot        = bool("Anti Bot",        true)
    private val includeMobs    = bool("Mobs",            false)
    private val shortcut       = bool("Shortcut",        false)

    private val critMode = enum("Crit Mode", CritMode.UltraFast)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
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
    @Volatile private var lastTeleportMs = 0L

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
        lastTeleportMs = 0L
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

        // Detect teleport for crit injection conflict
        if (pkt.mode == PlayerAuthInputPacket.Mode.TELEPORT) {
            lastTeleportMs = System.currentTimeMillis()
        }

        val targets = cachedTargets
        val primary = targets.firstOrNull()

        // CENTRALIZED: All rotation logic goes through RotationUtil
        if (rotMode.value == RotMode.Rotation && primary != null) {
            val targetRot = RotationUtil.toEntity(primary)
            var targetYaw = targetRot.yaw
            
            // Osu snapping is just a pre-process step before RotationUtil
            if (osuRots.value) {
                val snap = 36.4f
                targetYaw = Math.round(targetYaw / snap) * snap
            }
            
            val speed = (rotationSpeed.value / 30f).coerceIn(0.02f, 1f)
            val smooth = RotationUtil.smoothTo(
                currentYaw = headLockYaw,
                currentPitch = headLockPitch,
                target = RotationUtil.Rotation(targetYaw, targetRot.pitch),
                baseFactor = speed,
                speedJitter = speedJitter.value,
                yawJitter = yawJitter.value,
                pitchJitter = pitchJitter.value
            )
            headLockYaw = smooth.yaw
            headLockPitch = smooth.pitch
            
            pkt.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            EntityTracker.selfYaw = headLockYaw
            EntityTracker.selfPitch = headLockPitch
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

        val needsCritInjection = critMode.value != CritMode.None &&
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
                        if (Random.nextInt(100) < hitChance.value) {
                            PacketUtil.sendSwing(session)
                            toAttack.forEach { t ->
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
                    if (Random.nextInt(100) < hitChance.value) {
                        PacketUtil.sendSwing(session)
                        toAttack.forEach { t ->
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
        System.currentTimeMillis() - lastTeleportMs < TPAURA_CONFLICT_WINDOW_MS

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

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
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
}
