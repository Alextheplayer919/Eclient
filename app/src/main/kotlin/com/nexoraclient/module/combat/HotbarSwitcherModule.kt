package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HotbarSwitcherModule ("Switcher")
 *
 * Verilen slot araligi icinde hotbar'i otomatik olarak (server-only,
 * yani gercek client ekraninda GORUNMEDEN) ileri-geri gezdirir.
 *
 * NOT (adaptasyon notlari - orijinal ornekten farklar):
 *  - Bu proje Module/InterceptablePacket degil BaseModule/PacketEventBus
 *    kullaniyor; onEnabled/onDisabled yerine onEnable/onDisable, ve ayar
 *    tanimlari intValue/boolValue degil BaseModule'un int()/bool()
 *    yardimcilariyla yapiliyor.
 *  - Paket bazli (beforePacketBound) tetikleme yerine tick-loop kullanildi:
 *    paket akisi duzensiz/patlamali olabildigi icin (ozellikle dusuk paket
 *    trafigi olan anlarda) switchDelay'e gercekten sadik kalan bir
 *    zamanlayici, "her giden pakette kontrol et" yaklasimindan daha tutarli
 *    sonuc verir.
 *  - Elle PlayerHotbarPacket kurup session.clientBound(...) ile client'a
 *    GERI gondermek yerine InventoryUtil.sendHotbarSelect(session, slot)
 *    kullanildi: bu fonksiyon (a) session.serverBound kullanir, yani
 *    degisiklik SADECE sunucuya bildirilir, gercek ekranda hotbar secimi
 *    ASLA gorunur sekilde degismez (sessiz switcher - amaci bu), ve
 *    (b) MobEquipmentPacket ile o slottaki GERCEK itemi gonderir; sabit
 *    ItemData.AIR gondermek CrystalAura/AnchorAura'da daha once tam olarak
 *    bu yuzden "yerlestirildi diyor ama hicbir sey olmuyor" bug'ina yol
 *    acmisti (bkz. InventoryUtil.sendHotbarSelect ustundeki yorum) - o
 *    hatayi burada tekrarlamamak icin ayni merkezi fonksiyon kullanildi.
 *  - EntityTracker.selfHotbarSlot bu paketi biz gonderdigimiz icin server
 *    onayindan (PlayerHotbarPacket client->server yakalanmasi) once manuel
 *    guncelleniyor; aksi halde CrystalAura gibi "aktif hotbar" okuyan diger
 *    moduller bir sonraki gercek pakete kadar eski slotu gorurdu.
 */
class HotbarSwitcherModule : BaseModule(
    name        = "Switcher",
    category    = ModuleCategory.COMBAT,
    description = "Hotbar'i sunucu tarafinda sessizce belirli slotlar arasinda gezdirir"
) {

    private val startSlot   = int("StartSlot",   0,   0, 8)
    private val endSlot     = int("EndSlot",     8,   0, 8)
    private val switchDelay = int("SwitchDelay", 100, 10, 1000)
    private val loopMode    = bool("Loop",       true)
    private val reverseMode = bool("Reverse",    false)
    private val shortcut    = bool("Shortcut",   false)

    @Volatile private var currentSlot = 0
    @Volatile private var direction = 1
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()

        // FIX: start/end kullanici ayarlarindan gelip Reverse acikken
        // start > end gibi celiskili bir konfigurasyon olusabilir; bu
        // durumda ileri-geri sonsuz "tek slotta kilitlenme" olmasin diye
        // araligi normalize ediyoruz (kucuk olan gercek start, buyuk olan
        // gercek end kabul edilir).
        val lo = minOf(startSlot.value, endSlot.value)
        val hi = maxOf(startSlot.value, endSlot.value)

        direction = if (reverseMode.value) -1 else 1
        currentSlot = (if (direction > 0) lo else hi).coerceIn(0, 8)

        PacketEventBus.currentSession?.let { switchToSlot(it, currentSlot) }

        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                // Ayarlar calisirken degistirilmis olabilir, her turda
                // taze araligi al (StartSlot/EndSlot canli guncellenebilir).
                val lo = minOf(startSlot.value, endSlot.value)
                val hi = maxOf(startSlot.value, endSlot.value)

                val session = PacketEventBus.currentSession
                if (session != null) {
                    advanceSlot(lo, hi)
                    switchToSlot(session, currentSlot)
                }
            }
            delay(switchDelay.value.toLong())
        }
    }

    private fun advanceSlot(lo: Int, hi: Int) {
        currentSlot += direction

        if (currentSlot > hi) {
            currentSlot = if (loopMode.value) lo else hi.also { direction = -1 }
        } else if (currentSlot < lo) {
            currentSlot = if (loopMode.value) hi else lo.also { direction = 1 }
        }
    }

    private fun switchToSlot(session: RubidiumRelaySession, slot: Int) {
        InventoryUtil.sendHotbarSelect(session, slot)
        // Kendi gonderdigimiz sessiz slot degisikligini hemen yansitiyoruz ki
        // ayni tick icinde calisan diger moduller (ornek: CrystalAura'nin
        // hotbar dump'i, PlacementUtil.prepareItemForUse) guncel slotu gorsun.
        EntityTracker.selfHotbarSlot = slot
    }
}
