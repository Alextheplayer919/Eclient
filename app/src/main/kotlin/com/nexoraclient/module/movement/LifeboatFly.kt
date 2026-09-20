package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.MathUtil
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

/**
 * LifeboatFly — BypassFly ile aynı temel mekanizma (SetEntityMotionPacket
 * enjeksiyonu, istemci kendi fiziğini hesaplayıp sonucu PlayerAuthInputPacket
 * ile normal şekilde iletir). Farkı: BypassFly anlık maksimum hıza "snap"
 * ederken, bu modül hızı 0'dan hedef hıza doğru RAMP (ivmelenerek) artırıyor
 * ve tuş bırakıldığında da yavaşça sıfıra iniyor — "aniden max hıza fırlama"
 * paterni birçok anti-cheat'in ilk baktığı şeylerden biri.
 *
 * ÖNEMLİ DÜRÜSTLÜK NOTU: Lifeboat'un anti-cheat'inin iç eşiklerine dair elimde
 * özel/gizli bir bilgi yok — bu default değerler "doğal ivmelenme" mantığıyla
 * seçilmiş makul başlangıç noktaları, Lifeboat'a özel test edilip garanti
 * edilmiş sayılar değil. Sunucuda gerçekten deneyip ban/kick alıp almadığına
 * göre Accel/MaxSpeed/Jitter değerlerini kendin ince ayar yapman gerekecek.
 */
class LifeboatFly : BaseModule(
    name        = "LifeboatFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Ramp'li (kademeli ivmelenen) motion-fly — anlık max hıza sıçramayı önler"
) {
    enum class FlyMode { Water, Ground, Air }

    private val mode              = enum ("Mode",                 FlyMode.Air)
    private val maxHorizontalSpeed= float("Max Horizontal Speed",  3.2f,  0.5f, 8.0f)
    private val maxVerticalSpeed  = float("Max Vertical Speed",    1.2f,  0.2f, 4.0f)
    private val accel             = float("Acceleration",          0.18f, 0.02f, 1.0f) // 0..1, her tick hıza uygulanan lerp faktörü
    private val decel             = float("Deceleration",          0.25f, 0.02f, 1.0f)
    private val idleJitter        = float("Idle Jitter",           0.015f,0.0f,  0.1f)
    private val motionInterval    = int  ("Delay",                  50,   15,   150)
    private val grantAbilities    = bool ("Grant Fly Ability",      true)
    private val shortcut          = bool ("Shortcut",               false)

    @Volatile private var lastMotionMs = 0L
    @Volatile private var jitterState  = false
    @Volatile private var abilitiesOn  = false
    @Volatile private var lastSession  : RubidiumRelaySession? = null

    // Ramp durumu — anlık uygulanan hız, hedefe doğru yumuşakça yaklaşıyor.
    @Volatile private var curHoriz = 0f
    @Volatile private var curVert  = 0f

    override fun onEnable() {
        super.onEnable()
        lastMotionMs = 0L
        jitterState  = false
        abilitiesOn  = false
        curHoriz = 0f
        curVert  = 0f
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { applyAbilities(it, false) }
        abilitiesOn = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        // Ground modunda MAY_FLY/FLYING asla verilmiyor — sunucuya "uçuyor"
        // durumu hiç bildirilmiyor, sadece yatay hız artışı uygulanıyor.
        val wantAbilities = grantAbilities.value && mode.value != FlyMode.Ground
        if (wantAbilities != abilitiesOn) applyAbilities(event.session, wantAbilities)

        val now = System.currentTimeMillis()
        if (now - lastMotionMs < motionInterval.value) return
        lastMotionMs = now

        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val hasInput = inputX != 0f || inputZ != 0f

        val wantUp   = pkt.inputData.contains(PlayerAuthInputData.WANT_UP)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN)
        val hasVerticalInput = wantUp || wantDown

        // Yatay hız: girdi varsa hedefe doğru ivmelen (accel), yoksa sıfıra
        // doğru yavaşla (decel) — anlık on/off yerine yumuşak geçiş.
        curHoriz = if (hasInput) {
            MathUtil.lerp(curHoriz, maxHorizontalSpeed.value, accel.value)
        } else {
            MathUtil.lerp(curHoriz, 0f, decel.value)
        }

        val strafe  = inputX * curHoriz
        val forward = inputZ * curHoriz
        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val targetVert = when (mode.value) {
            FlyMode.Ground -> 0f
            FlyMode.Water, FlyMode.Air -> when {
                wantUp   ->  maxVerticalSpeed.value
                wantDown -> -maxVerticalSpeed.value
                else     -> 0f
            }
        }
        curVert = if (hasVerticalInput) {
            MathUtil.lerp(curVert, targetVert, accel.value)
        } else {
            MathUtil.lerp(curVert, flip() * idleJitter.value, decel.value)
        }

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(motionX, curVert, motionZ)
        }
        event.session.clientBound(motionPacket)
    }

    private fun flip(): Float {
        jitterState = !jitterState
        return if (jitterState) 1f else -1f
    }

    private fun applyAbilities(session: RubidiumRelaySession, enabled: Boolean) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
            commandPermission = if (enabled) CommandPermission.OWNER  else CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val values = mutableListOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED, Ability.WALK_SPEED, Ability.VERTICAL_FLY_SPEED
                )
                if (enabled) {
                    values += Ability.MAY_FLY
                    values += Ability.FLYING
                }
                abilityValues.addAll(values.toTypedArray())

                walkSpeed        = 0.1f
                flySpeed         = if (enabled) 0.3f else 0.05f
                verticalFlySpeed = if (enabled) 0.25f else 0.05f
            })
        }
        session.clientBound(packet)
        abilitiesOn = enabled
    }
}
