package com.rubidiumclient.events

import com.rubidiumclient.core.relay.RubidiumRelaySession
import java.util.concurrent.CopyOnWriteArrayList

object PacketEventBus {

    private val listeners = CopyOnWriteArrayList<PacketListener>()

    // FIX: publish() her paket için (iki yönde, saniyede yüzlerce kez)
    // listeners.toArray() çağırıyordu — CopyOnWriteArrayList.toArray() her
    // seferinde YENİ bir dizi kopyalıyor (Arrays.copyOf), yani her paket
    // başına ekstra allocation + GC baskısı demek. register/unregister
    // zaten nadir (sadece modül aç/kapa anı) olduğu için, diziyi sadece o an
    // yeniden oluşturup volatile bir referansta önbelleğe alıyoruz; publish()
    // artık kopyasız doğrudan bu referansı okuyor.
    @Volatile private var snapshot: Array<PacketListener> = emptyArray()

    private fun rebuildSnapshot() {
        snapshot = listeners.toTypedArray()
    }

    @Volatile var currentSession: RubidiumRelaySession? = null
        private set

    fun setSession(session: RubidiumRelaySession?) {
        currentSession = session
    }

    fun register(l: PacketListener) {
        if (listeners.contains(l)) return
        listeners.add(l)
        listeners.sortBy { it.priority }
        rebuildSnapshot()
    }

    fun unregister(l: PacketListener) {
        listeners.remove(l)
        rebuildSnapshot()
    }

    fun clear() {
        listeners.clear()
        rebuildSnapshot()
        currentSession = null
    }

    fun publish(event: PacketEvent) {
        val snap = snapshot

        // Read the eating state once per packet, not once per listener, so every
        // listener sees the same answer for this packet.
        val eating = try {
            com.rubidiumclient.core.EatingGuard.isEating
        } catch (_: Exception) {
            false
        }
        if (eating != eatingAtLastPublish) {
            eatingAtLastPublish = eating
            try {
                com.rubidiumclient.core.EatingGuard.noteTransition(eating)
            } catch (_: Exception) {
            }
        }

        for (l in snap) {
            // Combat holds off while the player is eating, then resumes on its
            // own once the bite finishes. Defaults to false, so non-combat
            // listeners — including EntityTracker, which is what keeps
            // selfUsingItem up to date — are never paused. Pausing that one
            // would stop the eating state from ever clearing.
            if (eating && l.pauseWhileEating) continue

            try { l.onPacket(event) } catch (_: Exception) {}
            // Only break if packet is CANCELLED (rejected completely)
            // If replacementPacket is set, continue to next listener
            // so they can read the modified packet and apply their changes
            if (event.isCancelled) break
        }
    }

    @Volatile private var eatingAtLastPublish: Boolean = false

    fun post(event: PacketEvent) = publish(event)

    val listenerCount: Int get() = listeners.size

    fun getListeners(): List<PacketListener> = listeners.toList()

    interface PacketListener {
        val priority: Int get() = 100

        /**
         * When true, onPacket is skipped while the player is eating (see
         * EatingGuard). Defaults to false so existing listeners are unaffected;
         * BaseModule turns it on for the COMBAT category only.
         */
        val pauseWhileEating: Boolean get() = false

        fun onPacket(event: PacketEvent)
    }
}
