package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.RubberbandGuard
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

class MotionFly : BaseModule(
    name        = "MotionFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Smooth anarchy fly with per‑tick capping"
) {

    enum class FlyMode {
        Motion,   // SetEntityMotion – fast, sometimes patched
        Vanilla,  // MovePlayerPacket with ground spoof – best bypass
        Packet,   // Pure MovePlayerPacket + abilities
        Elytra    // Glide simulation
    }

    // ── Settings ──────────────────────────────────────
    private val flyMode         = enum("Fly Mode",         FlyMode.Vanilla)
    private val horizontalSpeed = float("Horizontal",      1.5f,  0.1f,  10.0f)
    private val verticalSpeed   = float("Vertical",        0.6f,  0.1f,  5.0f)
    private val glideSpeed      = float("Glide Speed",     0.05f, -0.5f, 0.5f)
    private val bypassMode      = bool ("Lifeboat Bypass", true)
    private val motionInterval  = float("Delay",           30.0f, 5.0f,  100.0f)
    private val antiKick        = bool ("Anti-Kick",       true)
    private val antiKickInterval = int ("Anti-Kick Interval", 3500, 1000, 8000)
    private val jitter          = float("Jitter",          0.02f, 0f,   0.2f)
    private val grimMode        = bool ("Grim Mode",       false)
    private val grimSpeed       = float("Grim Speed",      0.25f, 0.05f, 1.0f)
    private val maxStep         = float("Max Step",        0.3f,  0.05f, 1.0f)   // KEY: caps movement per tick
    private val timerMultiplier = float("Timer Multiplier",1.0f,  0.5f,  5.0f)

    // ── State ──────────────────────────────────────────
    @Volatile private var lastMoveTime = 0L
    @Volatile private var jitterState = false
    @Volatile private var canFly = false
    @Volatile private var lastAntiKickTime = 0L
    @Volatile private var jitterSeed = 0.0

    private val rubberbandGuard = RubberbandGuard(scope)

    // ── Ability packets ──────────────────────────────
    private val flyPacket = UpdateAbilitiesPacket().apply {
        playerPermission  = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        uniqueEntityId    = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.MAY_FLY,
                    Ability.FLYING,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed  = 0.5f
        })
    }

    private val resetPacket = UpdateAbilitiesPacket().apply {
        playerPermission  = PlayerPermission.VISITOR
        commandPermission = CommandPermission.ANY
        uniqueEntityId    = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed  = 0.05f
        })
    }

    override fun onEnable() {
        super.onEnable()
        lastMoveTime = 0L
        jitterState = false
        canFly = false
        lastAntiKickTime = 0L
        jitterSeed = kotlin.random.Random.nextDouble(0.0, 2.0 * Math.PI)
        rubberbandGuard.reset()
    }

    override fun onDisable() {
        super.onDisable()
        PacketEventBus.currentSession?.let { applyFlyAbilities(false, it) }
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val session = event.session
        val now = System.currentTimeMillis()

        // Apply abilities for Vanilla / Packet modes
        if (flyMode.value == FlyMode.Vanilla || flyMode.value == FlyMode.Packet) {
            applyFlyAbilities(true, session)
        }

        // Rubberband guard (original)
        rubberbandGuard.guard(event, pkt, true, maxStep.value, 20L)

        // Anti-kick
        if (antiKick.value && flyMode.value != FlyMode.Vanilla) {
            if (now - lastAntiKickTime >= antiKickInterval.value) {
                lastAntiKickTime = now
                val fallPos = Vector3f.from(
                    EntityTracker.selfX,
                    EntityTracker.selfY - 0.1f,
                    EntityTracker.selfZ
                )
                sendPosition(session, fallPos, pkt.rotation)
            }
        }

        // Rate limit (multiplied by timer)
        val effectiveDelay = motionInterval.value / timerMultiplier.value
        if (now - lastMoveTime < effectiveDelay) return
        lastMoveTime = now

        // ── Speed ──────────────────────────────────────
        val effHoriz = if (grimMode.value) grimSpeed.value else horizontalSpeed.value
        val effVert  = if (grimMode.value) grimSpeed.value * 0.5f else verticalSpeed.value

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val wantUp = pkt.inputData.contains(PlayerAuthInputData.WANT_UP)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN)

        val yawRad = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yawRad.toDouble()).toFloat()
        val cosYaw = cos(yawRad.toDouble()).toFloat()

        val strafe  = inputX * effHoriz
        val forward = inputZ * effHoriz

        // Jitter
        jitterSeed += 0.1
        val jx = (sin(jitterSeed) * jitter.value).toFloat()
        val jz = (cos(jitterSeed + 1.0) * jitter.value).toFloat()

        // Vertical with Lifeboat bypass
        val vertical = when {
            wantUp -> effVert
            wantDown -> -effVert
            bypassMode.value -> -glideSpeed.value.coerceAtLeast(-0.1f)
            else -> glideSpeed.value
        }

        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ

        val moveX = strafe * cosYaw - forward * sinYaw + jx
        val moveZ = forward * cosYaw + strafe * sinYaw + jz
        val moveY = vertical

        // ── 🔥 CRITICAL FIX: Cap per‑tick movement ──
        val cap = maxStep.value
        val cappedX = moveX.coerceIn(-cap, cap)
        val cappedY = moveY.coerceIn(-cap, cap)
        val cappedZ = moveZ.coerceIn(-cap, cap)

        // ── Apply mode with capped movement ──────────
        when (flyMode.value) {
            FlyMode.Vanilla -> {
                val newX = sx + cappedX
                val newY = sy + cappedY
                val newZ = sz + cappedZ
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation, onGround = true)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
                // Sync motion so server doesn't desync
                pkt.motion = Vector2f.from(cappedX, cappedZ)
            }

            FlyMode.Motion -> {
                val motionPacket = SetEntityMotionPacket().apply {
                    runtimeEntityId = EntityTracker.selfRuntimeId
                    motion = Vector3f.from(
                        cappedX,
                        cappedY + if (jitterState) 0.03f else -0.03f,
                        cappedZ
                    )
                }
                session.clientBound(motionPacket)
                jitterState = !jitterState

                // Small position update
                val newX = sx + cappedX * 0.1f
                val newY = sy + cappedY * 0.1f
                val newZ = sz + cappedZ * 0.1f
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
                pkt.motion = Vector2f.from(cappedX * 0.1f, cappedZ * 0.1f)
            }

            FlyMode.Packet -> {
                val newX = sx + cappedX
                val newY = sy + cappedY
                val newZ = sz + cappedZ
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
                pkt.motion = Vector2f.from(cappedX, cappedZ)
            }

            FlyMode.Elytra -> {
                val newX = sx + cappedX * 1.2f
                val newY = sy + cappedY * 0.3f
                val newZ = sz + cappedZ * 1.2f
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
                pkt.motion = Vector2f.from(cappedX * 1.2f, cappedZ * 1.2f)
            }
        }

        event.cancelAndReplace(pkt)
    }

    // ── Helper: send MovePlayerPacket ────────────────
    private fun sendPosition(session: RubidiumRelaySession, pos: Vector3f, rot: Vector3f, onGround: Boolean = false) {
        try {
            val packet = MovePlayerPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                position = pos
                rotation = rot
                mode = MovePlayerPacket.Mode.NORMAL
                this.isOnGround = onGround
                ridingRuntimeEntityId = 0L
            }
            session.serverBound(packet)
            session.clientBound(packet)
        } catch (_: Exception) {}
    }

    // ── Apply abilities ──────────────────────────────
    private fun applyFlyAbilities(enabled: Boolean, session: RubidiumRelaySession) {
        if (canFly == enabled) return
        val id = EntityTracker.selfUniqueId
        flyPacket.uniqueEntityId = id
        resetPacket.uniqueEntityId = id
        session.clientBound(if (enabled) flyPacket else resetPacket)
        canFly = enabled
    }
}
