package com.rubidiumclient.module.combat

import com.rubidiumclient.BuildConfig
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.*
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────
private enum class AttackMode { CPS, INTERVAL }
private enum class TargetMode { SINGLE, SWITCH, MULTI }

private enum class RotationMode {
    NONE, AIM, RANDOM, SOFT_LOCK,
    TARGET_LOCK,   // legacy teleport orbit — gated by build flag
    ORBIT          // new non-teleport orbit — gated by build flag
}

private enum class QuantumAlgo { DYNAMIC, VELOCITY, PATTERN, NEURAL }
private enum class WeaponSwitchMode { NONE, FULL, SILENT }

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Melody-style KillAura: Aim/Random rotations, Target Lock, Orbit, Quantum prediction, silent weapon switch"
), PacketEventBus.PacketListener {

    override val priority: Int = 200

    // ── Attack settings ────────────────────────────────────
    private val attackMode     = enum("Attack Mode", AttackMode.CPS)
    private val cps            = int("CPS",          20, 1, 100)
    private val intervalTicks  = int("Interval",      1, 0, 20)
    private val boost          = int("Packets",      2, 1, 10)
    private val hitAttempts    = int("Hit Attempts", 1, 1, 5)
    private val packetAttack   = bool("Packet Attack", false)
    private val hitChance      = int("Hit Chance",   100, 0, 100)

    // ── Range ──────────────────────────────────────────────
    private val playersOnly   = bool("Players Only", true)
    private val mobsOnly      = bool("Mobs Only",    false)
    private val range         = float("Range",       50f,  2f,  50f)
    private val wallRange     = float("Wall Range",  3f,   0f,  10f)

    // ── Target selection ───────────────────────────────────
    private val targetMode    = enum("Target Mode", TargetMode.MULTI)
    private val switchDelay   = int("Switch Delay",100,  20,  1000)

    // ── Rotation ───────────────────────────────────────────
    // The available values depend on BuildConfig.ENABLE_ORBIT_MODE:
    //   new build  → ORBIT available, TARGET_LOCK hidden
    //   legacy build → TARGET_LOCK available, ORBIT hidden
    private val rotMode = enum(
        "Rotation Mode",
        RotationMode.AIM,
        values = RotationMode.values().filter { mode ->
            when (mode) {
                RotationMode.ORBIT       -> BuildConfig.ENABLE_ORBIT_MODE
                RotationMode.TARGET_LOCK -> !BuildConfig.ENABLE_ORBIT_MODE
                else -> true
            }
        }.toTypedArray()
    )

    // ── Legacy Target Lock settings (shown only when TARGET_LOCK active) ──
    private val lockDistance = float("Lock Distance", 3f, 0.5f, 7f)
        .visibleWhen { rotMode.value == RotationMode.TARGET_LOCK }
    private val lockSpeed    = float("Lock Speed",    4.3f, 0.5f, 12f)
        .visibleWhen { rotMode.value == RotationMode.TARGET_LOCK }

    // ── Orbit settings (shown only when ORBIT active) ──
    private val orbitRadius    = float("Orbit Radius",  4.5f, 2f, 8f)
        .visibleWhen { rotMode.value == RotationMode.ORBIT }
    private val orbitSpeed     = float("Orbit Speed",   8.0f, 1f, 20f)
        .visibleWhen { rotMode.value == RotationMode.ORBIT }
    private val orbitStiffness = int("Orbit Stiffness", 70, 10, 100)
        .visibleWhen { rotMode.value == RotationMode.ORBIT }
    private val orbitWhileIdle = bool("Orbit While Idle", true)
        .visibleWhen { rotMode.value == RotationMode.ORBIT }

    // ── Soft Lock ──
    private val softLockStrength = int("Soft Lock Strength", 70, 0, 100)
        .visibleWhen { rotMode.value == RotationMode.SOFT_LOCK }
    private val softLockRotSpeed = float("Soft Lock Rot Speed", 30f, 2f, 180f)
        .visibleWhen { rotMode.value == RotationMode.SOFT_LOCK }

    // ── Weapon switch ──────────────────────────────────────
    private val weaponSwitch   = enum("Weapon Switch", WeaponSwitchMode.NONE)
    private val hurtTimeCheck  = bool("Hurttime Check", true)

    // ── Quantum ────────────────────────────────────────────
    private val quantum        = bool("Quantum",          false)
    private val quantumAlgo    = enum("Q-Algorithm", QuantumAlgo.DYNAMIC)
        .visibleWhen { quantum.value }
    private val quantumStrength= float("Q-Strength",      1.5f, 0f,  5f)
        .visibleWhen { quantum.value }
    private val quantumHistory = int ("Q-History",        10,   3,   30)
        .visibleWhen { quantum.value }

    // ── Base ───────────────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val MIN_LOCK_RADIUS = 2.0f
    }

    // ── State ──────────────────────────────────────────────
    @Volatile private var lastAttackNs   = 0L
    @Volatile private var lastSwitchMs   = 0L
    @Volatile private var switchIndex    = 0
    @Volatile private var currentTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastScanMs     = 0L

    private var rotAngle = Pair(0f, 0f)
    private var shouldRot = false
    private var softYaw = 0f
    private var softPitch = 0f
    private var lastSoftTarget: EntityTracker.TrackedEntity? = null

    // ── Orbit / Lock shared state ──
    private var lastLockTarget: EntityTracker.TrackedEntity? = null
    private var lockLastTickMs = 0L
    private var lockRadius     = 0f
    private var lockSpinDir    = 1f     // +1 CCW, -1 CW — persisted across ticks

    private var randomYawOffset = 0f
    private var randomPitchOffset = 0f
    private var lastRandomTarget: EntityTracker.TrackedEntity? = null

    private var positionHistory = mutableListOf<Vector3f>()
    private var velocityHistory = mutableListOf<Vector3f>()
    private var lastQuantumTarget: EntityTracker.TrackedEntity? = null
    private var quantumConfidence = 1.0f

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackNs   = 0L
        lastSwitchMs   = 0L
        switchIndex    = 0
        currentTarget  = null
        cachedTargets  = emptyList()
        lastScanMs     = 0L
        positionHistory.clear()
        velocityHistory.clear()
        lastQuantumTarget = null
        quantumConfidence = 1.0f
        lastLockTarget = null
        lockLastTickMs = 0L
        lockRadius = 0f
        lockSpinDir = 1f
        randomYawOffset = 0f
        randomPitchOffset = 0f
        lastRandomTarget = null
        lastSoftTarget = null
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        positionHistory.clear()
        velocityHistory.clear()
        lastQuantumTarget = null
        lastLockTarget = null
        lockLastTickMs = 0L
        lockRadius = 0f
        lockSpinDir = 1f
        lastSoftTarget = null
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

    // ── Target selection ────────────────────────────────────
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
            playersOnly.value && mobsOnly.value -> false
            playersOnly.value && !isPlayer -> false
            mobsOnly.value && isPlayer -> false
            else -> true
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    // ── Weapon scoring ──────────────────────────────────────
    private val WEAPON_DAMAGE: Map<String, Float> = mapOf(
        "netherite_sword" to 8f, "diamond_sword" to 7f,  "iron_sword" to 6f,
        "stone_sword"     to 5f, "wooden_sword"  to 4f,  "golden_sword" to 4f,
        "netherite_axe"   to 10f, "diamond_axe"  to 9f,   "iron_axe" to 9f,
        "stone_axe"       to 9f,  "wooden_axe"   to 7f,   "golden_axe" to 7f,
        "mace"            to 6f,  "trident"      to 9f,
        "netherite_pickaxe" to 6f, "diamond_pickaxe" to 5f, "iron_pickaxe" to 4f,
        "stone_pickaxe"   to 3f,  "wooden_pickaxe"  to 2f, "golden_pickaxe" to 2f
    )

    private fun bestWeaponSlot(): Int {
        var best = -1
        var bestDmg = 0f
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = InventoryUtil.resolveIdentifier(item)
                ?.removePrefix("minecraft:") ?: continue
            val dmg = WEAPON_DAMAGE[id] ?: continue
            if (dmg > bestDmg) { bestDmg = dmg; best = slot }
        }
        return best
    }

    // ── Quantum (unchanged) ─────────────────────────────────
    private fun predictWithQuantum(target: EntityTracker.TrackedEntity, currentPos: Vector3f): Vector3f {
        if (!quantum.value || lastQuantumTarget != target) {
            positionHistory.clear()
            velocityHistory.clear()
            lastQuantumTarget = target
            quantumConfidence = 1.0f
            return currentPos
        }

        positionHistory.add(currentPos)
        if (positionHistory.size > quantumHistory.value) {
            positionHistory.removeAt(0)
        }

        if (positionHistory.size >= 2) {
            val latest = positionHistory.last()
            val prev = positionHistory[positionHistory.size - 2]
            val vel = Vector3f.from(latest.x - prev.x, latest.y - prev.y, latest.z - prev.z)
            velocityHistory.add(vel)
            if (velocityHistory.size > quantumHistory.value - 1) {
                velocityHistory.removeAt(0)
            }
        }

        if (positionHistory.size < 3) {
            return currentPos
        }

        return when (quantumAlgo.value) {
            QuantumAlgo.DYNAMIC  -> predictDynamic(currentPos)
            QuantumAlgo.VELOCITY -> predictAdvancedVelocity(currentPos)
            QuantumAlgo.PATTERN  -> predictPatternBased(currentPos)
            QuantumAlgo.NEURAL   -> predictNeural(currentPos)
        }
    }

    private fun predictDynamic(currentPos: Vector3f): Vector3f {
        if (velocityHistory.isEmpty()) return currentPos
        var weightedVel = Vector3f.from(0f, 0f, 0f)
        var totalWeight = 0f
        for (i in velocityHistory.indices) {
            val weight = (i + 1).toFloat() / velocityHistory.size
            val vel = velocityHistory[i]
            weightedVel = weightedVel.add(Vector3f.from(vel.x * weight, vel.y * weight, vel.z * weight))
            totalWeight += weight
        }
        if (totalWeight > 0f) {
            weightedVel = Vector3f.from(
                weightedVel.x / totalWeight, weightedVel.y / totalWeight, weightedVel.z / totalWeight)
        }
        if (velocityHistory.size >= 3) {
            val last = velocityHistory.last()
            val thirdLast = velocityHistory[velocityHistory.size - 3]
            val accel = Vector3f.from(
                (last.x - thirdLast.x) * 0.3f,
                (last.y - thirdLast.y) * 0.3f,
                (last.z - thirdLast.z) * 0.3f)
            weightedVel = weightedVel.add(accel)
        }
        return currentPos.add(Vector3f.from(
            weightedVel.x * quantumStrength.value * 1.5f,
            weightedVel.y * quantumStrength.value * 1.5f,
            weightedVel.z * quantumStrength.value * 1.5f))
    }

    private fun predictAdvancedVelocity(currentPos: Vector3f): Vector3f {
        if (velocityHistory.size < 2) return currentPos
        var avgVel = Vector3f.from(0f, 0f, 0f)
        for (vel in velocityHistory) avgVel = avgVel.add(vel)
        avgVel = Vector3f.from(
            avgVel.x / velocityHistory.size,
            avgVel.y / velocityHistory.size,
            avgVel.z / velocityHistory.size)
        return currentPos.add(Vector3f.from(
            avgVel.x * quantumStrength.value * 2.0f,
            avgVel.y * quantumStrength.value * 2.0f,
            avgVel.z * quantumStrength.value * 2.0f))
    }

    private fun predictPatternBased(currentPos: Vector3f): Vector3f {
        if (positionHistory.size < 5) return currentPos
        return predictDynamic(currentPos)
    }

    private fun predictNeural(currentPos: Vector3f): Vector3f {
        if (velocityHistory.size < 3) return currentPos
        val recent = velocityHistory.last()
        val recentMomentum = Vector3f.from(recent.x * 0.4f, recent.y * 0.4f, recent.z * 0.4f)
        var avgLen = 0f
        var avgDir = Vector3f.from(0f, 0f, 0f)
        for (vel in velocityHistory) {
            val len = sqrt(vel.x * vel.x + vel.y * vel.y + vel.z * vel.z)
            avgLen += len
            if (len > 0f) avgDir = avgDir.add(Vector3f.from(vel.x / len, vel.y / len, vel.z / len))
        }
        avgLen /= velocityHistory.size.toFloat()
        val avgDirection = Vector3f.from(
            avgDir.x * avgLen * 0.3f, avgDir.y * avgLen * 0.3f, avgDir.z * avgLen * 0.3f)
        val noise = Vector3f.from(
            (Random.nextFloat() * 2 - 1) * 0.05f,
            (Random.nextFloat() * 2 - 1) * 0.02f,
            (Random.nextFloat() * 2 - 1) * 0.05f)
        val total = recentMomentum.add(avgDirection).add(noise)
        return currentPos.add(Vector3f.from(
            total.x * quantumStrength.value,
            total.y * quantumStrength.value,
            total.z * quantumStrength.value))
    }

    // ── AIM (unchanged) ─────────────────────────────────────
    private fun calculateRotationKillAura3(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        var aimPos = Vector3f.from(target.x, target.y, target.z)
        aimPos = Vector3f.from(aimPos.x, target.y + 0.5f, aimPos.z)
        if (quantum.value) {
            val currentPos = aimPos
            aimPos = predictWithQuantum(target, currentPos)
            if (quantumConfidence < 0.5f) {
                aimPos = Vector3f.from(aimPos.x, target.y + 0.5f, aimPos.z)
            }
        }
        val rot = RotationUtil.toPoint(aimPos.x, aimPos.y, aimPos.z)
        rotAngle = Pair(rot.pitch, rot.yaw)
        shouldRot = true
    }

    // ── RANDOM (unchanged) ──────────────────────────────────
    private fun applyRandomRotation(target: EntityTracker.TrackedEntity) {
        val dx = target.x - EntityTracker.selfX
        val dz = target.z - EntityTracker.selfZ
        val targetYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        if (lastRandomTarget != target) {
            randomYawOffset = 0f
            randomPitchOffset = 0f
            lastRandomTarget = target
        }
        val bigJolt = Random.nextFloat() < 0.08f
        val yawStep = Random.nextFloat() * (if (bigJolt) 16f else 4f)
        randomYawOffset = (randomYawOffset + if (Random.nextBoolean()) yawStep else -yawStep)
        val pitchStep = Random.nextFloat() * (if (bigJolt) 8f else 2f)
        randomPitchOffset = (randomPitchOffset + if (Random.nextBoolean()) pitchStep else -pitchStep)
        randomYawOffset   = randomYawOffset.coerceIn(-40f, 40f)
        randomPitchOffset = randomPitchOffset.coerceIn(-15f, 15f)
        if (Random.nextFloat() < 0.05f) {
            randomYawOffset   *= 0.5f
            randomPitchOffset *= 0.5f
        }
        val pitch = (EntityTracker.selfPitch + randomPitchOffset).coerceIn(-90f, 90f)
        rotAngle = Pair(pitch, wrapYaw(targetYaw + randomYawOffset))
        shouldRot = true
    }

    // ── SOFT LOCK (unchanged) ───────────────────────────────
    private fun applySoftLock(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val px = pkt.position.x
        val pz = pkt.position.z
        val prevX = EntityTracker.selfX
        val prevZ = EntityTracker.selfZ
        val dx = px - prevX
        val dz = pz - prevZ
        val speed = sqrt(dx * dx.toDouble() + dz * dz.toDouble()).toFloat()
        val inFwd = pkt.motion.y
        val inStr = pkt.motion.x
        val hasMovementInput = abs(inFwd) > 0.05f || abs(inStr) > 0.05f
        if (hasMovementInput && speed > 0.005f) {
            val ox = px - target.x
            val oz = pz - target.z
            val r = sqrt(ox * ox.toDouble() + oz * oz.toDouble()).toFloat().coerceAtLeast(0.5f)
            val outX = ox / r; val outZ = oz / r
            val tanX = -outZ;  val tanZ = outX
            val vr = -inFwd
            val vt = inStr
            val nx = (vr * outX + vt * tanX) * speed
            val nz = (vr * outZ + vt * tanZ) * speed
            val s = softLockStrength.value / 100f
            val fx = dx * (1f - s) + nx * s
            val fz = dz * (1f - s) + nz * s
            pkt.position = Vector3f.from(prevX + fx, pkt.position.y, prevZ + fz)
        }
        val rot = RotationUtil.toPoint(target.x, target.y + 1.5f, target.z)
        val targetYaw = rot.yaw
        val targetPitch = rot.pitch
        if (lastSoftTarget != target) {
            lastSoftTarget = target
            softYaw = EntityTracker.selfYaw
            softPitch = EntityTracker.selfPitch
        }
        val maxStep = softLockRotSpeed.value
        val dy = wrapYaw(targetYaw - softYaw).coerceIn(-maxStep, maxStep)
        val dp = (targetPitch - softPitch).coerceIn(-maxStep * 0.5f, maxStep * 0.5f)
        softYaw = wrapYaw(softYaw + dy)
        softPitch = (softPitch + dp).coerceIn(-90f, 90f)
        rotAngle = Pair(softPitch, softYaw)
        shouldRot = true
    }

    // ─────────────────────────────────────────────────────────
    // LEGACY TARGET LOCK — teleport orbit
    // Compiled in only when BuildConfig.ENABLE_ORBIT_MODE == false.
    // Kept intact so a one-flag rollback restores known behavior.
    // ─────────────────────────────────────────────────────────
    private fun applyTargetLock(
        target: EntityTracker.TrackedEntity,
        pkt: PlayerAuthInputPacket,
        session: RubidiumRelaySession
    ) {
        val nowMs = System.currentTimeMillis()
        if (lastLockTarget != target || lockLastTickMs <= 0L) {
            lastLockTarget = target
            lockLastTickMs = nowMs
            lockRadius = 0f
        }
        if (lockRadius <= 0f) lockRadius = lockDistance.value.coerceAtLeast(MIN_LOCK_RADIUS)

        val dt = (nowMs - lockLastTickMs).coerceIn(0L, 250L) / 1000f
        val radiusMin = minOf(MIN_LOCK_RADIUS, lockDistance.value)
        val fwdIn = pkt.motion.y
        when {
            fwdIn > 0.25f  -> lockRadius = (lockRadius - lockSpeed.value * 0.5f * dt).coerceAtLeast(radiusMin)
            fwdIn < -0.25f -> lockRadius = (lockRadius + lockSpeed.value * 0.5f * dt).coerceAtMost(lockDistance.value.coerceAtLeast(radiusMin))
        }

        var spinDir = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT)) spinDir = 1f
        else if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) spinDir = -1f

        if (spinDir != 0f) {
            val step = (lockSpeed.value / lockRadius) * dt
            val angle = atan2(EntityTracker.selfZ - target.z, EntityTracker.selfX - target.x)
            val nextAngle = angle + spinDir * step
            val nx = target.x + lockRadius * cos(nextAngle)
            val nz = target.z + lockRadius * sin(nextAngle)
            val ny = pkt.position.y
            pkt.position = Vector3f.from(nx, ny, nz)
            EntityTracker.selfX = nx
            EntityTracker.selfY = ny
            EntityTracker.selfZ = nz
            PacketUtil.sendMove(
                session, nx, ny, nz,
                EntityTracker.selfYaw, EntityTracker.selfPitch,
                onGround = true, teleport = true, mirrorToClient = true
            )
        }

        lockLastTickMs = nowMs

        val dx = target.x - EntityTracker.selfX
        val dz = target.z - EntityTracker.selfZ
        val targetYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        rotAngle = Pair(EntityTracker.selfPitch, wrapYaw(targetYaw))
        shouldRot = true
    }

    // ─────────────────────────────────────────────────────────
    // ORBIT — non-teleport orbit
    // Compiled in only when BuildConfig.ENABLE_ORBIT_MODE == true.
    //
    //   • Automatic orbit at orbitRadius when LEFT/RIGHT held.
    //   • LEFT = counter-clockwise, RIGHT = clockwise.
    //   • No input → holds radius, still faces target, still attacks.
    //   • FORWARD/BACK retunes orbitRadius itself (persistent).
    //   • Radial correction keeps standoff; stiffer when too close
    //     so a walking target can't drag you into face-hug.
    //   • Position applied as a walking-scale delta, never a set.
    // ─────────────────────────────────────────────────────────
    private fun applyOrbit(
        target: EntityTracker.TrackedEntity,
        pkt: PlayerAuthInputPacket,
        session: RubidiumRelaySession
    ) {
        val nowMs = System.currentTimeMillis()
        if (lastLockTarget != target || lockLastTickMs <= 0L) {
            lastLockTarget = target
            lockLastTickMs = nowMs
            lockRadius = orbitRadius.value.coerceAtLeast(MIN_LOCK_RADIUS)
        }
        val dt = (nowMs - lockLastTickMs).coerceIn(0L, 250L) / 1000f
        lockLastTickMs = nowMs

        // Current geometry
        val ox = EntityTracker.selfX - target.x
        val oz = EntityTracker.selfZ - target.z
        val r  = sqrt(ox * ox + oz * oz).coerceAtLeast(0.5f)
        val outX = ox / r; val outZ = oz / r
        val tanX = -outZ;  val tanZ = outX   // 90° CCW

        // FORWARD/BACK retunes the persistent orbit radius
        val radiusMin = maxOf(MIN_LOCK_RADIUS, 0.5f)
        val radiusMax = orbitRadius.value.coerceAtLeast(radiusMin)
        val fwdIn = pkt.motion.y
        when {
            fwdIn >  0.25f -> lockRadius = (lockRadius - orbitSpeed.value * 0.5f * dt).coerceAtLeast(radiusMin)
            fwdIn < -0.25f -> lockRadius = (lockRadius + orbitSpeed.value * 0.5f * dt).coerceAtMost(radiusMax)
        }

        // LEFT/RIGHT: orbit direction. Held = circle; released = hold.
        // If orbitWhileIdle is on, default direction is CCW when no input.
        val spinDir = when {
            pkt.inputData.contains(PlayerAuthInputData.LEFT)  ->  1f
            pkt.inputData.contains(PlayerAuthInputData.RIGHT) -> -1f
            orbitWhileIdle.value                              ->  lockSpinDir
            else                                              ->  0f
        }
        // Remember last held direction so orbitWhileIdle resumes smoothly
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT))  lockSpinDir =  1f
        if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) lockSpinDir = -1f

        // Radial correction — stiffer when too close (anti-face-hug)
        val stiffness = orbitStiffness.value / 100f
        val radialError = r - lockRadius
        val radialGain  = (if (radialError < 0f) 1.4f else 0.7f) * stiffness
        val vRadial = (-radialError * radialGain)
            .coerceIn(-orbitSpeed.value, orbitSpeed.value)

        // Tangential — proportional to orbit speed
        val vTangent = spinDir * orbitSpeed.value

        // Combine in world space
        val vx = vRadial * outX + vTangent * tanX
        val vz = vRadial * outZ + vTangent * tanZ

        // Walking-scale clamp per tick
        val stepLen = sqrt(vx * vx + vz * vz) * dt
        val stepCap = orbitSpeed.value * dt
        val scale   = if (stepLen > stepCap && stepLen > 1e-5f) stepCap / stepLen else 1f

        // Apply as a DELTA — never a position set
        val nx = EntityTracker.selfX + vx * dt * scale
        val nz = EntityTracker.selfZ + vz * dt * scale
        val ny = pkt.position.y

        pkt.position = Vector3f.from(nx, ny, nz)
        EntityTracker.selfX = nx
        EntityTracker.selfY = ny
        EntityTracker.selfZ = nz
        PacketUtil.sendMove(
            session, nx, ny, nz,
            EntityTracker.selfYaw, EntityTracker.selfPitch,
            onGround = true, teleport = false, mirrorToClient = true
        )

        // Facing — aim at the target's head
        val dx = target.x - EntityTracker.selfX
        val dz = target.z - EntityTracker.selfZ
        val targetYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        rotAngle = Pair(EntityTracker.selfPitch, wrapYaw(targetYaw))
        shouldRot = true
    }

    private fun wrapYaw(yaw: Float): Float {
        var y = yaw % 360f
        if (y > 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    // ── Main packet handler ─────────────────────────────────
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        val session = event.session

        if (cachedTargets.isEmpty()) {
            currentTarget = null
            positionHistory.clear()
            velocityHistory.clear()
            lastQuantumTarget = null
            event.cancelAndReplace(pkt)
            return
        }

        val nowNs = System.nanoTime()
        val nowMs = System.currentTimeMillis()

        val target = when (targetMode.value) {
            TargetMode.SINGLE -> {
                if (currentTarget == null || !cachedTargets.contains(currentTarget)) {
                    currentTarget = cachedTargets.firstOrNull()
                    positionHistory.clear()
                    velocityHistory.clear()
                    lastQuantumTarget = null
                }
                currentTarget
            }
            TargetMode.SWITCH -> {
                if (nowMs - lastSwitchMs >= switchDelay.value) {
                    switchIndex = (switchIndex + 1) % cachedTargets.size
                    currentTarget = cachedTargets[switchIndex]
                    lastSwitchMs = nowMs
                    positionHistory.clear()
                    velocityHistory.clear()
                    lastQuantumTarget = null
                }
                currentTarget
            }
            TargetMode.MULTI -> null
        }

        val primary = target ?: cachedTargets.firstOrNull()
        if (primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Rotation / movement ────────────────────────────
        when (rotMode.value) {
            RotationMode.RANDOM      -> applyRandomRotation(primary)
            RotationMode.TARGET_LOCK -> applyTargetLock(primary, pkt, session)
            RotationMode.ORBIT       -> applyOrbit(primary, pkt, session)
            RotationMode.SOFT_LOCK   -> applySoftLock(primary, pkt)
            RotationMode.NONE,
            RotationMode.AIM         -> calculateRotationKillAura3(primary, pkt)
        }
        if (shouldRot && rotMode.value != RotationMode.NONE) {
            val (pitch, yaw) = rotAngle
            pkt.rotation = Vector3f.from(pitch, yaw, yaw)
            if (!silentRot.value) {
                EntityTracker.selfYaw = yaw
                EntityTracker.selfPitch = pitch
            }
        }

        // ── Attack timing ──────────────────────────────────
        val attackDelayNs = when (attackMode.value) {
            AttackMode.CPS      -> 1_000_000_000L / cps.value
            AttackMode.INTERVAL -> intervalTicks.value * 50_000_000L
        }
        if (nowNs - lastAttackNs < attackDelayNs) {
            event.cancelAndReplace(pkt)
            return
        }

        val targetsToHit = when (targetMode.value) {
            TargetMode.SINGLE, TargetMode.SWITCH -> listOfNotNull(primary)
            TargetMode.MULTI -> cachedTargets
        }

        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val inRange = targetsToHit.filter {
            val dist = MathUtil.dist3(sx, sy, sz, it.x, it.y, it.z)
            dist <= range.value || dist <= wallRange.value
        }
        if (inRange.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        val swingTargets = if (hurtTimeCheck.value) {
            inRange.filterNot { EntityTracker.wasRecentlyHurt(it.runtimeId, 500L) }
        } else inRange
        if (swingTargets.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        val realSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val bestSlot = if (weaponSwitch.value == WeaponSwitchMode.NONE) realSlot
                       else bestWeaponSlot().takeIf { it in 0..8 } ?: realSlot
        val switched = weaponSwitch.value != WeaponSwitchMode.NONE && bestSlot != realSlot
        if (switched) InventoryUtil.sendHotbarSelect(session, bestSlot)

        val attacksPerHit = hitAttempts.value
        val packetCount   = boost.value

        repeat(attacksPerHit) {
            swingTargets.forEach { targetEntity ->
                if (Random.nextInt(100) < hitChance.value) {
                    val clickPos = Vector3f.from(targetEntity.x, targetEntity.y + 1.5f, targetEntity.z)
                    if (packetAttack.value) {
                        repeat(packetCount) {
                            PacketUtil.sendSwing(session)
                            PacketUtil.sendAttack(session, targetEntity.runtimeId, bestSlot, clickPos)
                        }
                    } else {
                        PacketUtil.sendSwing(session)
                        PacketUtil.sendAttack(session, targetEntity.runtimeId, bestSlot, clickPos)
                    }
                }
            }
        }

        if (switched && weaponSwitch.value == WeaponSwitchMode.SILENT) {
            InventoryUtil.sendHotbarSelect(session, realSlot)
        }

        lastAttackNs = nowNs
        event.cancelAndReplace(pkt)
    }
}
```

---