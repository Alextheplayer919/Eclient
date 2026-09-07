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
    description = "KillAura3 rotations + Quantum + Attack features (hitAttempts, packetAttack, wallRange)"
), PacketEventBus.PacketListener {

    // ── Attack settings (WAura + KillAura3 extras) ────────
    private val attackMode     = int("Attack Mode", 0, 0, 1)    // 0=CPS, 1=Interval (ticks)
    private val cps            = int("CPS",          25, 1, 50)
    private val intervalTicks  = int("Interval",      1, 0, 20) // ticks between attacks
    private val boost          = int("Packets",      2, 1, 10)   // packets per hit
    private val hitAttempts    = int("Hit Attempts", 1, 1, 5)    // attacks per tick (KillAura3)
    private val packetAttack   = bool("Packet Attack", false)    // use packet attack (KillAura3)
    private val hitChance      = int("Hit Chance",   100, 0, 100)

    // ── Range settings ──────────────────────────────────────
    private val playersOnly   = bool("Players Only", true)
    private val mobsOnly      = bool("Mobs Only",    false)
    private val range         = float("Range",       50f,  2f,  50f)
    private val wallRange     = float("Wall Range",  3f,   0f,  10f)   // KillAura3: attack through walls

    // ── Target selection ──────────────────────────────────
    private val targetMode    = int("Target Mode", 2,    0,   2)   // 0=Single, 1=Switch, 2=Multi
    private val switchDelay   = int("Switch Delay",100,  20,  1000)

    // ── Orbit ──────────────────────────────────────────────
    private val orbitEnabled     = bool("Orbit",          false)
    private val orbitRange       = float("Orbit Range",   6f,   1.5f, 25f)
    private val orbitSpeed       = float("Orbit Speed",   8f,   1f,   30f)
    private val orbitMoveSpeed   = float("Move Speed",    2.0f,  0.5f, 6f)
    private val orbitStopDist    = float("Stop Distance", 100f,  10f,  200f)

    // ── KillAura3 rotations ────────────────────────────────
    private val rotMode        = int("Rotation Mode", 1, 0, 2)   // 0=None, 1=Normal, 2=Strafe

    // ── Quantum prediction ──────────────────────────────────
    private val quantum        = bool("Quantum",          false)
    private val quantumAlgo    = int ("Q-Algorithm",      0,   0,   3)   // 0=Dynamic, 1=Velocity, 2=Pattern, 3=Neural
    private val quantumStrength= float("Q-Strength",      1.5f, 0f,  5f)
    private val quantumHistory = int ("Q-History",        10,   3,   30)

    // ── Base ────────────────────────────────────────────────
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
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastSwitchMs   = 0L
    @Volatile private var switchIndex    = 0
    @Volatile private var currentTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastScanMs     = 0L

    // Rotation state
    private var rotAngle = Pair(0f, 0f)
    private var shouldRot = false
    private var strafeAngle = 0f

    // Quantum state
    private var positionHistory = mutableListOf<Vector3f>()
    private var velocityHistory = mutableListOf<Vector3f>()
    private var lastQuantumTarget: EntityTracker.TrackedEntity? = null
    private var quantumConfidence = 1.0f

    // Orbit state
    private var orbitAngle = 0f

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackNs   = 0L
        lastAttackMs   = 0L
        lastSwitchMs   = 0L
        switchIndex    = 0
        currentTarget  = null
        cachedTargets  = emptyList()
        lastScanMs     = 0L
        positionHistory.clear()
        velocityHistory.clear()
        lastQuantumTarget = null
        quantumConfidence = 1.0f
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        positionHistory.clear()
        velocityHistory.clear()
        lastQuantumTarget = null
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

    // ── Rotation (KillAura3 style) ──────────────────────────
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
        var pitch = rot.pitch

        if (rotMode.value == 2) {
            strafeAngle = (strafeAngle + 5f) % 360f
            val rad = Math.toRadians(strafeAngle.toDouble()).toFloat()
            yaw += sin(rad) * 5f
        }

        rotAngle = Pair(pitch, yaw)
        shouldRot = true
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

    // ── Attack ─────────────────────────────────────────────────
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

        // ── Rotation ──────────────────────────────────────────
        calculateRotationKillAura3(primary, pkt)

        if (shouldRot && rotMode.value != 0) {
            val (pitch, yaw) = rotAngle
            pkt.rotation = Vector3f.from(pitch, yaw, yaw)
            if (!silentRot.value) {
                EntityTracker.selfYaw = yaw
                EntityTracker.selfPitch = pitch
            }
        }

        // ── Orbit ──────────────────────────────────────────────
        if (orbitEnabled.value) {
            applyOrbit(session, primary, pkt)
        }

        // ── Attack timing ────────────────────────────────────
        val attackDelayNs = when (attackMode.value) {
            0 -> 1_000_000_000L / cps.value
            else -> intervalTicks.value * 50_000_000L // 50ms per tick
        }
        if (nowNs - lastAttackNs < attackDelayNs) {
            event.cancelAndReplace(pkt)
            return
        }

        val targetsToHit = when (targetMode.value) {
            0, 1 -> listOfNotNull(primary)
            else -> cachedTargets
        }

        // ── Range check with wallRange ──────────────────────
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

        // ── Attack using KillAura3 features ────────────────
        val attacksPerHit = hitAttempts.value
        val packetCount = boost.value

        repeat(attacksPerHit) { attempt ->
            inRange.forEach { targetEntity ->
                if (Random.nextInt(100) < hitChance.value) {
                    if (packetAttack.value) {
                        // Packet attack: send multiple packets (simulate InventoryTransactionPacket)
                        repeat(packetCount) {
                            PacketUtil.sendSwing(session)
                            val clickPos = Vector3f.from(targetEntity.x, targetEntity.y + 1.5f, targetEntity.z)
                            PacketUtil.sendAttack(session, targetEntity.runtimeId, slot, clickPos)
                        }
                    } else {
                        // Normal attack: one swing + one attack per attempt
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
