package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * HitboxModule ("Hitbox")
 *
 * Hedeflerin CLIENT tarafinda gorunen hitbox boyutunu buyuterek elle
 * (manuel tiklama ile) vurma isabetini artirir. Sunucunun kendi hit-test
 * mantigini degistirmez, sadece gercek client'in kendi ekranindaki
 * render/tiklama boyutunu etkiler.
 *
 * Referans dosyadan farklar (guclendirme):
 *  - Referans HANGI entity'lerin genisletildigini hic TAKIP ETMIYORDU;
 *    onDisabled() sadece "su an isTarget() olanlari" normale donduruyordu.
 *    Bu, oyuncu botluktan gercek oyuncuya gectiyse / menzilden ciktiysa
 *    genisletilmis hitbox'i SONSUZA KADAR unutulmus/duzeltilmemis birakirdi.
 *    Artik genisletilen HER runtimeId ayri takip ediliyor (expandedIds) ve
 *    hem menzilden/hedef-listesinden ciktiginda hem modul kapatildiginda
 *    TEK TEK normale donduruluyor.
 *  - Referans her `PlayerAuthInputPacket`'te (tickExists % 40 kontrolüyle,
 *    paket akisina bagli, duzensiz zamanlama) calisiyordu. Artik bagimsiz
 *    bir tick-loop (sabit interval) kullaniliyor — paket sikligindan bagimsiz,
 *    tutarli guncelleme.
 *  - Referanstaki "particle ile hitbox gorsellestirme" ozelligi ISLEVSIZDI:
 *    hesaplanan x/z koordinatlari hic kullanilmiyor, ayni LOVE_PARTICLES
 *    efekti entity'nin GERCEK pozisyonunda particleCount kadar (ayni
 *    noktada ustuste) tekrar tekrar gonderiliyordu — hem gorsel olarak bir
 *    "kutu" cizmiyor hem de gereksiz paket trafigi yaratiyordu. Bu ozellik
 *    kaldirildi; gercek bir kutu-cizim paketi (custom outline) bu protokol
 *    seviyesinde yok, o yuzden yanlis/eksik bir ozelligi oldugu gibi
 *    tasimak yerine cikarmak daha dogru bir tercih.
 */
class HitboxModule : BaseModule(
    name        = "Hitbox",
    category    = ModuleCategory.COMBAT,
    description = "Hedeflerin hitbox boyutunu (sadece kendi ekraninda) büyütür, elle vurma isabetini artırır"
) {

    companion object {
        private const val DEFAULT_WIDTH = 0.6f
        private const val DEFAULT_HEIGHT = 1.8f
    }

    private val hitboxWidth      = float("Width",  1.5f, 0.5f, 12f)
    private val hitboxHeight     = float("Height", 1.5f, 0.5f, 12f)
    private val playersOnly      = bool ("Players Only", true)
    private val includeMobs      = bool ("Include Mobs", false)
    private val range            = float("Range", 12f, 2f, 32f)
    private val updateIntervalMs = int  ("Update Interval", 500, 100, 2000)
    private val shortcut = bool("Shortcut", false)

    private var tickJob: Job? = null
    private val expandedIds = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var lastAppliedWidth = -1f
    @Volatile private var lastAppliedHeight = -1f

    override fun onEnable() {
        super.onEnable()
        expandedIds.clear()
        lastAppliedWidth = -1f
        lastAppliedHeight = -1f
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        PacketEventBus.currentSession?.let { resetAll(it) }
        expandedIds.clear()
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                PacketEventBus.currentSession?.let { applyToTargets(it) }
            }
            delay(updateIntervalMs.value.toLong())
        }
    }

    private fun applyToTargets(session: RubidiumRelaySession) {
        val targets = EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isTarget()
        }
        val currentIds = targets.map { it.runtimeId }.toHashSet()

        // Artik menzil disina cikan / hedef olmaktan cikan entity'lerin
        // hitbox'ini normale donduruyoruz.
        val toReset = expandedIds.filter { it !in currentIds }
        for (id in toReset) {
            sendHitbox(session, id, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            expandedIds.remove(id)
        }

        // LAG FIX: eskiden zaten genişletilmiş hedeflere de HER interval'de
        // (varsayılan 500ms) tekrar SetEntityDataPacket gönderiliyordu —
        // değer değişmediği halde gereksiz paket trafiği. Artık sadece YENİ
        // hedeflere veya Width/Height ayarı değiştiyse gönderiliyor.
        val settingsChanged = hitboxWidth.value != lastAppliedWidth || hitboxHeight.value != lastAppliedHeight
        for (t in targets) {
            val isNew = expandedIds.add(t.runtimeId)
            if (isNew || settingsChanged) {
                sendHitbox(session, t.runtimeId, hitboxWidth.value, hitboxHeight.value)
            }
        }
        lastAppliedWidth = hitboxWidth.value
        lastAppliedHeight = hitboxHeight.value
    }

    private fun sendHitbox(session: RubidiumRelaySession, runtimeId: Long, w: Float, h: Float) {
        val metadata = EntityDataMap()
        metadata.put(EntityDataTypes.WIDTH, w)
        metadata.put(EntityDataTypes.HEIGHT, h)
        metadata.put(EntityDataTypes.SCALE, 1.0f)
        session.clientBound(SetEntityDataPacket().apply {
            this.runtimeEntityId = runtimeId
            this.metadata = metadata
        })
    }

    private fun resetAll(session: RubidiumRelaySession) {
        for (id in expandedIds) sendHitbox(session, id, DEFAULT_WIDTH, DEFAULT_HEIGHT)
    }

    private fun EntityTracker.TrackedEntity.isTarget(): Boolean = when {
        isPlayer  -> playersOnly.value
        isHostile -> includeMobs.value
        else      -> false
    }
}
