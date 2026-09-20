package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

class Speed : BaseModule(
    name        = "Speed",
    category    = ModuleCategory.MOVEMENT,
    description = "Yürüme hızını artırır (Ability veya Motion modu)"
) {
    enum class Mode { Ability, Motion }

    private val mode         = enum ("Mode",       Mode.Motion)
    private val multiplier   = float("Multiplier", 1.5f, 1.0f, 5.0f) // Motion modu için
    private val walkSpeedVal = float("Walk Speed",  0.2f, 0.1f, 1.0f) // Ability modu için
    private val motionInterval = int("Motion Delay", 55, 15, 150) // Motion modu için

    @Volatile private var lastSession: RubidiumRelaySession? = null
    @Volatile private var abilitiesSent = false
    @Volatile private var lastMotionMs = 0L

    override fun onEnable() {
        super.onEnable()
        abilitiesSent = false
        lastMotionMs = 0L
    }

    override fun onDisable() {
        super.onDisable()
        if (abilitiesSent) {
            lastSession?.let { resetAbilities(it) }
            abilitiesSent = false
        }
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        when (mode.value) {
            Mode.Ability -> applyAbilitySpeed(event.session)
            Mode.Motion  -> applyMotionSpeed(pkt, event.session)
        }
    }

    private fun applyAbilitySpeed(session: RubidiumRelaySession) {
        if (abilitiesSent) return
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
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
                        Ability.WALK_SPEED
                    )
                )
                walkSpeed = walkSpeedVal.value
                flySpeed  = 0.05f
            })
        }
        session.clientBound(packet)
        abilitiesSent = true
    }

    private fun resetAbilities(session: RubidiumRelaySession) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.VISITOR
            commandPermission = CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
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
                        Ability.WALK_SPEED
                    )
                )
                walkSpeed = 0.1f
                flySpeed  = 0.05f
            })
        }
        session.clientBound(packet)
    }

    private fun applyMotionSpeed(pkt: PlayerAuthInputPacket, session: RubidiumRelaySession) {
        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        if (inputX == 0f && inputZ == 0f) return

        // FIX: throttle yoktu - normal yürüme sırasında HER
        // PlayerAuthInputPacket'te (saniyede onlarca kez, Jetpack'te
        // bulunan aynı eksik throttle sorunu) bir motion paketi gönderiliyordu.
        val now = System.currentTimeMillis()
        if (now - lastMotionMs < motionInterval.value) return
        lastMotionMs = now

        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        // Taban hızın üstüne eklenecek ekstra itki (multiplier 1.0 = itki yok)
        val boost   = (multiplier.value - 1f) * 0.15f
        val strafe  = inputX * boost
        val forward = inputZ * boost

        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(motionX, 0f, motionZ)
        })
    }
}
