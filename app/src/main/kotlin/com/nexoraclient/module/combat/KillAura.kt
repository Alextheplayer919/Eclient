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
    description = "WAura attack + orbit + advanced KillAura2 rotations"
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
    private val orbitRange       = float("Orbit Range",   6f,   1.5f, 25f)
    private val orbitSpeed       = float("Orbit Speed",   8f,   1f,   30f)
    private val orbitMoveSpeed   = float("Move Speed",    2.0f,  0.5f, 6f)
    private val orbitStopDist    = float("Stop Distance", 100f,  10f,  200f)

    // ── Advanced rotation settings (KillAura2) ─────────────
    private val rotMode        = int("Rotation Mode", 1, 0, 3)          // 0=None, 1=Normal, 2=Strafe, 3=Edge
    private val metaRotMode    = int("Meta Rotation", 0, 0, 1)          // 0=Normal, 1=Server-side (stub)
    private val vortexMode     = int("Vortex Mode", 0, 0, 3)            // 0=Off, 1=Counteract, 2=Jitter, 3=Adapt
    private val jitterIntensity= float("Jitter Int", 2.0f, 0f, 10f)
    private val aimSmoothness  = float("Smoothness", 80f, 0f, 100f)
    private val offsetY        = int("Y Offset", 0, -30, 30)
    private val antiKillaura   = bool("Anti KA", false)
    private val antiKARange    = float("Anti KA Range", 2f, 0f, 5f)

    // ── Base settings ──────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val POSITION_TOLERANCE = 0.05f
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

    // Rotation smoothing state
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var strafeAngle = 0f

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
        lastYaw        = EntityTracker.selfYaw
        lastPitch      = EntityTracker.selfPitch
        strafeAngle    = 0f
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

    // ── Advanced rotation calculation (KillAura2 style) ────
    private fun calculateRotation(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        // 1. Base aim position
        val aimPos = Vector3f.from(target.x, target.y + 1.5f, target.z) // eyes
        // (if you want body aim, adjust accordingly – we keep eyes by default)

        // 2. Base rotation
        val rot = RotationUtil.toEntity(target)
        var targetYaw = rot.yaw
        var targetPitch = rot.pitch

        // 3. Y offset
        targetPitch += offsetY.value

        // 4. Rotation modes: Strafe / Edge
        when (rotMode.value) {
            1 -> { /* Normal – nothing extra */ }
            2 -> { // Strafe – circular aim around target
                strafeAngle += 5f
                if (strafeAngle >= 360f) strafeAngle -= 360f
                val rad = Math.toRadians(strafeAngle.toDouble()).toFloat()
                targetYaw += sin(rad) * 5f // amplitude (can be tuned)
            }
            3 -> { // Edge – aim at hitbox corners
                // Simplified: approximate half-width/height
                val halfWidth = 0.3f
                val halfHeight = 0.9f
                strafeAngle += 5f
                if (strafeAngle >= 360f) strafeAngle -= 360f
                val rad = Math.toRadians(strafeAngle.toDouble()).toFloat()
                val offsetX = cos(rad) * halfWidth
                val offsetZ = sin(rad) * halfWidth
                val offsetY = sin(rad * 2) * halfHeight * 0.5f
                val edgePos = Vector3f.from(
                    target.x + offsetX,
                    target.y + 0.2f + offsetY,
                    target.z + offsetZ
                )
                // Use toPoint for coordinate-based rotation calculation
                val edgeRot = RotationUtil.toPoint(edgePos.x, edgePos.y, edgePos.z)
                targetYaw = edgeRot.yaw
                targetPitch = edgeRot.pitch
            }
        }

        // 5. Vortex modes (no target history, simple jitter patterns)
        when (vortexMode.value) {
            1 -> { // Counteract – small random jitter
                val jx = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.5f
                val jy = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.25f
                targetYaw += jx
                targetPitch += jy
            }
            2 -> { // Jitter – larger random offset
                val jx = (Random.nextFloat() * 2 - 1) * jitterIntensity.value
                val jy = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.5f
                targetYaw += jx
                targetPitch += jy
            }
            3 -> { // Adapt – jitter at close range, counteract at distance
                val dist = MathUtil.dist3(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
                    target.x, target.y, target.z)
                if (dist < 4f) {
                    val jx = (Random.nextFloat() * 2 - 1) * jitterIntensity.value
                    val jy = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.5f
                    targetYaw += jx
                    targetPitch += jy
                } else {
                    val jx = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.3f
                    val jy = (Random.nextFloat() * 2 - 1) * jitterIntensity.value * 0.15f
                    targetYaw += jx
                    targetPitch += jy
                }
            }
        }

        // 6. Anti‑Killaura (aim behind target when very close)
        if (antiKillaura.value) {
            val distXZ = MathUtil.dist2(EntityTracker.selfX, EntityTracker.selfZ, target.x, target.z)
            if (distXZ < antiKARange.value) {
                // Aim 180° behind the target
                targetYaw += 180f
                targetPitch *= 0.5f
            }
        }

        // 7. Normalize angles (clamp pitch, wrap yaw)
        var normalizedYaw = targetYaw
        while (normalizedYaw > 180f) normalizedYaw -= 360f
        while (normalizedYaw < -180f) normalizedYaw += 360f
        val normalizedPitch = targetPitch.coerceIn(-89f, 89f)

        // 8. Smoothness (interpolation)
        val smoothFactor = aimSmoothness.value / 100f
        val smoothedYaw = lastYaw + (normalizedYaw - lastYaw) * smoothFactor
        val smoothedPitch = lastPitch + (normalizedPitch - lastPitch) * smoothFactor

        // Store for next tick
        lastYaw = smoothedYaw
        lastPitch = smoothedPitch

        // 9. Apply to packet
        pkt.rotation = Vector3f.from(smoothedPitch, smoothedYaw, smoothedYaw)
        if (!silentRot.value) {
            EntityTracker.selfYaw = smoothedYaw
            EntityTracker.selfPitch = smoothedPitch
        }
        // Also update headLockYaw/Pitch for other uses
        headLockYaw = smoothedYaw
        headLockPitch = smoothedPitch
    }

    // ── Orbit (aggressive distance enforcement) ──────────────
    private fun applyOrbit(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val distToTarget = MathUtil.dist3(selfPos.x, selfPos.y, selfPos.z, target.x, target.y, target.z)

        if (distToTarget > orbitStopDist.value) return

        var strafeInput = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT)) strafeInput = -1f
        else if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) strafeInput = 1f

        if (strafeInput != 0f) {
            orbitAngle += strafeInput * orbitSpeed.value
            orbitAngle %= 360f
        }

        val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
        val desiredX = target.x + cos(rad) * orbitRange.value
        val desiredZ = target.z + sin(rad) * orbitRange.value
        val desiredY = target.y + 0.2f

        val dx = desiredX - selfPos.x
        val dy = desiredY - selfPos.y
        val dz = desiredZ - selfPos.z
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)

        val newPos = if (totalDist <= POSITION_TOLERANCE) {
            Vector3f.from(desiredX, desiredY, desiredZ)
        } else {
            val step = min(orbitMoveSpeed.value, totalDist)
            Vector3f.from(
                selfPos.x + dx / totalDist * step,
                selfPos.y + dy / totalDist * step,
                selfPos.z + dz / totalDist * step
            )
        }

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

        // ── Advanced rotation ──────────────────────────────────
        calculateRotation(primary, pkt)

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
