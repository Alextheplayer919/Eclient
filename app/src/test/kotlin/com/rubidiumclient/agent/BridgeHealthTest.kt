package com.rubidiumclient.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthTest {

    private var now = 1_000L
    private fun health() = BridgeHealth { now }

    @Test fun nothingMeasuredYet() {
        val h = health()
        assertNull(h.pushHz()); assertNull(h.gapP95Ms()); assertNull(h.rttMs)
        assertTrue(h.summary(), h.summary().contains("pushes=0"))
    }

    @Test fun aSteadyTwentyHertzFeed_isMeasuredAsTwentyHertz() {
        val h = health()
        repeat(60) { h.onPush(now); now += 50 }
        assertEquals(20.0, h.pushHz()!!, 0.5)
        assertEquals(50L, h.gapP95Ms())
    }

    @Test fun hitches_showUpInTheGapPercentile() {
        val h = health()
        // 1 push in 5 arrives 300 ms late
        repeat(100) { i -> h.onPush(now); now += if (i % 5 == 4) 300 else 50 }
        assertTrue("p95=${h.gapP95Ms()}", h.gapP95Ms()!! >= 300)
    }

    @Test fun aNewConnection_doesNotCountTheDowntimeAsOneGiantGap() {
        val h = health()
        repeat(10) { h.onPush(now); now += 50 }
        h.onDisconnect()
        now += 30_000                       // the game was away for 30 s
        h.onConnect()
        repeat(10) { h.onPush(now); now += 50 }
        assertEquals(50L, h.gapP95Ms())
    }

    @Test fun rtt_isSmoothed() {
        val h = health()
        h.onRtt(10)
        assertEquals(10.0, h.rttMs!!, 1e-9)
        h.onRtt(110)
        assertEquals(40.0, h.rttMs!!, 1e-9)          // 0.7*10 + 0.3*110
    }

    @Test fun counters() {
        val h = health()
        h.onConnect(); h.onConnect(); h.onDisconnect(); h.onTimeout(); h.onStall(); h.onRejected(); h.onParseError(); h.onParseError()
        assertEquals(2, h.connects); assertEquals(1, h.disconnects); assertEquals(1, h.timeouts)
        assertEquals(1, h.stalls); assertEquals(1, h.rejections); assertEquals(2, h.parseErrors)
        val s = h.summary()
        assertTrue(s, s.contains("connects=2") && s.contains("timeouts=1") && s.contains("parseErrors=2"))
        assertNotNull(s)
    }
}
