package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class LegitAura : BaseModule(
    name        = "LegitAura",
    category    = ModuleCategory.COMBAT,
    description = "İnsan gibi tepki süresi/dönüş/sapma ile 'legit' görünen otomatik saldırı"
), PacketEventBus.PacketListener {

    companion object {
        private const val TICK_INTERVAL_MS = 10L
        private const val SCAN_INTERVAL_MS = 50L
        private const val AIM_TOLERANCE = 6f
        private const val MISS_RATE = 0.06f
        private const val IGNORE_FRIENDS = true
    }

    private val range        = float("Range",         4.5f, 1f, 6f)
    private val fov          = int  ("FOV",            75,   10, 360)
    private val reactionMs   = int  ("Reaction Delay", 150,  0,  1000)
    private val turnSmooth   = float("Turn Smooth",    0.18f, 0.02f, 1f)
    private val aimJitter    = float("Aim Jitter",     1f,   0f,  8f)
    private val attackDelay  = int  ("Attack Delay",   250,  50, 2000)
    private val shortcut     = bool ("Shortcut",       false)

    @Volatile private var curYaw = 0f
    @Volatile private var curPitch = 0f
    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastScanMs = 0L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var nextDelayMs = 0L
    @Volatile private var reactionDeadlineMs = 0L
    @Volatile private var lastTargetId = 0L

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        curYaw = EntityTracker.selfYaw
        curPitch = EntityTracker.selfPitch
        lastAttackMs = 0L
        lastScanMs = 0L
        cachedTarget = null
        reactionDeadlineMs = 0L
        lastTargetId = 0L
        nextDelayMs = rollNextDelay()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        applyLegitRotation(event, pkt)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        val cached = cachedTarget?.takeIf {
            EntityTracker.getById(it.runtimeId) != null &&
            EntityTracker.distanceTo(it) <= range.value
        }
        if (cached != null) return cached
        if (now - lastScanMs < SCAN_INTERVAL_MS && cachedTarget == null) return null
        lastScanMs = now

        val found = EntityTracker.getEntitiesInRange(range.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { RotationUtil.fovCheck(it, fov.value.toFloat()) }
            .let { if (IGNORE_FRIENDS) it.filterNot { e -> e.isFriendEntity } else it }
            .minByOrNull { EntityTracker.distanceTo(it) }

        cachedTarget = found
        return found
    }

    private fun applyLegitRotation(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        val target = findTarget() ?: return
        val now = System.currentTimeMillis()

        if (target.runtimeId != lastTargetId) {
            lastTargetId = target.runtimeId
            reactionDeadlineMs = now + reactionMs.value
        }
        if (now < reactionDeadlineMs) return

        var targetRot = RotationUtil.toEntity(target)
        targetRot = RotationUtil.approximate(targetRot, aimJitter.value, aimJitter.value * 0.6f)

        curYaw = smoothYaw(curYaw, targetRot.yaw, turnSmooth.value)
        curPitch = smoothPitch(curPitch, targetRot.pitch, turnSmooth.value)

        pkt.rotation = Vector3f.from(curPitch, curYaw, curYaw)
        EntityTracker.selfYaw = curYaw
        EntityTracker.selfPitch = curPitch

        event.cancelAndReplace(pkt)
    }

    private fun tick() {
        val target = cachedTarget?.let { EntityTracker.getById(it.runtimeId) } ?: return
        if (EntityTracker.distanceTo(target) > range.value) return

        val now = System.currentTimeMillis()
        if (now < reactionDeadlineMs) return
        if (now - lastAttackMs < nextDelayMs) return

        val realRot = RotationUtil.toEntity(target)
        val aimedEnough = RotationUtil.angleDiff(curYaw, realRot.yaw) <= AIM_TOLERANCE &&
            kotlin.math.abs(curPitch - realRot.pitch) <= AIM_TOLERANCE
        if (!aimedEnough) return

        lastAttackMs = now
        nextDelayMs = rollNextDelay()
        if (Math.random() < MISS_RATE) return

        val session = PacketEventBus.currentSession ?: return
        scope.launch { attack(session, target.runtimeId) }
    }

    private fun rollNextDelay(): Long {
        val base = attackDelay.value
        val lo = (base * 0.7f).toInt().coerceAtLeast(20)
        val hi = (base * 1.3f).toInt().coerceAtLeast(lo)
        return MathUtil.randomInt(lo, hi).toLong()
    }

    private suspend fun attack(session: RubidiumRelaySession, targetRid: Long) {
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, targetRid, EntityTracker.selfHotbarSlot)
    }

    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }
}
