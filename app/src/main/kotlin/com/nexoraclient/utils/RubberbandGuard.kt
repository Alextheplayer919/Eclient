package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.ceil
import kotlin.math.sqrt

// FIX (Timer modülü bağımlılığı kaldırıldı): BypassFly/CreativeFly/MotionFly'ın
// üçü de eskiden anti-rubber-band korumasını TimerPvP (ayrı bir "Timer" modülünün
// "PvP Timer" ayarı) üzerinden alıyordu. Timer modülü kapalıyken (ki varsayılan
// budur, ve fly modüllerini kullanan biri Timer'ı hiç açmayabilir) koruma tamamen
// devre dışı kalıyordu — enjekte edilen yüksek hız tek tick'te "meşru" hareket
// sınırının çok üstünde bir sıçramaya yol açıyor, sunucu bunu geçersiz sayıp
// oyuncuyu eski pozisyona geri atıyordu (rubber-band).
//
// Bu sınıf, TimerPvP.dispatch() ile AYNI adımlama mantığını (büyük sıçramayı
// "meşru" boyutta küçük adımlara bölüp art arda kısa aralıklarla gönderme)
// hiçbir dış modüle bağımlı olmadan, her fly modülünün KENDİ ayarlarıyla
// (kendi enabled/maxStep/stepDelayMs) çalıştırır. Davranış TimerPvP ile
// birebir aynı — sadece kaynağı artık paylaşılan bir modül değil, çağıran
// modülün kendi state'i.
//
// Kullanım: her fly modülü kendi RubberbandGuard örneğini (constructor'da
// yalnızca coroutine scope alır) tutar, her PlayerAuthInputPacket'ta guard(...)
// çağırır.
class RubberbandGuard(private val scope: CoroutineScope) {

    private var lastReportedX = 0f
    private var lastReportedY = 0f
    private var lastReportedZ = 0f
    private var reportedInit  = false

    fun reset() {
        reportedInit = false
    }

    /**
     * @param enabled       koruma açık mı (modülün kendi ayarı)
     * @param maxStep       tek tick'te "meşru" kabul edilen maksimum pozisyon değişimi
     * @param stepDelayMs   adımlar arası gecikme (ms)
     */
    fun guard(
        event: PacketEvent,
        pkt: PlayerAuthInputPacket,
        enabled: Boolean,
        maxStep: Float,
        stepDelayMs: Long
    ) {
        val truePos = pkt.position

        if (!reportedInit) {
            lastReportedX = truePos.x; lastReportedY = truePos.y; lastReportedZ = truePos.z
            reportedInit = true
            return
        }

        if (!enabled) {
            lastReportedX = truePos.x; lastReportedY = truePos.y; lastReportedZ = truePos.z
            return
        }

        val dx = truePos.x - lastReportedX
        val dy = truePos.y - lastReportedY
        val dz = truePos.z - lastReportedZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val step = maxStep.coerceAtLeast(0.1f)

        if (dist <= step) {
            lastReportedX = truePos.x; lastReportedY = truePos.y; lastReportedZ = truePos.z
            return
        }

        val t = step / dist
        val clampedX = lastReportedX + dx * t
        val clampedY = lastReportedY + dy * t
        val clampedZ = lastReportedZ + dz * t

        pkt.position = Vector3f.from(clampedX, clampedY, clampedZ)
        event.cancelAndReplace(pkt)

        val fromX = clampedX; val fromY = clampedY; val fromZ = clampedZ
        val toX = truePos.x;  val toY = truePos.y;  val toZ = truePos.z
        val yaw = pkt.rotation.y; val pitch = pkt.rotation.x
        val onGround = EntityTracker.selfOnGround
        val session = event.session

        lastReportedX = toX; lastReportedY = toY; lastReportedZ = toZ

        scope.launch {
            try {
                dispatchSteps(session, fromX, fromY, fromZ, toX, toY, toZ, yaw, pitch, onGround, step, stepDelayMs)
            } catch (_: Exception) {}
        }
    }

    private suspend fun dispatchSteps(
        session: RubidiumRelaySession,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        yaw: Float, pitch: Float,
        onGround: Boolean,
        step: Float,
        stepDelayMs: Long
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)

        if (dist <= step) {
            send(session, toX, toY, toZ, yaw, pitch, onGround, mirrorToClient = true)
            return
        }

        val steps = ceil(dist / step).toInt().coerceIn(1, 10)
        val delayMs = stepDelayMs.coerceIn(1L, 100L)

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val cx = fromX + dx * t
            val cy = fromY + dy * t
            val cz = fromZ + dz * t
            send(session, cx, cy, cz, yaw, pitch, onGround, mirrorToClient = i == steps)
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
    }
}
