package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
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

/**
 * NoClip — referans alınan mucheng/MuCute NoClipModule ile aynı temel fikir
 * (Ability.NO_CLIP + dikey motion enjeksiyonu), Rubidium'un kendi
 * BaseModule/PacketEventBus mimarisine uyarlanmış hali. Orijinaldeki
 * `Module`/`InterceptablePacket`/`session.localPlayer` API'leri bu projede
 * yok, o yüzden birebir kopya derlenmezdi — CreativeFly/BypassFly'daki
 * kurulu pattern'lerle (event.session, event.cancel(), EntityTracker) aynı
 * davranışı üretecek şekilde yeniden yazıldı.
 *
 * BypassFly'dan farkı: gerçek pozisyonu sunucudan gizlemiyor (FreeCamera'nın
 * aksine) — sadece bloklardan geçebilme + serbest dikey hareket sağlıyor,
 * ortaya çıkan gerçek pozisyon normal şekilde (istemcinin kendi fiziğinden)
 * sunucuya iletilmeye devam ediyor.
 */
class NoClipModule : BaseModule(
    name        = "NoClip",
    category    = ModuleCategory.MOVEMENT,
    description = "Ability.NO_CLIP ile bloklardan geçme + dikey serbest hareket (Space/Shift)"
) {
    private val moveSpeed = float("Speed", 0.15f, 0.05f, 1.5f)
    private val motionInterval = int("Delay", 55, 15, 150)
    private val shortcut  = bool ("Shortcut", false)

    @Volatile private var noClipActive = false
    @Volatile private var lastSession: RubidiumRelaySession? = null
    @Volatile private var lastMotionMs = 0L

    override fun onEnable() {
        super.onEnable()
        noClipActive = false
        lastMotionMs = 0L
    }

    override fun onDisable() {
        lastSession?.let { disableAbilities(it) }
        noClipActive = false
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet

        // Referanstaki gibi: hem client'ın kendi NO_CLIP isteğini hem de
        // sunucudan gelen ability paketlerini engelliyoruz — ikisi de bizim
        // spoof ettiğimiz durumu ezebilir.
        if (pkt is RequestAbilityPacket && pkt.ability == Ability.NO_CLIP) {
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

        if (!noClipActive) {
            enableAbilities(event.session)
            noClipActive = true
        }

        // Dikey hareket: Jump=yukarı, Sneak=aşağı (referansla birebir aynı
        // input mapping). Yatay hareket, NO_CLIP yetkisiyle gerçek istemcinin
        // kendi fiziği tarafından zaten bloklardan geçirilerek hesaplanıyor.
        var verticalMotion = 0f
        if (pkt.inputData.contains(PlayerAuthInputData.JUMPING)) {
            verticalMotion = moveSpeed.value
        } else if (pkt.inputData.contains(PlayerAuthInputData.SNEAKING)) {
            verticalMotion = -moveSpeed.value
        }

        // FIX: throttle yoktu - Jump/Sneak basılı tutulurken HER
        // PlayerAuthInputPacket'te bir motion paketi gönderiliyordu
        // (Jetpack/ElytraFly'daki aynı eksik throttle sorunu).
        if (verticalMotion != 0f) {
            val now = System.currentTimeMillis()
            if (now - lastMotionMs >= motionInterval.value) {
                lastMotionMs = now
                event.session.clientBound(SetEntityMotionPacket().apply {
                    runtimeEntityId = EntityTracker.selfRuntimeId
                    motion = Vector3f.from(0f, verticalMotion, 0f)
                })
            }
        }
    }

    private fun enableAbilities(session: RubidiumRelaySession) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(
                    arrayOf(
                        Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                        Ability.MAY_FLY, Ability.FLY_SPEED, Ability.WALK_SPEED,
                        Ability.OPERATOR_COMMANDS, Ability.NO_CLIP
                    )
                )
                walkSpeed = 0.1f
                flySpeed  = moveSpeed.value
            })
        }
        session.clientBound(packet)
    }

    private fun disableAbilities(session: RubidiumRelaySession) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(
                    arrayOf(
                        Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                        Ability.FLY_SPEED, Ability.WALK_SPEED, Ability.OPERATOR_COMMANDS
                    )
                )
                walkSpeed = 0.1f
            })
        }
        session.clientBound(packet)
    }
}
