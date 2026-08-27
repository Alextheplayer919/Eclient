
package com.rubidiumclient.module.world

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket

/**
 * WeatherControllerModule ("WeatherController")
 *
 * TAMAMEN client-side gorsel bir illuzyon: sunucunun gercek hava durumunu
 * degistirmez, sadece kendi ekraninda hangi havanin gorunecegini kontrol eder.
 *
 * NOT (adaptasyon notlari - orijinal ornekten farklar):
 *  - Bu proje Module/InterceptablePacket degil BaseModule/PacketEventBus
 *    kullaniyor; onEnabled/onDisabled yerine onEnable/onDisable, ayar
 *    tanimlari boolValue degil BaseModule'un enum()/int() yardimcilariyla
 *    yapiliyor.
 *  - 3 ayri boolValue (clear/rain/thunderstorm) yerine tek bir EnumSetting
 *    kullanildi: orijinal tasarimda ucu de ayni anda true olabilirdi (celiskili
 *    state, sadece when() sirasi yuzunden "clear" hep kazaniyordu) - enum ile
 *    bu yapisal olarak imkansiz hale geliyor.
 *  - PlayerAuthInputPacket'e "binip" her 100ms'de bir zorla yeniden gondermek
 *    yerine iki mekanizma birlikte kullanildi:
 *      1) Sunucudan gelen GERCEK hava paketleri (SERVER_TO_CLIENT
 *         LevelEventPacket, START/STOP_RAINING/THUNDERSTORM) iptal ediliyor
 *         (event.cancel()) - boylece sunucunun kendi hava senkronu bizim
 *         zorladigimiz havayi geri almiyor.
 *      2) Dusuk frekansli (varsayilan 5sn) bir tick-loop, guvenlik agi olarak
 *         periyodik yeniden gonderim yapiyor (ornegin modul acilir acilmaz
 *         hemen bir kez, sonra baglanti/chunk degisimi gibi durumlarda
 *         senkron kaybi olursa toparlamak icin).
 *    Bu, orijinaldeki gibi hareket paketine bagli olmadigi icin oyuncu hic
 *    kipirdamasa bile calisir, ve 100ms yerine cok daha seyrek paket
 *    gonderdigi icin gereksiz trafik yaratmaz.
 *  - position alani gercek oyuncu konumu yerine Vector3f.ZERO kullanildi:
 *    START/STOP_RAINING ve START/STOP_THUNDERSTORM seviye-geneli (global)
 *    olaylardir, pozisyon alani bu olay turleri icin islevsel degildir.
 */
class WeatherControllerModule : BaseModule(
    name        = "WeatherController",
    category    = ModuleCategory.WORLD,
    description = "Kendi ekranindaki havayi (acik/yagmurlu/firtinali) sunucudan bagimsiz olarak sabitler — sadece gorsel, sunucunun gercek havasini degistirmez"
) {

    enum class WeatherType { CLEAR, RAIN, THUNDERSTORM }

    private val weather = enum("Weather", WeatherType.CLEAR)
    private val resyncIntervalMs = int("ResyncInterval", 5000, 1000, 20000)
    private val shortcut = bool("Shortcut", false)

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        PacketEventBus.currentSession?.let { applyWeather(it, weather.value) }
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        // Modul kapanirken zorladigimiz havayi temizliyoruz - sunucunun
        // bir sonraki gercek hava paketi normal sekilde uygulanabilsin.
        PacketEventBus.currentSession?.let { clearWeather(it) }
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isServerToClient) return
        val pkt = event.packet as? LevelEventPacket ?: return
        when (pkt.type) {
            LevelEvent.START_RAINING, LevelEvent.STOP_RAINING,
            LevelEvent.START_THUNDERSTORM, LevelEvent.STOP_THUNDERSTORM -> {
                // Sunucunun kendi hava senkronunu bastır — bizim zorladigimiz
                // hava tickLoop tarafindan zaten uygulaniyor/korunuyor.
                event.cancel()
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                PacketEventBus.currentSession?.let { applyWeather(it, weather.value) }
            }
            delay(resyncIntervalMs.value.toLong())
        }
    }

    private fun applyWeather(session: com.rubidiumclient.core.relay.RubidiumRelaySession, type: WeatherType) {
        clearWeather(session)
        when (type) {
            WeatherType.CLEAR -> { /* zaten clearWeather ile temizlendi */ }
            WeatherType.RAIN -> {
                session.clientBound(LevelEventPacket().apply {
                    this.type = LevelEvent.START_RAINING
                    position  = Vector3f.ZERO
                    data      = 10000
                })
            }
            WeatherType.THUNDERSTORM -> {
                session.clientBound(LevelEventPacket().apply {
                    this.type = LevelEvent.START_RAINING
                    position  = Vector3f.ZERO
                    data      = 10000
                })
                session.clientBound(LevelEventPacket().apply {
                    this.type = LevelEvent.START_THUNDERSTORM
                    position  = Vector3f.ZERO
                    data      = 10000
                })
            }
        }
    }

    private fun clearWeather(session: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        session.clientBound(LevelEventPacket().apply {
            type     = LevelEvent.STOP_RAINING
            position = Vector3f.ZERO
            data     = 0
        })
        session.clientBound(LevelEventPacket().apply {
            type     = LevelEvent.STOP_THUNDERSTORM
            position = Vector3f.ZERO
            data     = 0
        })
    }
}
