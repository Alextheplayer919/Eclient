package com.rubidiumclient.agent

import java.util.Locale

/**
 * Measurements of the app <-> agent link, so "is the bridge healthy?" can be answered from the
 * phone (`.camera`) and from `Downloads/baba.txt` instead of guessed at.
 *
 * Pure: no Android, no sockets, injected clock. Written by the agent reader thread, read from the
 * command/overlay threads, so every access is synchronized (cheap: ~20 calls a second).
 */
class BridgeHealth(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Ring(private val cap: Int) {
        private val a = LongArray(cap)
        var n = 0; private set
        private var i = 0
        fun add(v: Long) { a[i] = v; i = (i + 1) % cap; if (n < cap) n++ }
        fun percentile(p: Double): Long? {
            if (n == 0) return null
            val s = a.copyOf(n).also { it.sort() }
            return s[((n - 1) * p).toInt()]
        }
    }

    private val gaps = Ring(128)          // ms between consecutive state pushes
    private var lastPushAt = 0L
    private var ewmaGapMs = 0.0

    var connects = 0; private set
    var disconnects = 0; private set
    var pushes = 0L; private set
    var parseErrors = 0; private set
    var rejections = 0; private set
    var timeouts = 0; private set          // link declared dead: the agent stopped answering at all
    var stalls = 0; private set            // link fine, but the game thread stopped producing new ticks
    var rttMs: Double? = null; private set
    var lastConnectAt = 0L; private set

    @Synchronized fun onConnect() { connects++; lastConnectAt = clock(); lastPushAt = 0L }
    @Synchronized fun onDisconnect() { disconnects++ }
    @Synchronized fun onParseError() { parseErrors++ }
    @Synchronized fun onRejected() { rejections++ }
    @Synchronized fun onTimeout() { timeouts++ }
    @Synchronized fun onStall() { stalls++ }

    @Synchronized fun onPush(now: Long = clock()) {
        pushes++
        if (lastPushAt != 0L) {
            val gap = now - lastPushAt
            gaps.add(gap)
            ewmaGapMs = if (ewmaGapMs == 0.0) gap.toDouble() else ewmaGapMs * 0.9 + gap * 0.1
        }
        lastPushAt = now
    }

    @Synchronized fun onRtt(ms: Long) {
        rttMs = rttMs?.let { it * 0.7 + ms * 0.3 } ?: ms.toDouble()
    }

    /** Smoothed push rate in Hz, or null before two pushes arrived. */
    @Synchronized fun pushHz(): Double? = if (ewmaGapMs > 0.0) 1000.0 / ewmaGapMs else null

    /** 95th percentile of the gap between pushes (ms): the number that tells you about hitches. */
    @Synchronized fun gapP95Ms(): Long? = gaps.percentile(0.95)

    @Synchronized fun summary(): String {
        fun f(v: Double?) = if (v == null) "-" else String.format(Locale.ROOT, "%.1f", v)
        return "pushes=$pushes rate=${f(pushHz())}Hz gapP95=${gapP95Ms() ?: "-"}ms rtt=${f(rttMs)}ms " +
            "connects=$connects timeouts=$timeouts stalls=$stalls rejected=$rejections parseErrors=$parseErrors"
    }
}
