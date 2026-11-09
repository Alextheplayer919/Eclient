
package com.rubidiumclient.module.combat

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.CritLock
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir (ULTRA HIZLI)"
) {
    enum class CritMode { 
        Vanilla,      // Standart 7-packet
        Fast,         // 3-packet hızlı (ÖNERİLEN)
        UltraFast,    // 2-packet çok hızlı
        Packet        // Minimal packet
    }

    companion object {
        // FIX: "crit-injection" keyed mutex — Criticals is now the only
        // consumer, but the key is kept so a future crit-emitting module
        // can share the same lock instead of silently double-writing Y.
        private const val CRIT_LOCK_KEY = "crit-injection"
    }

    private val mode     = enum ("Mode",      CritMode.Fast)
    private val cooldown = int  ("Cooldown",  0, 0, 100)
    private val shortcut = bool ("Shortcut",  false)

    @Volatile private var lastCritMs = 0L

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return

        val isAttack = pkt.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY &&
                       pkt.actionType == 1

        if (!isAttack) return

        val now = System.currentTimeMillis()
        if (now - lastCritMs < cooldown.value) return

        val session = PacketEventBus.currentSession ?: return

        event.cancel()

        scope.launch {
            // BUG FIX: saldırı paketi (pkt) artık her inject* fonksiyonunun
            // İÇİNDE, sahte "havadayım" hareketinden SONRA ama onGround=true
            // reset'inden ÖNCE gönderiliyor. Eskiden reset paketi saldırıdan
            // önce gidiyordu — server saldırıyı aldığında zaten "yerde"
            // görünüyordun, crit şartı (düşerken vurmak) hiç sağlanmıyordu.
            // Bu, damage'ın sessizce normale düşmesinin asıl sebebiydi.
            val acquired = CritLock.tryRunWait(CRIT_LOCK_KEY, timeoutMs = 30L) {
                lastCritMs = now
                when (mode.value) {
                    CritMode.Vanilla    -> injectVanilla(session, pkt)
                    CritMode.Fast       -> injectFast(session, pkt)      // ÖNERİLEN
                    CritMode.UltraFast  -> injectUltraFast(session, pkt)
                    CritMode.Packet     -> injectPacket(session, pkt)
                }
            }
            // Kilit 30ms içinde alınamazsa (çok nadir), crit'siz de olsa
            // orijinal saldırı paketini kaybetme — olduğu gibi ilet.
            if (!acquired) session.serverBound(pkt)
        }
    }

    // FAST: Sadece 3 packet, minimal gecikme. Sıra: yukarı -> düşüyor ->
    // SALDIRI (hâlâ havadayken) -> yere resetle.
    private suspend fun injectFast(s: com.rubidiumclient.core.relay.RubidiumRelaySession, pkt: InventoryTransactionPacket) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(10L)  // 25ms -> 10ms
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = false)
        delay(5L)   // Ekstra küçük delay
        s.serverBound(pkt)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    // ULTRA FAST: 2 packet, max hız. Saldırı hâlâ havadayken (onGround=false) gider.
    private suspend fun injectUltraFast(s: com.rubidiumclient.core.relay.RubidiumRelaySession, pkt: InventoryTransactionPacket) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(5L)
        s.serverBound(pkt)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    private suspend fun injectVanilla(s: com.rubidiumclient.core.relay.RubidiumRelaySession, pkt: InventoryTransactionPacket) {
        // Opsiyonel: sadece 2 ara adım + saldırı + reset
        listOf(0.42f, 0.1f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
            delay(8L)
        }
        s.serverBound(pkt)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    private suspend fun injectPacket(s: com.rubidiumclient.core.relay.RubidiumRelaySession, pkt: InventoryTransactionPacket) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.001f, onGround = false)
        delay(2L)
        s.serverBound(pkt)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }
}
