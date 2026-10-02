package com.rubidiumclient.agent

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameParserTest {

    private fun parse(json: String, now: Long = 42L) = CameraFrameParser.parse(JSONObject(json), now)

    private val matrix16 = (0 until 16).joinToString(",") { (it + 1).toString() }

    @Test fun fullFrame() {
        val f = parse("""{"fovY":74.5,"mode":0,"eye":{"x":123.5,"y":65.62,"z":-88.25},"yaw":137.5,"pitch":-4.2,
            "viewport":{"w":2400,"h":1080},"vp":[$matrix16]}""")
        assertNotNull(f); f!!
        assertEquals(42L, f.atMs)
        assertEquals(74.5f, f.fovYDeg!!, 1e-5f)
        assertEquals(0, f.mode)
        assertTrue(f.hasPose)
        assertEquals(123.5f, f.eyeX!!, 1e-5f); assertEquals(65.62f, f.eyeY!!, 1e-5f); assertEquals(-88.25f, f.eyeZ!!, 1e-5f)
        assertEquals(137.5f, f.yawDeg!!, 1e-5f); assertEquals(-4.2f, f.pitchDeg!!, 1e-5f)
        assertEquals(2400, f.viewportW); assertEquals(1080, f.viewportH)
        assertArrayEquals(FloatArray(16) { (it + 1).toFloat() }, f.vp!!, 1e-5f)
    }

    @Test fun fovOnly_isTheCheapestUsefulFrame() {
        val f = parse("""{"fovY":82}""")!!
        assertEquals(82f, f.fovYDeg!!, 1e-5f)
        assertFalse(f.hasPose); assertNull(f.vp); assertNull(f.mode)
    }

    @Test fun emptyOrUnknownObject_isNull() {
        assertNull(parse("{}"))
        assertNull(parse("""{"something":"else"}"""))
    }

    @Test fun incompletePose_isDroppedAsAWhole() {
        val noRotation = parse("""{"fovY":80,"eye":{"x":1,"y":2,"z":3}}""")!!
        assertFalse(noRotation.hasPose); assertNull(noRotation.eyeX)
        val noZ = parse("""{"fovY":80,"eye":{"x":1,"y":2},"yaw":1,"pitch":2}""")!!
        assertFalse(noZ.hasPose)
    }

    @Test fun badValues_areDroppedIndividually_notFatal() {
        val f = parse("""{"fovY":500,"mode":9,"vp":[1,2,3],"viewport":{"w":0,"h":-5},"eye":{"x":1,"y":2,"z":3},"yaw":10,"pitch":5}""")!!
        assertNull("fov out of range", f.fovYDeg)
        assertNull("mode out of range", f.mode)
        assertNull("matrix of the wrong length", f.vp)
        assertNull(f.viewportW); assertNull(f.viewportH)
        assertTrue("the valid pose survives", f.hasPose)
    }

    @Test fun nonFiniteNumbers_areRejected() {
        // org.json cannot even represent NaN literals, so use a string-typed number the optDouble path rejects
        val f = parse("""{"fovY":"nan","vp":[$matrix16],"eye":{"x":"x","y":2,"z":3},"yaw":1,"pitch":2}""")!!
        assertNull(f.fovYDeg)
        assertFalse(f.hasPose)
        assertNotNull("the matrix is still fine", f.vp)
    }

    @Test fun matrixWithABadElement_isRejectedWhole() {
        val bad = (0 until 16).joinToString(",") { if (it == 7) "\"x\"" else "1" }
        assertNull(parse("""{"fovY":70,"vp":[$bad]}""")!!.vp)
    }

    @Test fun modeAlone_isKept_butNotASourceByItself() {
        val f = parse("""{"mode":2}""")!!
        assertEquals(2, f.mode)
    }
}
