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
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class CreativeFly : BaseModule(
    name        = "CreativeFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Ability tabanlı uçuş — FlyModule ile aynı mekanizma: sadece dikey motion (Jump/Sneak), yatay hareket vanilla WASD'a bırakılır"
) {
    private val flySpeed  = float("Fly Speed",  0.5f, 0.1f, 1.5f)
    private val walkSpeed = float("Walk Speed", 0.1f, 0.02f, 0.5f)
    // FIX: eskiden Timer modülüne (PvP Timer ayarı) bağımlıydı, Timer
    // kapalıyken koruma hiç çalışmıyordu. Artık bağımsız kendi ayarları var.
    private val antiRubberband    = bool ("Anti Rubber-band", true)
    private val rubberbandMaxStep = float("Max Step",         0.9f, 0.3f, 2.0f)
    private val rubberbandDelayMs = int  ("Step Delay (ms)",  12,   2,   40)

    @Volatile private var lastSession: RubidiumRelaySession? = null
    @Volatile private var canFly = false

    // Anti rubber-band: bağımsız RubberbandGuard, kendi state'ini kendi tutar.
    private val rubberbandGuard = RubberbandGuard(scope)

    override fun onEnable() {
        super.onEnable()
        canFly = false
        rubberbandGuard.reset()
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { sendAbilities(it, false) }
        canFly = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet

        // Client'ın kendi FLYING isteği ve sunucunun ability paketleri bizim spoof
        // ettiğimiz durumu bozabilir — ikisini de koşulsuz engelliyoruz (FlyModule
        // ile aynı davranış, artık ayrı bir "Keep Alive" ayarı yok).
        if (pkt is RequestAbilityPacket && pkt.ability == Ability.FLYING) {
            event.cancel()
            return
        }
        if (pkt is UpdateAbilitiesPacket) {
            event.cancel()
            return
        }

        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        if (!canFly) {
            sendAbilities(event.session, true)
            canFly = true
        }

        rubberbandGuard.guard(event, pkt, antiRubberband.value, rubberbandMaxStep.value, rubberbandDelayMs.value.toLong())

        // Dikey hareket: Jump=yukarı, Sneak=aşağı. Yatay hareket tamamen vanilla
        // WASD fiziğine bırakılıyor — MotionFly'daki gibi tam 3D motion override yok,
        // ekstra timer/jitter da yok (doğal PlayerAuthInputPacket hızında çalışır).
        var verticalMotion = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.JUMPING)) {
            verticalMotion = flySpeed.value
        } else if (pkt.inputData.contains(PlayerAuthInputData.SNEAKING)) {
            verticalMotion = -flySpeed.value
        }

        if (verticalMotion != 0f) {
            event.session.clientBound(SetEntityMotionPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                motion = Vector3f.from(0f, verticalMotion, 0f)
            })
        }
    }

    private fun sendAbilities(session: RubidiumRelaySession, enabled: Boolean) {
        val fs = if (enabled) flySpeed.value else 0.05f
        val ws = walkSpeed.value

        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
            commandPermission = if (enabled) CommandPermission.OWNER  else CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val values = mutableListOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
                if (enabled) {
                    values += Ability.MAY_FLY
                    values += Ability.FLYING
                    values += Ability.OPERATOR_COMMANDS
                }
                abilityValues.addAll(values.toTypedArray())

                this.walkSpeed = ws
                this.flySpeed  = fs
            })
        }
        session.clientBound(packet)
    }

    // Anti rubber-band mantığı artık paylaşılan RubberbandGuard sınıfında
    // (bkz. com.rubidiumclient.utils.RubberbandGuard) - Timer modülüne
    // bağımlılık yok, kendi bağımsız ayarlarımızla çalışıyor.
}
