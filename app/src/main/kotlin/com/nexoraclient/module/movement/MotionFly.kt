
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
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

class MotionFly : BaseModule(
    name        = "MotionFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Motion paketi tabanlı uçuş"
) {
    private val horizontalSpeed = float("Horizontal Speed", 3.5f, 0.5f, 10.0f)
    private val verticalSpeed   = float("Vertical Speed",   1.5f, 0.5f, 5.0f)
    private val glideSpeed      = float("Glide Speed",      0.1f, -0.01f, 1.0f)
    private val motionInterval  = float("Delay",            50.0f, 10.0f, 100.0f)
    private val shortcut        = bool ("Shortcut",         false)
    // FIX: eskiden Timer modülüne (PvP Timer ayarı) bağımlıydı, Timer
    // kapalıyken koruma hiç çalışmıyordu. Artık bağımsız kendi ayarları var.
    private val antiRubberband    = bool ("Anti Rubber-band", true)
    private val rubberbandMaxStep = float("Max Step",         0.9f, 0.3f, 2.0f)
    private val rubberbandDelayMs = int  ("Step Delay (ms)",  12,   2,   40)

    @Volatile private var lastMotionTime = 0L
    @Volatile private var jitterState    = false
    @Volatile private var canFly         = false
    @Volatile private var lastSession    : RubidiumRelaySession? = null

    // Anti rubber-band: bağımsız RubberbandGuard, kendi state'ini kendi tutar.
    private val rubberbandGuard = RubberbandGuard(scope)

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
        rubberbandGuard.reset()
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
        applyFlyAbilities(true, event.session)

        val now = System.currentTimeMillis()
        rubberbandGuard.guard(event, pkt, antiRubberband.value, rubberbandMaxStep.value, rubberbandDelayMs.value.toLong())

        if (now - lastMotionTime < motionInterval.value) return

        val vertical = when {
            pkt.inputData.contains(PlayerAuthInputData.WANT_UP)   -> verticalSpeed.value
            pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN) -> -verticalSpeed.value
            else -> glideSpeed.value
        }

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val strafe  = inputX * horizontalSpeed.value
        val forward = inputZ * horizontalSpeed.value

        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                motionX,
                vertical + if (jitterState) 0.05f else -0.05f,
                motionZ
            )
        }

        event.session.clientBound(motionPacket)
        jitterState = !jitterState
        lastMotionTime = System.currentTimeMillis()
    }

    private fun applyFlyAbilities(enabled: Boolean, session: RubidiumRelaySession) {
        if (canFly == enabled) return
        val id = EntityTracker.selfUniqueId
        flyPacket.uniqueEntityId = id
        resetPacket.uniqueEntityId = id
        session.clientBound(if (enabled) flyPacket else resetPacket)
        canFly = enabled
    }

    // Anti rubber-band mantığı artık paylaşılan RubberbandGuard sınıfında
    // (bkz. com.rubidiumclient.utils.RubberbandGuard) - Timer modülüne
    // bağımlılık yok, kendi bağımsız ayarlarımızla çalışıyor.
}
