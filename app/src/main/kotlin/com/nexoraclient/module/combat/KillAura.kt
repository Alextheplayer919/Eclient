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
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.*
import kotlin.random.Random

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "WAura attack + manual orbit (A/D input via inputData)"
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
    private val orbitEnabled  = bool("Orbit",         false)
    private val orbitRange    = float("Orbit Range",  6f,   2f,   20f)   // radius
    private val orbitSpeed    = float("Orbit Speed",  6f,   0.5f, 20f)   // more responsive
    private val orbitStopDist = float("Stop Distance",50f,  4f,   100f)  // works from far away

    // ── Rotation ────────────────────────────────────────────
    private val silentRot     = bool("Silent Rotation", true)
    private val ignoreFriends = bool("Ignore Friends", true)
    private val antiBot       = bool("Anti Bot",       true)
    private val shortcut      = bool("Shortcut",       false)

    companion object {
        private const val TARGET_SCAN_INTERVAL = 100L
        private const val ORBIT_TOLERANCE = 0.3f
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

    // ── Rotation ──────────────────────────────────────────────
    private fun updateRotation(target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val rot = RotationUtil.toEntity(target)
        headLockYaw = rot.yaw
        headLockPitch = rot.pitch
        pkt.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)

        if (!silentRot.value) {
            EntityTracker.selfYaw = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }
    }

    // ── Orbit (manual, using inputData for A/D) ─────────────
    private fun applyOrbit(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity, pkt: PlayerAuthInputPacket) {
        val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val distToTarget = MathUtil.dist3(selfPos.x, selfPos.y, selfPos.z, target.x, target.y, target.z)

        if (distToTarget > orbitStopDist.value) return

        // Read strafe from inputData (immune to Fly packet modifications)
        var strafeInput = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.LEFT)) strafeInput = -1f
        else if (pkt.inputData.contains(PlayerAuthInputData.RIGHT)) strafeInput = 1f

        if (strafeInput == 0f) return

        orbitAngle += strafeInput * orbitSpeed.value
        orbitAngle %= 360f

        val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
        val targetX = target.x + cos(rad) * orbitRange.value
        val targetZ = target.z + sin(rad) * orbitRange.value
        val targetY = target.y + 0.2f

        val dx = targetX - selfPos.x
        val dz = targetZ - selfPos.z
        val dy = targetY - selfPos.y
        val horizDist = sqrt(dx * dx + dz * dz)
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)

        if (totalDist > ORBIT_TOLERANCE) {
            val speed = 0.5f
            var motionX = 0f
            var motionZ = 0f
            var motionY = 0f

            if (horizDist > 0.1f) {
                val normX = dx / horizDist
                val normZ = dz / horizDist
                motionX = normX * speed
                motionZ = normZ * speed
            }
            motionY = dy.coerceIn(-0.2f, 0.2f)

            val motionPacket = SetEntityMotionPacket()
            motionPacket.runtimeEntityId = EntityTracker.selfRuntimeId
            motionPacket.motion = Vector3f.from(motionX, motionY, motionZ)
            session.clientBound(motionPacket)
        }
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

        updateRotation(primary, pkt)

        if (orbitEnabled.value) {
            applyOrbit(session, primary, pkt)
        }

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
