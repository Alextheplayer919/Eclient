package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.*
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.*
import kotlin.random.Random

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Wide‑rotation orbital aimbot with target lock"
), PacketEventBus.PacketListener {

    enum class HitType   { Single, Multi }
    enum class DelayMode { Interval, Cps }
    enum class RotMode   { None, Instant, Smooth, Silent }   // Silent = server only, no camera snap
    enum class CritMode  { None, Fast, UltraFast }
    enum class StrafeMode { None, Orbit, Manual }            // Orbit = auto‑circles, Manual = you control A/D

    // ── Core settings ──────────────────────────────
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
    private val rotMode        = enum ("Rotation",        RotMode.Smooth)
    private val rotationSpeed  = float("Rotation Speed", 30f,   0f,   30f) // for Smooth
    private val osuRots        = bool ("Osu Rots",       false)            // snap to 36.4° steps
    private val ignoreFriends  = bool ("Ignore Friends",  true)
    private val antiBot        = bool ("Anti Bot",        true)
    private val includeMobs    = bool ("Mobs",           false)
    private val shortcut       = bool ("Shortcut",       false)

    // ── Wide‑rotation / Orbital settings ──────────
    private val strafeMode     = enum ("Strafe Mode",    StrafeMode.Orbit)
    private val orbitRange     = float("Orbit Range",   4.5f,  1f,   12f)   // keep this distance
    private val orbitSpeed     = float("Orbit Speed",   40f,  5f,   120f)   // degrees per tick
    private val approachSpeed  = float("Approach Speed",0.8f, 0.1f, 3f)    // when too far
    private val verticalSpeed  = float("Vertical Speed",1.8f, 0.1f, 4f)

    private val critMode       = enum("Crit Mode", CritMode.UltraFast)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val MOVE_INTERVAL_MS = 20L
    }

    // ── State ──────────────────────────────────────
    @Volatile private var pendingAttacks = 0
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var isBoosting     = false
    @Volatile private var boostTimerMs   = 0L
    @Volatile private var lastBoostMs    = 0L
    @Volatile private var orbitAngle     = 0f
    @Volatile private var lastMoveMs     = 0L

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        pendingAttacks   = 0
        lastAttackMs     = 0L
        lastScanMs       = 0L
        cachedTargets    = emptyList()
        headLockYaw      = EntityTracker.selfYaw
        headLockPitch    = EntityTracker.selfPitch
        isBoosting       = false
        boostTimerMs     = 0L
        lastBoostMs      = System.currentTimeMillis()
        orbitAngle       = Random.nextFloat() * 360f
        lastMoveMs       = 0L
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

        // ── Rotation ────────────────────────────────────
        if (primary != null && rotMode.value != RotMode.None) {
            val rot = RotationUtil.toEntity(primary)
            var targetYaw = rot.yaw
            if (osuRots.value) {
                val snap = 36.4f
                targetYaw = round(targetYaw / snap) * snap
            }
            when (rotMode.value) {
                RotMode.Instant -> {
                    headLockYaw   = targetYaw
                    headLockPitch = rot.pitch
                }
                RotMode.Smooth -> {
                    val f = (rotationSpeed.value / 30f).coerceIn(0.02f, 1f)
                    headLockYaw   = smoothYaw(headLockYaw, targetYaw, f)
                    headLockPitch = smoothPitch(headLockPitch, rot.pitch, f)
                }
                RotMode.Silent -> {
                    headLockYaw   = targetYaw
                    headLockPitch = rot.pitch
                    // Silent: do NOT update EntityTracker.selfYaw/Pitch
                }
                else -> {}
            }
            // Apply rotation to packet (server sees it)
            pkt.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            // If not Silent, also update local camera
            if (rotMode.value != RotMode.Silent) {
                EntityTracker.selfYaw   = headLockYaw
                EntityTracker.selfPitch = headLockPitch
            }
        }

        // ── Wide‑orbit movement (with smooth positioning) ──
        if (primary != null && strafeMode.value != StrafeMode.None) {
            val now = System.currentTimeMillis()
            if (now - lastMoveMs >= MOVE_INTERVAL_MS) {
                lastMoveMs = now
                val session = event.session
                moveOrbit(session, primary)
            }
        }

        // ── Attack logic ──────────────────────────────
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

    // ── Movement: approach + orbit (wide rotations) ──
    private fun moveOrbit(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        // If TPAura is active, don't interfere
        if (tpAuraRecentlyMoved()) return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val tx = target.x
        val ty = target.y
        val tz = target.z

        val dx = tx - selfX
        val dz = tz - selfZ
        val horizDist = sqrt(dx * dx + dz * dz)

        val targetY = ty + orbitRange.value * 0.5f // keep at mid‑height

        val newPos = if (horizDist > orbitRange.value * 1.5f) {
            // Too far → approach smoothly
            val step = approachSpeed.value
            val len = horizDist.coerceAtLeast(0.001f)
            Vector3f.from(
                selfX + dx / len * step,
                selfY + (targetY - selfY).coerceIn(-verticalSpeed.value, verticalSpeed.value),
                selfZ + dz / len * step
            )
        } else {
            // In range → orbit
            when (strafeMode.value) {
                StrafeMode.Orbit -> {
                    orbitAngle += orbitSpeed.value
                    orbitAngle %= 360f
                    val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
                    val r = orbitRange.value
                    Vector3f.from(
                        tx + sin(rad) * r,
                        targetY,
                        tz + cos(rad) * r
                    )
                }
                StrafeMode.Manual -> {
                    // Manual strafe: you control A/D; we keep you at orbitRange
                    // but we do NOT override your lateral movement – only correct distance.
                    // Since we cannot read input, we simply keep distance by moving toward/away.
                    val currentDist = horizDist
                    val desired = orbitRange.value
                    if (abs(currentDist - desired) > 0.3f) {
                        val dirX = dx / horizDist
                        val dirZ = dz / horizDist
                        val move = (desired - currentDist).coerceIn(-approachSpeed.value, approachSpeed.value)
                        Vector3f.from(
                            selfX + dirX * move,
                            selfY + (targetY - selfY).coerceIn(-verticalSpeed.value, verticalSpeed.value),
                            selfZ + dirZ * move
                        )
                    } else {
                        // Already at correct distance – stay
                        Vector3f.from(selfX, selfY, selfZ)
                    }
                }
                else -> Vector3f.from(selfX, selfY, selfZ)
            }
        }

        // Send using TimerPvP (safe split‑step teleport)
        try {
            val yaw = if (rotMode.value != RotMode.None) headLockYaw else EntityTracker.selfYaw
            val pitch = if (rotMode.value != RotMode.None) headLockPitch else EntityTracker.selfPitch
            TimerPvP.dispatch(
                session,
                selfX, selfY, selfZ,
                newPos.x, newPos.y, newPos.z,
                yaw, pitch,
                onGround = false
            )
            TPAura.notifyExternalPositionOverride()
            EntityTracker.selfX = newPos.x
            EntityTracker.selfY = newPos.y
            EntityTracker.selfZ = newPos.z
        } catch (_: Exception) {}
    }

    private fun tpAuraRecentlyMoved(): Boolean =
        System.currentTimeMillis() - TPAura.lastPositionOverrideMs < TPAURA_CONFLICT_WINDOW_MS

    // ── Crit injection ──────────────────────────────
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

    // ── Tick loop ──────────────────────────────────
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

        if (now - lastScanMs >= TARGET_SCAN_INTERVAL) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }

        if (cachedTargets.isEmpty()) return

        lastAttackMs   = now
        pendingAttacks = if (isBoosting) boostAttempts.value else attempts.value
    }

    // ── Target selection ──────────────────────────
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

    // ── Utilities ──────────────────────────────────
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
