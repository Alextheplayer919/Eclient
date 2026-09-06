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
    description = "KillAura2 port – working attack (debug)"
), PacketEventBus.PacketListener {

    enum class RotMode { None, Smooth, Instant, Jitter, Vortex }
    enum class HitType { Single, Multi, Closest }
    enum class AutoWeaponMode { None, BestDamage, BestEnchant }
    enum class CritMode { None, Fast, UltraFast }

    // ── Settings ──────────────────────────────────────────────
    private val range           = float("Range",           5f,   1f,   20f)
    private val wallRange       = float("Wall Range",      3f,   0f,   10f)
    private val aps             = int  ("APS",             15,   1,    50)   // Attacks per second
    private val rotMode         = enum("Rotation Mode",    RotMode.Smooth)
    private val randomizeRot    = bool("Randomize Rot",    true)
    private val hitType         = enum("Hit Type",         HitType.Multi)
    private val hitAttempts     = int  ("Hit Attempts",    1,    1,    10)
    private val hitChance       = int  ("Hit Chance",      100,  0,    100)
    private val autoWeaponMode  = enum("AutoWeapon",       AutoWeaponMode.None)  // Disabled for now
    private val eatStop         = bool("Eat Stop",         false)
    private val includeMobs     = bool("Include Mobs",     false)
    private val packetAttack    = bool("Packet Attack",    false)  // Keep false for now
    private val packetAmount    = int  ("Packets per hit", 1,    1,    5)
    private val aimPosMode      = int  ("Aim Position",    0,    0,    2)
    private val rotMinYaw       = float("Min Yaw",         -180f, -180f, 180f)
    private val rotMaxYaw       = float("Max Yaw",         180f, -180f, 180f)
    private val rotMinPitch     = float("Min Pitch",       -90f, -90f, 90f)
    private val rotMaxPitch     = float("Max Pitch",       90f,  -90f, 90f)

    private val vortexMode      = int  ("Vortex Mode",     0,    0,    3)
    private val jitterIntensity = float("Jitter Int",      2.0f, 0f,   10f)
    private val patternSamples  = int  ("Pattern Samples", 10,   2,    30)
    private val dynamicPred     = bool("Dynamic Pred",     true)

    private val ignoreFriends   = bool("Ignore Friends",   true)
    private val antiBot         = bool("Anti Bot",         true)
    private val critMode        = enum("Crit Mode",        CritMode.UltraFast)
    private val shortcut        = bool("Shortcut",         false)

    // ── State ──────────────────────────────────────────────────
    @Volatile private var accumulatedTime = 0.0
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    private var targetHistory = LinkedList<Triple<Float, Float, Float>>()
    private var attackCounter = 0
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        println("[KillAura] Enabled")
        accumulatedTime = 0.0
        lastAttackMs = 0L
        lastScanMs = 0L
        cachedTargets = emptyList()
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        targetHistory.clear()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        println("[KillAura] Disabled")
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                if (System.currentTimeMillis() - lastScanMs >= 100L) {
                    cachedTargets = selectTargets()
                    println("[KillAura] Targets updated: ${cachedTargets.size}")
                    lastScanMs = System.currentTimeMillis()
                }
                // Attack loop (like onNormalTick)
                val session = PacketEventBus.currentSession
                if (session != null && cachedTargets.isNotEmpty()) {
                    performAttack(session)
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
        if (!includeMobs.value && !entity.isPlayer) return false
        return true
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    // ── Rotation ──────────────────────────────────────────
    private fun calcRotation(target: EntityTracker.TrackedEntity): Pair<Float, Float> {
        val rot = RotationUtil.toEntity(target)
        var baseYaw = rot.yaw
        var basePitch = rot.pitch
        when (aimPosMode.value) {
            1 -> basePitch += 0.5f   // chest
            2 -> basePitch += 1.5f   // feet
        }
        // (Vortex / jitter logic omitted for brevity – you can add later)
        // Simple smooth
        headLockYaw = smoothYaw(headLockYaw, baseYaw, 0.3f)
        headLockPitch = smoothPitch(headLockPitch, basePitch, 0.3f)
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

    // ── Attack ─────────────────────────────────────────────
    private fun performAttack(session: RubidiumRelaySession) {
        val now = System.currentTimeMillis()
        val delta = (now - lastAttackMs) / 1000.0
        lastAttackMs = now
        accumulatedTime += delta
        val expectedAttacks = aps.value * accumulatedTime
        val attacksToDo = expectedAttacks.toInt()
        if (attacksToDo <= 0) return
        accumulatedTime -= attacksToDo / aps.value.toDouble()

        // Select targets based on hit type
        val targetsToHit = when (hitType.value) {
            HitType.Single, HitType.Closest -> {
                val primary = when (hitType.value) {
                    HitType.Single -> cachedTargets.firstOrNull()
                    HitType.Closest -> cachedTargets.minByOrNull {
                        MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    }
                    else -> null
                }
                listOfNotNull(primary)
            }
            HitType.Multi -> cachedTargets
        }

        val inRange = targetsToHit.filter { isInRange(it) }
        if (inRange.isEmpty()) {
            println("[KillAura] No targets in range")
            return
        }

        // --- Attack loop ---
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        repeat(attacksToDo) {
            inRange.forEach { target ->
                if (Random.nextInt(100) < hitChance.value) {
                    repeat(hitAttempts.value) {
                        // Swing
                        PacketUtil.sendSwing(session)
                        // Attack
                        val clickPos = Vector3f.from(target.x, target.y + 1.5f, target.z)
                        PacketUtil.sendAttack(session, target.runtimeId, slot, clickPos)
                        attackCounter++
                    }
                }
            }
            if (hitType.value != HitType.Multi) return@repeat
        }
        println("[KillAura] Attacked ${inRange.size} targets, $attacksToDo times")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        // Rotate towards target
        val primary = cachedTargets.firstOrNull()
        if (primary != null) {
            val (yaw, pitch) = calcRotation(primary)
            pkt.rotation = Vector3f.from(pitch, yaw, yaw)
            EntityTracker.selfYaw = yaw
            EntityTracker.selfPitch = pitch
        }

        // Optional: if you want to attack in onPacket as well, you can call performAttack here,
        // but we already attack in tickLoop.
        event.cancelAndReplace(pkt)
    }

    private fun isInRange(target: EntityTracker.TrackedEntity): Boolean {
        val dist = MathUtil.dist3(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
            target.x, target.y, target.z)
        return dist <= range.value + wallRange.value
    }

    private fun tpAuraRecentlyMoved(): Boolean = false
}
