package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.RubberbandGuard
import kotlinx.coroutines.launch
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
    description = "MotionFly with anarchy bypass modes (Vanilla / Motion / Packet / Elytra)"
) {

    enum class FlyMode {
        /** Original SetEntityMotion – fast but may flag on some servers */
        Motion,
        /** VanillaFly – spoofs ground with MovePlayerPacket, best bypass */
        Vanilla,
        /** PacketFly – only MovePlayerPacket, uses ability packet */
        Packet,
        /** ElytraFly – simulates gliding */
        Elytra
    }

    // ── Original settings ──────────────────────────────────
    private val horizontalSpeed = float("Horizontal Speed", 3.5f, 0.5f, 10.0f)
    private val verticalSpeed   = float("Vertical Speed",   1.5f, 0.5f, 5.0f)
    private val glideSpeed      = float("Glide Speed",      0.1f, -0.01f, 1.0f)
    private val motionInterval  = float("Delay",            50.0f, 10.0f, 100.0f)
    private val shortcut        = bool ("Shortcut",         false)
    private val antiRubberband    = bool ("Anti Rubber-band", true)
    private val rubberbandMaxStep = float("Max Step",         0.9f, 0.3f, 2.0f)
    private val rubberbandDelayMs = int  ("Step Delay (ms)",  12,   2,   40)

    // ── New anarchy bypass settings ──────────────────────
    private val flyMode         = enum("Fly Mode",         FlyMode.Vanilla)   // default to best bypass
    private val bypassMode      = bool ("Lifeboat Bypass", true)              // uses negative glide when not pressing up/down
    private val antiKick        = bool ("Anti-Kick",       true)
    private val antiKickInterval = int ("Anti-Kick Interval", 3500, 1000, 8000)
    private val jitter          = float("Jitter",          0.03f, 0f, 0.2f)
    private val grimMode        = bool ("Grim Mode",       false)
    private val grimSpeed       = float("Grim Speed",       0.25f, 0.05f, 1.0f)

    // ── State ──────────────────────────────────────────
    @Volatile private var lastMotionTime = 0L
    @Volatile private var jitterState    = false
    @Volatile private var canFly         = false
    @Volatile private var lastSession    : RubidiumRelaySession? = null
    @Volatile private var lastAntiKickTime = 0L
    @Volatile private var jitterSeed     = 0.0
    @Volatile private var grimTicks      = 0

    private val rubberbandGuard = RubberbandGuard(scope)

    // ── Ability packets (same as original) ──────────────
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
        lastMotionTime = 0L
        jitterState = false
        canFly = false
        lastAntiKickTime = 0L
        jitterSeed = kotlin.random.Random.nextDouble(0.0, 2.0 * Math.PI)
        grimTicks = 0
        rubberbandGuard.reset()
        // Apply abilities for Vanilla / Packet modes
        val session = PacketEventBus.currentSession
        if (session != null && (flyMode.value == FlyMode.Vanilla || flyMode.value == FlyMode.Packet)) {
            applyFlyAbilities(true, session)
        }
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { applyFlyAbilities(false, it) }
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session
        val session = event.session
        val now = System.currentTimeMillis()

        // Apply abilities for Vanilla / Packet modes
        if (flyMode.value == FlyMode.Vanilla || flyMode.value == FlyMode.Packet) {
            applyFlyAbilities(true, session)
        }

        // Original rubberband guard
        rubberbandGuard.guard(event, pkt, antiRubberband.value, rubberbandMaxStep.value, rubberbandDelayMs.value.toLong())

        // Anti-kick (not needed for Vanilla mode)
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

        // Rate limit
        if (now - lastMotionTime < motionInterval.value) return
        lastMotionTime = now

        // ── Speed calculation ──────────────────────────────
        val effHoriz = if (grimMode.value) grimSpeed.value else horizontalSpeed.value
        val effVert  = if (grimMode.value) grimSpeed.value * 0.5f else verticalSpeed.value

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val wantUp = pkt.inputData.contains(PlayerAuthInputData.WANT_UP)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN)

        val yaw = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val strafe  = inputX * effHoriz
        val forward = inputZ * effHoriz

        // Jitter
        jitterSeed += 0.1
        val jx = sin(jitterSeed) * jitter.value
        val jz = cos(jitterSeed + 1.0) * jitter.value

        // Vertical with Lifeboat bypass
        val vertical = when {
            wantUp -> effVert
            wantDown -> -effVert
            bypassMode.value -> -glideSpeed.value.coerceAtLeast(-0.1f)   // soft fall
            else -> glideSpeed.value
        }

        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ

        val moveX = strafe * cosYaw - forward * sinYaw + jx
        val moveZ = forward * cosYaw + strafe * sinYaw + jz
        val moveY = vertical

        // ── Execute by mode ──────────────────────────
        when (flyMode.value) {
            FlyMode.Vanilla -> {
                val newX = sx + moveX
                val newY = sy + moveY
                val newZ = sz + moveZ
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation, onGround = true)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
            }

            FlyMode.Motion -> {
                val motionPacket = SetEntityMotionPacket().apply {
                    runtimeEntityId = EntityTracker.selfRuntimeId
                    motion = Vector3f.from(
                        moveX,
                        moveY + if (jitterState) 0.03f else -0.03f,
                        moveZ
                    )
                }
                session.clientBound(motionPacket)
                jitterState = !jitterState

                // Small position update for tracker sync
                val newX = sx + moveX * 0.1f
                val newY = sy + moveY * 0.1f
                val newZ = sz + moveZ * 0.1f
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
            }

            FlyMode.Packet -> {
                val newX = sx + moveX
                val newY = sy + moveY
                val newZ = sz + moveZ
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
            }

            FlyMode.Elytra -> {
                val newX = sx + moveX * 1.2f
                val newY = sy + moveY * 0.3f
                val newZ = sz + moveZ * 1.2f
                sendPosition(session, Vector3f.from(newX, newY, newZ), pkt.rotation)
                EntityTracker.selfX = newX
                EntityTracker.selfY = newY
                EntityTracker.selfZ = newZ
            }
        }

        // Grim mode: periodic small fall packet
        if (grimMode.value) {
            grimTicks++
            if (grimTicks >= 20) {
                grimTicks = 0
                val fallPos = Vector3f.from(
                    EntityTracker.selfX,
                    EntityTracker.selfY - 0.05f,
                    EntityTracker.selfZ
                )
                sendPosition(session, fallPos, pkt.rotation)
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
