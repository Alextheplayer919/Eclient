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
import kotlin.math.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Silent aimbot – instant lock"
), PacketEventBus.PacketListener {

    enum class HitType   { Single, Multi }
    enum class DelayMode { Interval, Cps }
    enum class RotMode   { None, Instant, Silent }   // removed Smooth for simplicity
    enum class CritMode  { None, Fast, UltraFast }

    // ── Core settings ──────────────────────────────────
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
    private val rotMode        = enum ("Rotation",        RotMode.Instant)   // INSTANT LOCK
    private val osuRots        = bool ("Osu Rots",       false)              // keep OFF
    private val ignoreFriends  = bool ("Ignore Friends",  true)
    private val antiBot        = bool ("Anti Bot",        true)
    private val includeMobs    = bool ("Mobs",           false)
    private val shortcut       = bool ("Shortcut",       false)

    private val critMode       = enum("Crit Mode", CritMode.UltraFast)

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

        // ── Instant head lock (no smoothing, no tweaking) ──
        if (rotMode.value != RotMode.None && primary != null) {
            val rot = RotationUtil.toEntity(primary)
            var targetYaw = rot.yaw
            if (osuRots.value) {
                val snap = 36.4f
                targetYaw = round(targetYaw / snap) * snap
            }
            // INSTANT assignment – no smooth interpolation
            headLockYaw   = targetYaw
            headLockPitch = rot.pitch
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            
            if (rotMode.value == RotMode.Instant) {
                // Also update camera if not silent
                EntityTracker.selfYaw   = headLockYaw
                EntityTracker.selfPitch = headLockPitch
            }
            // Silent mode: don't update camera
        }

        // ── Prepare attack ──────────────────────────────
        if (pendingAttacks <= 0 || primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        val session = event.session
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val toAttack = if (hitType.value == HitType.Single) listOfNotNull(primary)
                       else targets.take(attempts.value.coerceAtLeast(1))

        val needsCritInjection = critMode.value != CritMode.None &&
                                  !tpAuraRecentlyMoved()

        if (needsCritInjection && EntityTracker.selfSprinting) {
            pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }

        scope.launch {
            if (needsCritInjection) {
                if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                    try {
                        when (critMode.value) {
                            CritMode.Fast      -> injectCritFastUp(session)
                            CritMode.UltraFast -> injectCritUltraFastUp(session)
                            CritMode.None      -> {}
                        }

                        repeat(pendingAttacks) {
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
                    } finally {
                        CritLock.release(CRIT_LOCK_KEY)
                    }
                }
            } else {
                repeat(pendingAttacks) {
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
}
