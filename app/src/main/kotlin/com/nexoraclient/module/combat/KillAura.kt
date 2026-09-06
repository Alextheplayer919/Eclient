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
import kotlin.math.*
import kotlin.random.Random
import java.util.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Full KillAura2 port – bypasses + advanced jitter"
), PacketEventBus.PacketListener {

    enum class RotMode { None, Smooth, Instant, Jitter, Vortex }
    enum class HitType { Single, Multi, Closest }
    enum class AutoWeaponMode { None, BestDamage, BestEnchant }  // etc.
    enum class CritMode { None, Fast, UltraFast }

    // ── Settings ──────────────────────────────────────────────
    private val range           = float("Range",           5f,   1f,   20f)
    private val wallRange       = float("Wall Range",      3f,   0f,   10f)
    private val intervalTicks   = int  ("Interval (ticks)",1,    0,    20)
    private val cps             = int  ("CPS",             20,   1,    50)
    private val rotMode         = enum("Rotation Mode",    RotMode.Smooth)
    private val randomizeRot    = bool("Randomize Rot",    true)
    private val hitType         = enum("Hit Type",         HitType.Multi)
    private val hitAttempts     = int  ("Hit Attempts",    1,    1,    10)
    private val hitChance       = int  ("Hit Chance",      100,  0,    100)
    private val autoWeaponMode  = enum("AutoWeapon",       AutoWeaponMode.BestDamage)
    private val eatStop         = bool("Eat Stop",         false)
    private val includeMobs     = bool("Include Mobs",     false)
    private val hurtTimeCheck   = bool("Hurt Time Check",  true)
    private val caCompatibility = bool("CA Compatibility", true)
    private val packetAttack    = bool("Packet Attack",    false)
    private val ofja            = bool("OFJA",             false)  // old anti-cheat bypass
    private val javaMode        = bool("Java Mode",        false)  // Java edition rotation
    private val packetAmount    = int  ("Packets per hit", 1,    1,    5)
    private val aimPosMode      = int  ("Aim Position",    0,    0,    2)   // 0=head, 1=chest, 2=feet
    private val metaRotMode     = int  ("Meta Rotation",   0,    0,    1)   // 0=normal, 1=server-side
    private val rotMinYaw       = float("Min Yaw",         -180f, -180f, 180f)
    private val rotMaxYaw       = float("Max Yaw",         180f, -180f, 180f)
    private val rotMinPitch     = float("Min Pitch",       -90f, -90f, 90f)
    private val rotMaxPitch     = float("Max Pitch",       90f,  -90f, 90f)

    // ── Jitter / Vortex settings ──────────────────────────────
    private val vortexMode      = int  ("Vortex Mode",     0,    0,    3)   // 0=off, 1=linear, 2=sin, 3=random
    private val jitterIntensity = float("Jitter Int",      2.0f, 0f,   10f)
    private val patternSamples  = int  ("Pattern Samples", 10,   2,    30)
    private val dynamicPred     = bool("Dynamic Pred",     true)

    private val ignoreFriends   = bool("Ignore Friends",   true)
    private val antiBot         = bool("Anti Bot",         true)
    private val critMode        = enum("Crit Mode",        CritMode.UltraFast)
    private val shortcut        = bool("Shortcut",         false)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
        private const val TARGET_SCAN_INTERVAL = 100L
    }

    // ── State ──────────────────────────────────────────────────
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    private var targetHistory = LinkedList<Triple<Float, Float, Float>>()  // x, y, z
    private var lastRealRot = Pair(0f, 0f)
    private var strafeAngle = 0f
    private var attackCounter = 0

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs   = 0L
        lastScanMs     = 0L
        cachedTargets  = emptyList()
        headLockYaw    = EntityTracker.selfYaw
        headLockPitch  = EntityTracker.selfPitch
        targetHistory.clear()
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
        val raw = EntityTracker.getEntitiesInRange(range.value + wallRange.value)

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
            !includeMobs.value && !isPlayer -> false
            else -> true
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    // ── AutoWeapon ──────────────────────────────────────────
    private fun getBestWeaponSlot(target: EntityTracker.TrackedEntity?): Int {
        if (autoWeaponMode.value == AutoWeaponMode.None) {
            return EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        }
        // Placeholder – you need to implement actual item damage comparison.
        // For now, return current slot.
        return EntityTracker.selfHotbarSlot.coerceIn(0, 8)
    }

    // ── Rotation with clamping + jitter + vortex ───────────
    private fun calcRotation(target: EntityTracker.TrackedEntity): Pair<Float, Float> {
        val rot = RotationUtil.toEntity(target)
        var baseYaw = rot.yaw
        var basePitch = rot.pitch

        // Apply aim position offset
        when (aimPosMode) {
            1 -> basePitch += 0.5f   // chest
            2 -> basePitch += 1.5f   // feet
        }

        // Update target history for dynamic prediction
        if (dynamicPred.value) {
            targetHistory.addLast(Triple(target.x, target.y, target.z))
            if (targetHistory.size > patternSamples.value) {
                targetHistory.removeFirst()
            }
        }

        // Rotation mode
        when (rotMode.value) {
            RotMode.None -> {
                return Pair(headLockYaw, headLockPitch)
            }
            RotMode.Instant -> {
                headLockYaw = baseYaw
                headLockPitch = basePitch
            }
            RotMode.Smooth -> {
                val f = 0.3f
                headLockYaw = smoothYaw(headLockYaw, baseYaw, f)
                headLockPitch = smoothPitch(headLockPitch, basePitch, f)
            }
            RotMode.Jitter -> {
                // Instant + random jitter
                val jY = (Random.nextFloat() - 0.5f) * jitterIntensity.value
                val jP = (Random.nextFloat() - 0.5f) * jitterIntensity.value * 0.5f
                headLockYaw = baseYaw + jY
                headLockPitch = (basePitch + jP).coerceIn(-90f, 90f)
            }
            RotMode.Vortex -> {
                // Pattern-based jitter
                val pattern = when (vortexMode) {
                    1 -> sin(attackCounter * 0.1f) * jitterIntensity.value
                    2 -> sin(attackCounter * 0.05f) * jitterIntensity.value * 0.5f
                    3 -> (Random.nextFloat() - 0.5f) * jitterIntensity.value * 2f
                    else -> 0f
                }
                headLockYaw = baseYaw + pattern
                headLockPitch = (basePitch + pattern * 0.3f).coerceIn(-90f, 90f)
            }
        }

        // Randomize (extra jitter)
        if (randomizeRot.value && rotMode.value != RotMode.Jitter && rotMode.value != RotMode.Vortex) {
            headLockYaw += (Random.nextFloat() - 0.5f) * 1.0f
            headLockPitch += (Random.nextFloat() - 0.5f) * 0.5f
        }

        // Clamp to user-defined limits
        headLockYaw = headLockYaw.coerceIn(rotMinYaw.value, rotMaxYaw.value)
        headLockPitch = headLockPitch.coerceIn(rotMinPitch.value, rotMaxPitch.value)

        // Java mode: adjust yaw to Java-style (optional)
        if (javaMode.value) {
            headLockYaw = (headLockYaw % 360f + 360f) % 360f
        }

        return Pair(headLockYaw, headLockPitch)
    }

    private fun smoothYaw(cur: Float, tgt: Float, f: Float): Float {
        var d = tgt - cur
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return cur + d * f
    }

    private fun smoothPitch(cur: Float, tgt: Float, f: Float): Float {
        return (cur + (tgt - cur) * f).coerceIn(-90f, 90f)
    }

    // ── Attack logic ─────────────────────────────────────────
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val targets = cachedTargets
        if (targets.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Select target(s) ─────────────────────────────
        val primary = when (hitType.value) {
            HitType.Single -> targets.firstOrNull()
            HitType.Closest -> targets.minByOrNull {
                MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            }
            HitType.Multi -> null
        }

        val targetToAim = primary ?: targets.firstOrNull()
        if (targetToAim != null) {
            // Update rotation
            val (yaw, pitch) = calcRotation(targetToAim)
            pkt.rotation = Vector3f.from(pitch, yaw, yaw)
            EntityTracker.selfYaw = yaw
            EntityTracker.selfPitch = pitch
        }

        // ── Attack timing ──────────────────────────────
        val delay = if (intervalTicks.value > 0) intervalTicks.value * 50L else 1000L / cps.value
        if (System.currentTimeMillis() - lastAttackMs < delay) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Build target list ──────────────────────────
        val targetsToHit = when (hitType.value) {
            HitType.Single, HitType.Closest -> listOfNotNull(primary)
            HitType.Multi -> targets
        }

        val inRange = targetsToHit.filter { isInRange(it) }
        if (inRange.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Crit injection ─────────────────────────────
        val needsCrit = critMode.value != CritMode.None && !tpAuraRecentlyMoved()
        if (needsCrit && EntityTracker.selfSprinting) {
            pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }

        val session = event.session
        val slot = getBestWeaponSlot(primary)

        // ── Hurt time check ────────────────────────────
        val validTargets = if (hurtTimeCheck.value) {
            inRange.filter { it.hurtTime <= 0 }
        } else {
            inRange
        }
        if (validTargets.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Eat stop ────────────────────────────────────
        if (eatStop.value && EntityTracker.selfEating) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── CA Compatibility ────────────────────────────
        if (caCompatibility.value) {
            // maybe adjust timing to avoid conflict with CrystalAura
        }

        val attackCount = if (packetAttack.value) packetAmount.value else 1
        val hitChanceVal = hitChance.value

        scope.launch {
            if (needsCrit) {
                if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                    try {
                        when (critMode.value) {
                            CritMode.Fast      -> injectCritFastUp(session)
                            CritMode.UltraFast -> injectCritUltraFastUp(session)
                            CritMode.None      -> {}
                        }

                        repeat(hitAttempts.value) {
                            if (!packetAttack.value) {
                                PacketUtil.sendSwing(session)
                            }
                            validTargets.forEach { t ->
                                if (Random.nextInt(100) < hitChanceVal) {
                                    repeat(attackCount) {
                                        val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                                        PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                                    }
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
                repeat(hitAttempts.value) {
                    if (!packetAttack.value) {
                        PacketUtil.sendSwing(session)
                    }
                    validTargets.forEach { t ->
                        if (Random.nextInt(100) < hitChanceVal) {
                            repeat(attackCount) {
                                val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                                PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                            }
                        }
                    }
                }
            }
        }

        lastAttackMs = System.currentTimeMillis()
        attackCounter++
        event.cancelAndReplace(pkt)
    }

    private fun isInRange(target: EntityTracker.TrackedEntity): Boolean {
        val dist = MathUtil.dist3(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
            target.x, target.y, target.z)
        return dist <= range.value || (dist <= range.value + wallRange.value)
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
}
