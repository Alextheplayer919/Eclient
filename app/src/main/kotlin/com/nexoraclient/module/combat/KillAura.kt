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
    description = "KillAura3 rotations + Quantum + Random + Target Lock"
), PacketEventBus.PacketListener {

    // ── Attack settings ────────────────────────────────────
    private val attackMode     = int("Attack Mode", 0, 0, 1)
    private val cps            = int("CPS",          25, 1, 50)
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
    private val targetMode    = int("Target Mode", 2,    0,   2)
    private val switchDelay   = int("Switch Delay",100,  20,  1000)

    // ── Orbit ──────────────────────────────────────────────
    private val orbitEnabled     = bool("Orbit",          false)
    private val orbitRange       = float("Orbit Range",   6f,   1.5f, 25f)
    private val orbitSpeed       = float("Orbit Speed",   8f,   1f,   30f)
    private val orbitMoveSpeed   = float("Move Speed",    2.0f,  0.5f, 6f)
    private val orbitStopDist    = float("Stop Distance", 100f,  10f,  200f)

    // ── Rotation ──────────────────────────────────────────
    private val rotMode        = int("Rotation Mode", 1, 0, 4)   // 0=None, 1=Normal, 2=Strafe, 3=Random, 4=Target Lock
    private val lockDistance   = float("Lock Distance", 10f, 1f, 90f) // yaw degrees per packet while a strafe input is held (Target Lock)

    // ── Quantum prediction ─────────────────────────────────
    private val quantum        = bool("Quantum",          false)
    private val quantumAlgo    = int ("Q-Algorithm",      0,   0,   3)
    private val quantumStrength= float("Q-Strength",      1.5f, 0f,  5f)
    private val quantumHistory = int ("Q-History",        10,   3,   30)

    // ── Base ───────────────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val POSITION_TOLERANCE = 0.05f
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
    private var strafeAngle = 0f

    private var lockedYaw = 0f
    private var lastLockTarget: EntityTracker.TrackedEntity? = null

    private var positionHistory = mutableListOf<Vector3f>()
    private var velocityHistory = mutableListOf<Vector3f>()
    private var lastQuantumTarget: EntityTracker.TrackedEntity? = null
    private var quantumConfidence = 1.0f

    private var orbitAngle = 0f

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
        lockedYaw = 0f
        lastLockTarget = null
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
            playersOnly.value && mobsOnly.value -> false
            playersOnly.value && !isPlayer -> false
            mobsOnly.value && isPlayer -> false
            else -> true
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    // ── Quantum prediction ──────────────────────────────────
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
            0 -> predictDynamic(currentPos)
            1 -> predictAdvancedVelocity(currentPos)
            2 -> predictPatternBased(currentPos)
            3 -> predictNeural(currentPos)
            else -> currentPos
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
                weightedVel.x / totalWeight,
                weightedVel.y / totalWeight,
                weightedVel.z / totalWeight
            )
        }

        if (velocityHistory.size >= 3) {
            val last = velocityHistory.last()
            val thirdLast = velocityHistory[velocityHistory.size - 3]
            val accel = Vector3f.from(
                (last.x - thirdLast.x) * 0.3f,
                (last.y - thirdLast.y) * 0.3f,
                (last.z - thirdLast.z) * 0.3f
            )
            weightedVel = weightedVel.add(accel)
        }

        val offset = Vector3f.from(
            weightedVel.x * quantumStrength.value * 1.5f,
            weightedVel.y * quantumStrength.value * 1.5f,
            weightedVel.z * quantumStrength.value * 1.5f
        )
        return currentPos.add(offset)
    }

    private fun predictAdvancedVelocity(currentPos: Vector3f): Vector3f {
        if (velocityHistory.size < 2) return currentPos

        var avgVel = Vector3f.from(0f, 0f, 0f)
        for (vel in velocityHistory) {
            avgVel = avgVel.add(vel)
        }
        avgVel = Vector3f.from(
            avgVel.x / velocityHistory.size,
            avgVel.y / velocityHistory.size,
            avgVel.z / velocityHistory.size
        )
        val offset = Vector3f.from(
            avgVel.x * quantumStrength.value * 2.0f,
            avgVel.y * quantumStrength.value * 2.0f,
            avgVel.z * quantumStrength.value * 2.0f
        )
        return currentPos.add(offset)
    }

    private fun predictPatternBased(currentPos: Vector3f): Vector3f {
        if (positionHistory.size < 5) return currentPos
        return predictDynamic(currentPos)
    }

    private fun predictNeural(currentPos: Vector3f): Vector3f {
        if (velocityHistory.size < 3) return currentPos

        val recent = velocityHistory.last()
        val recentMomentum = Vector3f.from(
            recent.x * 0.4f,
            recent.y * 0.4f,
            recent.z * 0.4f
        )

        var avgLen = 0f
        var avgDir = Vector3f.from(0f, 0f, 0f)
        for (vel in velocityHistory) {
            val len = sqrt(vel.x * vel.x + vel.y * vel.y + vel.z * vel.z)
            avgLen += len
            if (len > 0f) {
                avgDir = avgDir.add(Vector3f.from(vel.x / len, vel.y / len, vel.z / len))
            }
        }
        avgLen /= velocityHistory.size.toFloat()
        val avgDirection = Vector3f.from(
            avgDir.x * avgLen * 0.3f,
            avgDir.y * avgLen * 0.3f,
            avgDir.z * avgLen * 0.3f
        )

        val noise = Vector3f.from(
            (Random.nextFloat() * 2 - 1) * 0.05f,
            (Random.nextFloat() * 2 - 1) * 0.02f,
            (Random.nextFloat() * 2 - 1) * 0.05f
        )
        val total = recentMomentum.add(avgDirection).add(noise)
        val offset = Vector3f.from(
            total.x * quantumStrength.value,
            total.y * quantumStrength.value,
            total.z * quantumStrength.value
        )
        return currentPos.add(offset)
    }

    // ── KillAura3 rotation (normal mode) ──────────────────
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
        var yaw = rot.yaw
        val pitch = rot.pitch

        if (rotMode.value == 2) {
            strafeAngle = (strafeAngle + 5f) % 360f
            val rad = Math.toRadians(strafeAngle.toDouble()).toFloat()
            yaw += sin(rad) * 5f
        }

        rotAngle = Pair(pitch, yaw)
        shouldRot = true
    }

    // ── Random rotation: a fresh random yaw/pitch every packet ──
    private fun applyRandomRotation() {
        val yaw   = Random.nextFloat() * 360f - 180f
        val pitch = Random.nextFloat() * 180f - 90f
        rotAngle = Pair(pitch, yaw)
        shouldRot = true
    }

    // ── Target Lock: yaw-only lock that holds still until the player presses a
    //    strafe input — left input spins the rotation left, right spins right.
    //    "Lock Distance" = how far it spins per packet. ──
    private fun applyTargetLock(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        // Snap onto the target whenever it changes so the lock starts aimed
        // (Apolon CalcPlayerAngle style yaw).
        if (lastLockTarget != target) {
            val dx = target.x - EntityTracker.selfX
            val dz = target.z - EntityTracker.selfZ
            lockedYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
            lastLockTarget = target
        }

        var spinDir = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT)) spinDir = -1f
        else if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) spinDir = 1f

        if (spinDir != 0f) {
            lockedYaw = wrapYaw(lockedYaw + spinDir * lockDistance.value)
        }

        // Real pitch, locked/spun yaw — stands still until strafe input arrives.
        rotAngle = Pair(EntityTracker.selfPitch, lockedYaw)
        shouldRot = true
    }

    private fun wrapYaw(yaw: Float): Float {
        var y = yaw % 360f
        if (y > 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    // ── Orbit ──────────────────────────────────────────────────
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

    // ── Main packet handler ────────────────────────────────
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
            0 -> {
                if (currentTarget == null || !cachedTargets.contains(currentTarget)) {
                    currentTarget = cachedTargets.firstOrNull()
                    positionHistory.clear()
                    velocityHistory.clear()
                    lastQuantumTarget = null
                }
                currentTarget
            }
            1 -> {
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
            else -> null
        }

        val primary = target ?: cachedTargets.firstOrNull()
        if (primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Rotation (0=None, 1=Normal, 2=Strafe, 3=Random, 4=Target Lock) ────
        when (rotMode.value) {
            3    -> applyRandomRotation()
            4    -> applyTargetLock(primary, pkt)
            else -> calculateRotationKillAura3(primary, pkt)
        }
        if (shouldRot && rotMode.value != 0) {
            val (pitch, yaw) = rotAngle
            pkt.rotation = Vector3f.from(pitch, yaw, yaw)
            if (!silentRot.value) {
                EntityTracker.selfYaw = yaw
                EntityTracker.selfPitch = pitch
            }
        }

        // ── Orbit ──────────────────────────────────────────
        if (orbitEnabled.value) {
            applyOrbit(session, primary, pkt)
        }

        // ── Attack timing ──────────────────────────────────
        val attackDelayNs = when (attackMode.value) {
            0 -> 1_000_000_000L / cps.value
            else -> intervalTicks.value * 50_000_000L
        }
        if (nowNs - lastAttackNs < attackDelayNs) {
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
            val dist = MathUtil.dist3(sx, sy, sz, it.x, it.y, it.z)
            dist <= range.value || dist <= wallRange.value
        }
        if (inRange.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val attacksPerHit = hitAttempts.value
        val packetCount = boost.value

        repeat(attacksPerHit) {
            inRange.forEach { targetEntity ->
                if (Random.nextInt(100) < hitChance.value) {
                    if (packetAttack.value) {
                        repeat(packetCount) {
                            PacketUtil.sendSwing(session)
                            val clickPos = Vector3f.from(targetEntity.x, targetEntity.y + 1.5f, targetEntity.z)
                            PacketUtil.sendAttack(session, targetEntity.runtimeId, slot, clickPos)
                        }
                    } else {
                        PacketUtil.sendSwing(session)
                        val clickPos = Vector3f.from(targetEntity.x, targetEntity.y + 1.5f, targetEntity.z)
                        PacketUtil.sendAttack(session, targetEntity.runtimeId, slot, clickPos)
                    }
                }
            }
        }

        lastAttackNs = nowNs
        event.cancelAndReplace(pkt)
    }
}
