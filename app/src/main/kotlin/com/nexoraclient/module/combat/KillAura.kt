package com.rubidiumclient.module.combat

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

// Named modes (Melody V2 port): every selection setting shows a real name in
// the menu instead of a number that needs a code comment to decode.
private enum class AttackMode { CPS, INTERVAL }
private enum class TargetMode { SINGLE, SWITCH, MULTI }
private enum class RotationMode { NONE, AIM, RANDOM, TARGET_LOCK }
private enum class QuantumAlgo { DYNAMIC, VELOCITY, PATTERN, NEURAL }

/**
 * Melody "Switch" mode: whether KillAura re-equips the best hotbar weapon for
 * the attack wave.
 *  NONE   — never touch the hotbar.
 *  FULL   — switch to the best weapon and STAY there until the next wave.
 *  SILENT — switch, attack, switch back in the same wave; the dance only
 *           exists as a MobEquipmentPacket sandwich on the wire, the HUD
 *           selection never visibly moves.
 */
private enum class WeaponSwitchMode { NONE, FULL, SILENT }

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Melody-style KillAura: Aim/Random rotations, real Target Lock strafe, Quantum prediction, silent weapon switch"
), PacketEventBus.PacketListener {

    /**
     * Listener order on the bus: 100 = default (movement modules like
     * MotionFly read the packet FIRST and see the player's own camera/input
     * untouched), 200 = combat rotation writers run LAST so they always
     * compose on top of the final packet regardless of module enable order.
     * Without this, enabling MotionFly before KillAura made rotation state
     * dependent on registration sequence.
     */
    override val priority: Int = 200

    // ── Attack settings ────────────────────────────────────
    private val attackMode     = enum("Attack Mode", AttackMode.CPS)
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
    private val targetMode    = enum("Target Mode", TargetMode.MULTI)
    private val switchDelay   = int("Switch Delay",100,  20,  1000)

    // ── Rotation & Target Lock ────────────────────────────
    // Strafe rotation mode was removed (sine-wobble yaw — strictly worse
    // than the remaining generators; see docs/MELODY_MODULES.md port notes).
    private val rotMode        = enum("Rotation Mode", RotationMode.AIM)
    private val lockDistance   = float("Lock Distance", 3f, 0.5f, 7f) // Target Lock: radius to hold from the target, in BLOCKS
    private val lockSpeed      = float("Lock Speed", 4.3f, 0.5f, 12f) // Target Lock: circling speed, blocks per second

    // ── Weapon switch (Melody Switch) ──────────────────────
    private val weaponSwitch   = enum("Weapon Switch", WeaponSwitchMode.NONE)
    private val hurtTimeCheck  = bool("Hurttime Check", true)

    // ── Quantum prediction ─────────────────────────────────
    private val quantum        = bool("Quantum",          false)
    private val quantumAlgo    = enum("Q-Algorithm", QuantumAlgo.DYNAMIC)
    private val quantumStrength= float("Q-Strength",      1.5f, 0f,  5f)
    private val quantumHistory = int ("Q-History",        10,   3,   30)

    // ── Base ───────────────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        // Never spiral closer than this into the target's face, even if the
        // owner cranks Lock Distance below it.
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

    private var lastLockTarget: EntityTracker.TrackedEntity? = null
    private var lockLastTickMs = 0L
    private var lockRadius     = 0f // 0 = uninitialized; first lock = slider

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
        randomYawOffset = 0f
        randomPitchOffset = 0f
        lastRandomTarget = null
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

    // ── Best weapon scoring (Melody getBestWeaponSlot port) ──
    // Melody scores slotDamage = attackDamage + 1.25 * sharpnessLevel. The
    // relay mirror does not carry enchant NBT for hotbar items reliably, so
    // the port scores by identifier -> base attack damage only; structure is
    // identical (highest score wins, -1 = nothing worth switching to).
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

    // ── AIM rotation (Quantum-assisted point aiming) ───────
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

    // ── Random rotation: glued to the target like Target Lock, but the
    //    offset drifts off in random little hops every packet instead of
    //    smoothly circling — a jittery wander around the target. The hop
    //    sizes are clamped so it stays within a loose cone around the
    //    target and occasionally snaps back near center, never straying
    //    fully away. Only a target switch or module restart re-centers it. ──
    private fun applyRandomRotation(target: EntityTracker.TrackedEntity) {
        // Where the target actually is right now (Apolon CalcPlayerAngle style yaw)
        val dx = target.x - EntityTracker.selfX
        val dz = target.z - EntityTracker.selfZ
        val targetYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()

        if (lastRandomTarget != target) {
            // Fresh lock: start dead-on the target
            randomYawOffset   = 0f
            randomPitchOffset = 0f
            lastRandomTarget  = target
        }

        // Random walk: a small random hop each packet (mostly small, sometimes
        // a bigger jolt), staying inside a ±40° cone yaw-wise and ±15° pitch
        // so the rotation still tracks the target loosely. Small chance to
        // snap back near center so the offset never accumulates away forever.
        val bigJolt = Random.nextFloat() < 0.08f
        val yawStep = Random.nextFloat() * (if (bigJolt) 16f else 4f)
        randomYawOffset = (randomYawOffset + if (Random.nextBoolean()) yawStep else -yawStep)
        val pitchStep = Random.nextFloat() * (if (bigJolt) 8f else 2f)
        randomPitchOffset = (randomPitchOffset + if (Random.nextBoolean()) pitchStep else -pitchStep)

        randomYawOffset   = randomYawOffset.coerceIn(-40f, 40f)
        randomPitchOffset = randomPitchOffset.coerceIn(-15f, 15f)

        if (Random.nextFloat() < 0.05f) {
            // Occasional pull back toward dead-on
            randomYawOffset   *= 0.5f
            randomPitchOffset *= 0.5f
        }

        // Real pitch base, offset drifting like the yaw — always on the
        // general vicinity of the target, never fully random angles.
        val pitch = (EntityTracker.selfPitch + randomPitchOffset).coerceIn(-90f, 90f)
        rotAngle = Pair(pitch, wrapYaw(targetYaw + randomYawOffset))
        shouldRot = true
    }

    // ── Target Lock: REAL movement circling, not a yaw gimmick.
    //
    //    Geometry (owner's circle diagram): you are a dot on a circle, the
    //    target is the center.
    //      • LEFT strafe held  -> you walk the circle counter-clockwise
    //      • RIGHT strafe held -> clockwise
    //      • neither held      -> you simply stop circling, rotation pinned
    //      • forward input     -> radius shrinks smoothly (spiral in),
    //        but NEVER past 2 blocks — no face-hug spinning
    //      • backward input    -> radius grows back out (up to Lock slider)
    //
    //    Server-side this is ordinary movement (the position inside your own
    //    PlayerAuthInputPacket is rewritten); each step is mirrored back to
    //    the client via a forced MovePlayerPacket so the game renders the
    //    circle instead of rubber-banding. No world data on the wire -> the
    //    circle is naive geometry and does not path around walls. ──
    private fun applyTargetLock(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket, session: RubidiumRelaySession) {
        val nowMs = System.currentTimeMillis()
        if (lastLockTarget != target || lockLastTickMs <= 0L) {
            lastLockTarget = target
            lockLastTickMs = nowMs
            lockRadius = 0f
        }
        if (lockRadius <= 0f) lockRadius = lockDistance.value.coerceAtLeast(MIN_LOCK_RADIUS)

        val dt = (nowMs - lockLastTickMs).coerceIn(0L, 250L) / 1000f

        // Radial breathing (analog stick forward/back comes through the
        // packet's motion vector, untouched by any rotation module).
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

        // Rotation glued to the target from wherever we stand now (Apolon
        // CalcPlayerAngle style yaw), real pitch — same contract as before.
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

        // ── Rotation ──────────────────────────────────────
        when (rotMode.value) {
            RotationMode.RANDOM      -> applyRandomRotation(primary)
            RotationMode.TARGET_LOCK -> applyTargetLock(primary, pkt, session)
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

        // ── Hurttime check (Melody): skip targets still inside their
        //    hurt-invulnerability window (~0.5 s) — the server discards hits
        //    there anyway, swinging only wastes CPS budget and looks blatant. ──
        val swingTargets = if (hurtTimeCheck.value) {
            inRange.filterNot { EntityTracker.wasRecentlyHurt(it.runtimeId, 500L) }
        } else inRange
        if (swingTargets.isEmpty()) {
            event.cancelAndReplace(pkt)
            return
        }

        // ── Weapon switch (Melody): equip the best hotbar weapon for the
        //    wave. SILENT wraps the whole wave in a MobEquipmentPacket
        //    sandwich (switch → hit → switch back) that is invisible on the
        //    client HUD; FULL leaves the best weapon equipped. ──
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
