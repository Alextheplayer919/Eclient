package com.rubidiumclient.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kritik enjeksiyon kilidi - artık çok daha hızlı
 */
object CritLock {
    // AtomicBoolean daha hafif
    private val locked = AtomicBoolean(false)

    // FIX: düz mutableMapOf thread-safe DEĞİLDİ. KillAura + KillAuraPro aynı
    // anda (her ikisi de scope.launch ile) her attack'ta bu map'e concurrent
    // getOrPut çağırıyordu. Thread-safe olmayan bir HashMap'e eşzamanlı yazmak
    // internal node zincirini bozabilir; sonuç olarak bazı tick'lerde kilit
    // ya hiç oluşmuyor ya da yanlış davranıyor ve crit enjeksiyonu sessizce
    // atlanıyordu — düşük hasarın büyük ihtimalle asıl kaynağı buydu.
    private val moduleLocks = ConcurrentHashMap<String, Mutex>()

    fun tryAcquire(key: String): Boolean =
        moduleLocks.computeIfAbsent(key) { Mutex() }.tryLock()

    fun release(key: String) {
        moduleLocks[key]?.let { if (it.isLocked) it.unlock() }
    }

    // FIX: tryAcquire() anlık tryLock() — meşgulse hemen vazgeçiyor. KillAura
    // (MovePacket modu) ile Criticals aynı anahtarı paylaştığı için, birinin
    // 5-25ms süren enjeksiyon penceresine diğerinin saldırı paketi denk
    // gelirse crit sessizce kayboluyordu (paket crit'siz gidiyordu). Bu
    // fonksiyon aynı işi yapıyor ama kilit meşgulse kısa bir süre (timeoutMs)
    // bekleyip tekrar deniyor — pencere küçük olduğu için çoğu çakışma bu
    // bekleme içinde çözülüyor, crit nadiren kaybolur.
    suspend fun tryRunWait(key: String, timeoutMs: Long, block: suspend () -> Unit): Boolean {
        val lock = moduleLocks.computeIfAbsent(key) { Mutex() }
        val acquired = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            lock.lock()
            true
        } ?: false
        if (!acquired) return false
        try {
            block()
        } finally {
            lock.unlock()
        }
        return true
    }

    suspend fun tryRun(block: suspend () -> Unit) {
        // AtomicBoolean ile hızlı kontrol
        if (!locked.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            locked.set(false)
        }
    }
    
    // Modül bazlı kilit (daha az çakışma)
    suspend fun tryRun(moduleName: String, block: suspend () -> Unit) {
        val lock = moduleLocks.computeIfAbsent(moduleName) { Mutex() }
        if (!lock.tryLock()) return
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}