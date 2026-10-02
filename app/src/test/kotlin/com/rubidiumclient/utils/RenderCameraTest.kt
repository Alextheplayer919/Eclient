package com.rubidiumclient.utils

import com.rubidiumclient.utils.RenderCamera.FrameFixMode
import com.rubidiumclient.utils.RenderCamera.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RenderCameraTest {

    @Before fun setUp() = RenderCamera.reset()
    @After fun tearDown() = RenderCamera.reset()

    // a fixed, non-degenerate scene
    private val selfX = 10f; private val selfY = 65.62f; private val selfZ = 20f
    private val yaw = 15f; private val pitch = 8f
    private val w = 2400; private val h = 1080
    private val target = Triple(12f, 66f, 32f)
    private val now = 1_000_000L

    private fun proj(selfYIsEye: Boolean, fov: Float = 90f, at: Long = now) =
        RenderCamera.project(target.first, target.second, target.third, selfX, selfY, selfZ, yaw, pitch, w, h, fov, selfYIsEye, at)

    private fun pin(eyeY: Float, fov: Float = 90f) =
        Projector.pinhole(target.first, target.second, target.third, selfX, eyeY, selfZ, yaw, pitch, w, h, fov)

    // ------------------------------------------------------------ no sensor: nothing changes ---

    @Test fun noSensor_isTheLegacyEstimate() {
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = true))
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = false))
        assertEquals(Source.PACKETS, RenderCamera.activeSource(now))
    }

    @Test fun autoMode_withoutMeasurement_neverCorrects() {
        RenderCamera.frameFixMode = FrameFixMode.AUTO
        RenderCamera.eyeFrameVerified = false
        assertFalse(RenderCamera.eyeFrameFixActive(true))
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = true))
    }

    // ------------------------------------------------------------------ the eye-height fix ---

    @Test fun autoMode_correctsOnlyWhenMeasuredAndTheTrackerClaimsEyeFrame() {
        RenderCamera.frameFixMode = FrameFixMode.AUTO
        RenderCamera.eyeFrameVerified = true
        assertTrue(RenderCamera.eyeFrameFixActive(true))
        assertFalse("tracker says feet frame right now -> legacy +1.62 is right", RenderCamera.eyeFrameFixActive(false))
        assertEquals(pin(selfY), proj(selfYIsEye = true))              // selfY IS the eye
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = false))
    }

    @Test fun onMode_trustsTheTrackersFlag() {
        RenderCamera.frameFixMode = FrameFixMode.ON
        assertEquals(pin(selfY), proj(selfYIsEye = true))
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = false))
    }

    @Test fun offMode_isAlwaysLegacy_evenIfMeasured() {
        RenderCamera.frameFixMode = FrameFixMode.OFF
        RenderCamera.eyeFrameVerified = true
        assertEquals(pin(selfY + 1.62f), proj(selfYIsEye = true))
    }

    @Test fun theFix_actuallyMovesTheProjection() {
        RenderCamera.frameFixMode = FrameFixMode.OFF
        val before = proj(true)
        RenderCamera.frameFixMode = FrameFixMode.ON
        val after = proj(true)
        assertNotEquals(before, after)
    }

    // ------------------------------------------------------------------------- sensor layers ---

    @Test fun sensorFov_replacesTheGuessedFov_butKeepsPacketPose() {
        RenderCamera.sensor = CameraFrame(atMs = now, fovYDeg = 70f)
        assertEquals(Source.SENSOR_FOV, RenderCamera.activeSource(now))
        assertEquals(pin(selfY + 1.62f, fov = 70f), proj(selfYIsEye = false, fov = 110f))
        assertNotEquals(pin(selfY + 1.62f, fov = 110f), proj(selfYIsEye = false, fov = 110f))
    }

    @Test fun sensorPose_replacesPacketPoseAndFov() {
        RenderCamera.sensor = CameraFrame(
            atMs = now, fovYDeg = 75f,
            eyeX = 1f, eyeY = 70f, eyeZ = 2f, yawDeg = 40f, pitchDeg = -5f, mode = 0,
        )
        assertEquals(Source.SENSOR_POSE, RenderCamera.activeSource(now))
        val expected = Projector.pinhole(target.first, target.second, target.third, 1f, 70f, 2f, 40f, -5f, w, h, 75f)
        assertEquals(expected, proj(selfYIsEye = true))
    }

    @Test fun sensorMatrix_winsOverEverything() {
        val identityLike = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[11] = 1f; it[15] = 0f } // w = z
        RenderCamera.sensor = CameraFrame(atMs = now, vp = identityLike, fovYDeg = 75f)
        assertEquals(Source.SENSOR_MATRIX, RenderCamera.activeSource(now))
        val expected = Projector.viewProj(identityLike, target.first, target.second, target.third, w, h)
        assertEquals(expected, proj(selfYIsEye = true))
    }

    @Test fun staleSensor_isIgnored() {
        RenderCamera.sensor = CameraFrame(atMs = now, fovYDeg = 70f)
        val stillFresh = now + RenderCamera.SENSOR_MAX_AGE_MS
        val tooOld = now + RenderCamera.SENSOR_MAX_AGE_MS + 1
        assertEquals(Source.SENSOR_FOV, RenderCamera.activeSource(stillFresh))
        assertEquals(Source.PACKETS, RenderCamera.activeSource(tooOld))
        assertEquals(pin(selfY + 1.62f, fov = 90f), proj(selfYIsEye = false, fov = 90f, at = tooOld))
    }

    @Test fun thirdPerson_withoutACameraPose_hidesTheOverlayInsteadOfMisplacingIt() {
        RenderCamera.sensor = CameraFrame(atMs = now, fovYDeg = 70f, mode = 1)
        assertNull(proj(selfYIsEye = true))
        // …but a full pose or matrix still works in third person
        RenderCamera.sensor = CameraFrame(atMs = now, fovYDeg = 70f, mode = 1,
            eyeX = 5f, eyeY = 70f, eyeZ = 0f, yawDeg = 0f, pitchDeg = 10f)
        assertNotNull(proj(selfYIsEye = true))
    }

    @Test fun matrixPath_returnsNullForPointsBehindTheCamera_notALegacyFallback() {
        // clip.w = -z  -> everything with z > 0 is "behind"
        val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[11] = -1f }
        RenderCamera.sensor = CameraFrame(atMs = now, vp = m)
        assertNull(proj(selfYIsEye = true))
    }

    @Test fun frameWithOnlyAMode_isNotUsedAsASource() {
        RenderCamera.sensor = CameraFrame(atMs = now, mode = 0)
        assertEquals(Source.PACKETS, RenderCamera.activeSource(now))
    }
}
