
package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

/**
 * AirJump — havadayken (selfOnGround = false) zıplama tuşuna her basışta
 * (basılı tutmada değil, her yeni basışta — kenar/edge tespiti) yukarı yönlü
 * bir SetEntityMotionPacket enjekte eder. BypassFly/MotionFly ile aynı
 * mantık: paket CLIENT-BOUND gönderiliyor, yani gerçek Minecraft istemcisinin
 * kendi fizik motoru bu hızı entegre edip SONUCU kendi PlayerAuthInputPacket'i
 * üzerinden sunucuya normal, istemci-otoriter hareket olarak iletiyor — ayrı
 * bir sahte MovePlayerPacket/teleport yok.
 *
 * Yerdeyken (selfOnGround = true) hiçbir şey yapmaz, normal zıplama olduğu
 * gibi çalışır — sadece havadaki EKSTRA zıplamaları enjekte eder.
 */
class AirJump : BaseModule(
    name        = "AirJump",
    category    = ModuleCategory.MOVEMENT,
    description = "Havadayken zıplama tuşuna basınca ekstra yukarı itiş uygular"
) {
    private val jumpPower     = float("Jump Power",      0.42f, 0.1f, 1.2f)
    private val maxAirJumps   = int  ("Max Air Jumps",    1,     1,   10)
    private val infinite      = bool ("Infinite",         false)
    private val cooldownMs    = int  ("Cooldown",         250,   50,  1000)
    private val resetOnGround = bool ("Reset On Ground",  true)

    @Volatile private var jumpsUsed      = 0
    @Volatile private var wasJumpPressed = false
    @Volatile private var lastJumpMs     = 0L
    @Volatile private var wasOnGround    = true

    override fun onEnable() {
        super.onEnable()
        jumpsUsed = 0
        wasJumpPressed = false
        lastJumpMs = 0L
        wasOnGround = EntityTracker.selfOnGround
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        // PlayerAuthInputPacket'te isOnGround alanı YOK (derleme hatasının
        // sebebi buydu) - EntityTracker.handleAuthInput bu paketten ground
        // durumu okumuyor, çünkü protokol bunu bu pakette taşımıyor. Ground
        // bilgisi MovePlayerPacket/MoveEntityAbsolutePacket üzerinden gelir.
        // Bu yüzden EntityTracker.selfOnGround kullanmak zorundayız; teorik
        // bir tick gecikme riski var ama alternatif yok.
        val onGround = EntityTracker.selfOnGround
        if (resetOnGround.value && onGround && !wasOnGround) {
            jumpsUsed = 0
        }
        wasOnGround = onGround

        val jumpPressed = pkt.inputData.contains(PlayerAuthInputData.JUMPING)
        // Kenar tespiti: sadece basılı tutmadan yeni basışta tetiklenir,
        // yoksa tuşu basılı tutarken saniyede onlarca kez tetiklenip
        // gereksiz hız/lag birikir.
        val justPressed = jumpPressed && !wasJumpPressed
        wasJumpPressed = jumpPressed

        if (!justPressed) return
        if (onGround) return // normal zıplama, dokunma

        val now = System.currentTimeMillis()
        if (now - lastJumpMs < cooldownMs.value) return

        if (!infinite.value && jumpsUsed >= maxAirJumps.value) return

        lastJumpMs = now
        jumpsUsed++

        event.session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(0f, jumpPower.value, 0f)
        })
    }
}
