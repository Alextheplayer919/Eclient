package com.rubidiumclient.agent

import com.rubidiumclient.agent.SensorAudit.FrameVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SensorAuditTest {

    private val rnd = Random(1)
    private var clockMs = 0L
    private val logged = ArrayList<String>()
    private fun audit(everyMs: Long = 5_000L) = SensorAudit({ logged += it }, everyMs, { clockMs })

    /**
     * Feed [n] samples where the tracker's Y is the agent's Y plus [offset] (± noise), with the
     * tracker claiming [claimsEye]. The player walks along a line so positions are not constant.
     */
    private fun feed(a: SensorAudit, n: Int, offset: Double, claimsEye: Boolean, noise: Double = 0.05, onGround: Boolean = true) {
        repeat(n) { i ->
            val x = 100.0 + i * 0.2; val z = -40.0 + i * 0.1; val y = 64.0
            a.onSample(
                agentX = x, agentY = y, agentZ = z, agentOnGround = onGround,
                trackerX = (x + (rnd.nextDouble() - 0.5) * 0.1).toFloat(),
                trackerY = (y + offset + (rnd.nextDouble() - 0.5) * 2 * noise).toFloat(),
                trackerZ = (z + (rnd.nextDouble() - 0.5) * 0.1).toFloat(),
                trackerClaimsEyeFrame = claimsEye,
            )
            clockMs += 50
        }
    }

    @Test fun eyeFrame_isConfirmed_whenTrackerYIsAboutOnePointSixTwoAboveTheAgent() {
        val a = audit()
        feed(a, 60, offset = 1.62, claimsEye = true)
        assertEquals(FrameVerdict.EYE, a.eyeClaimVerdict)
        assertTrue(a.eyeFrameConfirmed)
        assertFalse(a.flagContradicted)
    }

    @Test fun eyeFrame_notConfirmed_beforeEnoughSamples() {
        val a = audit()
        feed(a, SensorAudit.MIN_SAMPLES - 1, offset = 1.62, claimsEye = true)
        assertEquals(FrameVerdict.UNKNOWN, a.eyeClaimVerdict)
        assertFalse(a.eyeFrameConfirmed)
        feed(a, 1, offset = 1.62, claimsEye = true)
        assertTrue(a.eyeFrameConfirmed)
    }

    @Test fun aFlagThatLies_isReported() {
        // tracker says "eye frame" but its Y equals the agent's feet Y
        val a = audit()
        feed(a, 60, offset = 0.0, claimsEye = true)
        assertEquals(FrameVerdict.FEET, a.eyeClaimVerdict)
        assertFalse("must NOT enable the eye correction", a.eyeFrameConfirmed)
        assertTrue(a.flagContradicted)
    }

    @Test fun feetFrame_whenTheTrackerClaimsFeet_andMatches() {
        val a = audit()
        feed(a, 60, offset = 0.0, claimsEye = false)
        assertEquals(FrameVerdict.FEET, a.feetClaimVerdict)
        assertFalse(a.flagContradicted)
        assertFalse(a.eyeFrameConfirmed)
    }

    @Test fun feetClaim_thatIsReallyEye_isReported() {
        val a = audit()
        feed(a, 60, offset = 1.62, claimsEye = false)
        assertEquals(FrameVerdict.EYE, a.feetClaimVerdict)
        assertTrue(a.flagContradicted)
    }

    @Test fun anOffsetThatIsNeither_isOther_andNeverEnablesTheFix() {
        val a = audit()
        feed(a, 60, offset = 0.9, claimsEye = true)
        assertEquals(FrameVerdict.OTHER, a.eyeClaimVerdict)
        assertFalse(a.eyeFrameConfirmed)
    }

    @Test fun jumpingNoise_doesNotBreakTheMedian() {
        // sampling skew while airborne adds up to ±0.4 blocks of noise; the median still sees 1.62
        val a = audit()
        feed(a, 80, offset = 1.62, claimsEye = true, noise = 0.4, onGround = false)
        assertEquals(FrameVerdict.EYE, a.eyeClaimVerdict)
    }

    @Test fun samplesBeforeTheFirstPacket_areIgnored() {
        val a = audit()
        repeat(100) { a.onSample(100.0, 64.0, 100.0, true, 0f, 0f, 0f, true) }
        assertEquals(FrameVerdict.UNKNOWN, a.eyeClaimVerdict)
        assertTrue(a.summary().contains("samples=0"))
    }

    @Test fun nonFiniteAgentValues_areIgnored() {
        val a = audit()
        repeat(100) { a.onSample(Double.NaN, 64.0, 1.0, true, 5f, 65.6f, 5f, true) }
        assertEquals(FrameVerdict.UNKNOWN, a.eyeClaimVerdict)
    }

    @Test fun logging_isThrottled_andOnlyAfterEnoughSamples() {
        val a = audit(everyMs = 5_000)
        feed(a, 20, offset = 1.62, claimsEye = true)           // 1 s: too few samples, nothing logged
        assertEquals(0, logged.size)
        feed(a, 200, offset = 1.62, claimsEye = true)          // 10 s of samples -> a couple of lines, not 200
        assertTrue("logged ${logged.size}", logged.size in 1..3)
        assertTrue(logged.first().startsWith("audit samples="))
    }

    @Test fun summary_reportsTheMeasurements() {
        val a = audit()
        feed(a, 60, offset = 1.62, claimsEye = true)
        val s = a.summary()
        assertTrue(s, s.contains("eye-claim dy=1.6"))
        assertTrue(s, s.contains("→ EYE"))
        assertTrue(s, s.contains("agentGroundFrac=0.00"))
    }
}
