package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
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

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Otomatik saldırı"
), PacketEventBus.PacketListener {

    enum class AttackMode   { Single, Multi, Closest }
    enum class PriorityMode { Distance, Health, LowestHealth, Direction }
    enum class CritMode     { InputFlag, MovePacket, None }

    // ---------- Core settings ----------
    private val cpsMin          = int  ("CPS Min",          20,    0,  20)
    private val cpsMax          = int  ("CPS Max",          20,    0,  20)
    private val range           = float("Range",            18f,   1f, 18f)
    private val fov             = int  ("FOV",              360,   30, 360)
    private val maxTargets      = int  ("Max Targets",       10,   1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val critMode        = enum ("Crit Mode",        CritMode.MovePacket)
    private val critInjectionCooldownMs = int("Crit Injection Cooldown", 0, 0, 1000)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 1f,   0.01f, 1f)
    private val ignoreFriends   = bool ("Ignore Friends",   true)
    private val antiBot         = bool ("Anti Bot",         true)
    private val requireLos      = bool ("Require LOS",      false)
    private val shortcut        = bool ("Shortcut",         false)

    // ---------- Orbit (replaces broken keepDistance) ----------
    private val orbit          = bool ("Orbit",              false)
    private val orbitRange     = float("Orbit Range",       4f,   1f,  10f)
    private val orbitSpeed     = float("Orbit Speed",       30f,  1f,  100f) // degrees per tick
    private val orbitADControl = bool ("A/D Control Orbit", false)

    companion object {
        private const val CRIT_LOCK_KEY = "crit-injection"
    }

    @Volatile private var pendingAttack  = false
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var critPending    = false
    @Volatile private var lastMovePacketCritMs = 0L
    @Volatile private var orbitAngle     = 0f // current orbit position (degrees)

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        pendingAttack = false
        lastAttackMs  = 0L
        lastScanMs    = 0L
        cachedTargets = emptyList()
        headLockYaw   = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        critPending   = false
        lastMovePacketCritMs = 0L
        orbitAngle    = 0f
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

        // ----- Head lock -----
        if (headLock.value && primary != null) {
            val rot = RotationUtil.toEntity(primary)
            val f   = headLockSmooth.value
            headLockYaw   = smoothYaw(headLockYaw,   rot.yaw,   f)
            headLockPitch = smoothPitch(headLockPitch, rot.pitch, f)
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            EntityTracker.selfYaw   = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }

        // ----- Orbit: keep distance by circling the target -----
        if (orbit.value && primary != null) {
            if (orbitADControl.value) {
                // A/D input from moveVector.x (positive = right, negative = left)
                val strafeInput = pkt.moveVector.x
                orbitAngle += strafeInput * orbitSpeed.value * 0.5f
            } else {
                orbitAngle += orbitSpeed.value // auto-rotate
            }
            orbitAngle %= 360f

            val rad = Math.toRadians(orbitAngle.toDouble()).toFloat()
            val dx = sin(rad) * orbitRange.value
            val dz = cos(rad) * orbitRange.value
            // Keep Y at target's height + half range (so you're not underground or floating too high)
            val targetPos = Vector3f.from(
                primary.x + dx,
                primary.y + orbitRange.value * 0.5f,
                primary.z + dz
            )
            pkt.position = targetPos
            EntityTracker.selfX = pkt.position.x
            EntityTracker.selfY = pkt.position.y
            EntityTracker.selfZ = pkt.position.z
        }

        // ----- Crit via InputFlag -----
        if (critPending && critMode.value == CritMode.InputFlag && primary != null) {
            if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                if (EntityTracker.selfSprinting) pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
                if (EntityTracker.selfOnGround) pkt.inputData.add(PlayerAuthInputData.JUMPING)
                CritLock.release(CRIT_LOCK_KEY)
            }
            critPending = false
        }

        if (!pendingAttack || primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        pendingAttack = false
        val session = event.session

        // ----- Crit via MovePacket -----
        var movePacketCritFired = false
        val tpAuraConflict = try {
            System.currentTimeMillis() - TPAura.lastPositionOverrideMs < 120L
        } catch (e: NoClassDefFoundError) { false }

        if (critMode.value == CritMode.MovePacket && !tpAuraConflict &&
            EntityTracker.selfOnGround && !EntityTracker.selfInWater) {
            val now2 = System.currentTimeMillis()
            if (now2 - lastMovePacketCritMs >= critInjectionCooldownMs.value) {
                if (CritLock.tryAcquire(CRIT_LOCK_KEY)) {
                    lastMovePacketCritMs = now2
                    movePacketCritFired = true
                    if (EntityTracker.selfSprinting) pkt.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
                    PacketUtil.sendMoveAtSelf(session, dyOffset = 0.42f, onGround = false)
                }
            }
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        when (attackMode.value) {
            AttackMode.Multi -> {
                PacketUtil.sendSwing(session)
                targets.take(maxTargets.value).forEach { t ->
                    if (requireLos.value && !hasLos(t)) return@forEach
                    val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                    PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                }
            }
            else -> {
                val click = Vector3f.from(primary.x, primary.y + 1.5f, primary.z)
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, primary.runtimeId, slot, click)
            }
        }

        if (movePacketCritFired) {
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
            CritLock.release(CRIT_LOCK_KEY)
        }

        event.cancelAndReplace(pkt)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(20L)
        }
    }

    private fun tick() {
        val now     = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= 50L) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }

        if (cachedTargets.isEmpty()) return

        lastAttackMs  = now
        val critBlocked = EntityTracker.selfInWater || EntityTracker.selfBlinded
        critPending   = critMode.value == CritMode.InputFlag && !critBlocked
        pendingAttack = true
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val raw = EntityTracker.getEntitiesInRange(range.value)
        val needsAngle = fov.value < 360 || priorityMode.value == PriorityMode.Direction

        data class Scored(val entity: EntityTracker.TrackedEntity, val key: Float)
        val scored = ArrayList<Scored>(raw.size)
        for (e in raw) {
            if (!e.isPlayer || e.runtimeId == EntityTracker.selfRuntimeId) continue
            val angle = if (needsAngle) EntityTracker.angleToEntity(e) else 0f
            if (fov.value < 360 && angle > fov.value / 2f) continue
            if (ignoreFriends.value && e.isFriendEntity) continue
            if (antiBot.value && e.isLikelyBot()) continue
            val key = when (priorityMode.value) {
                PriorityMode.Distance     -> MathUtil.dist3sq(e.x, e.y, e.z, sx, sy, sz)
                PriorityMode.Health,
                PriorityMode.LowestHealth -> e.health
                PriorityMode.Direction    -> angle
            }
            scored.add(Scored(e, key))
        }
        scored.sortBy { if (reversePriority.value) -it.key else it.key }
        return scored.map { it.entity }
    }

    private fun hasLos(t: EntityTracker.TrackedEntity): Boolean = MathUtil.hasLineOfSight(
        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ,
        t.x, t.y + 1.2f, t.z
    )

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
