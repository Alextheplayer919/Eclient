package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.combat.TPAura
import com.rubidiumclient.module.movement.Timer
import kotlinx.coroutines.delay
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import kotlin.math.ceil
import kotlin.math.sqrt

// TPAura / AuraV3 gibi modüller Horizontal/Vertical Speed ile büyük tek-paket
// pozisyon sıçraması yapıyor (3-7 blok). Bu, gerçek oyuncu hareketinin
// (yürüyüş ~0.2 blok/tick, sprint ~0.28 blok/tick) çok üstünde olduğundan
// sunucu bunu geçersiz sayıp rubber-band (geri atma) yapıyor.
//
// PC Timer hilesinin gerçek mantığı: tick döngüsünü hızlandırıp NORMAL
// boyutta ama daha SIK paket göndermek. Burada aynı etkiyi paket seviyesinde
// taklit ediyoruz — tek büyük sıçramayı "meşru" boyutta küçük adımlara
// bölüp art arda çok kısa aralıklarla gönderiyoruz. Sunucu her paketi tek
// başına normal hız olarak görür, toplam sıçrama yine hızlı gerçekleşir.
object TimerPvP {

    private val timerModule: Timer? get() = ModuleManager.byName("Timer") as? Timer

    val enabled: Boolean get() = timerModule?.let { it.isEnabled && it.pvpModeEnabled } == true
    val maxStep: Float   get() = timerModule?.pvpMaxStep ?: 0.9f
    val stepDelayMs: Long get() = timerModule?.pvpStepDelayMs ?: 12L

    // TPAura / AuraV3 ham session.serverBound(movePacket) yerine bunu
    // çağırır. Timer'ın PvP modu kapalıysa (veya mesafe zaten küçükse)
    // davranış birebir eskisiyle aynı: tek paket, anında.
    suspend fun dispatch(
        session        : RubidiumRelaySession,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        yaw: Float, pitch: Float,
        onGround       : Boolean,
        mirrorToClient : Boolean = true
    ) {
        if (!enabled) {
            send(session, toX, toY, toZ, yaw, pitch, onGround, mirrorToClient)
            return
        }

        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val step = maxStep.coerceAtLeast(0.1f)

        if (dist <= step) {
            send(session, toX, toY, toZ, yaw, pitch, onGround, mirrorToClient)
            return
        }

        // Adım sayısı sabit bir üst sınıra kapatılıyor — aşırı uzun mesafede
        // (örn. ilk yaklaşma anı) yüzlerce mikro-paket göndermek yerine
        // makul sayıda, biraz daha büyük adımlarla gidiyoruz.
        val steps = ceil(dist / step).toInt().coerceIn(1, 10)
        val delayMs = stepDelayMs.coerceIn(1L, 100L)

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val cx = fromX + dx * t
            val cy = fromY + dy * t
            val cz = fromZ + dz * t
            send(session, cx, cy, cz, yaw, pitch, onGround, mirrorToClient && i == steps)
            if (i != steps) delay(delayMs)
        }
    }

    private fun send(
        session: RubidiumRelaySession,
        x: Float, y: Float, z: Float,
        yaw: Float, pitch: Float,
        onGround: Boolean,
        mirrorToClient: Boolean
    ) {
        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(x, y, z)
            rotation              = Vector3f.from(pitch, yaw, yaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(movePacket)
        if (mirrorToClient) session.clientBound(movePacket)
        EntityTracker.selfX = x; EntityTracker.selfY = y; EntityTracker.selfZ = z
        TPAura.notifyExternalPositionOverride()
    }
}
