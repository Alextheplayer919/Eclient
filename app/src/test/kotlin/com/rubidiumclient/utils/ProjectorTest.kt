package com.rubidiumclient.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ProjectorTest {

    /** Verbatim copy of MathUtil.worldToScreen as it was BEFORE hybrid-lite. The reference. */
    private fun legacy(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float,
    ): Pair<Float, Float>? {
        val eyeY = selfY + 1.62f
        val dx = (wx - selfX).toDouble()
        val dy = (wy - eyeY).toDouble()
        val dz = (wz - selfZ).toDouble()

        val yawR   = Math.toRadians(-yaw.toDouble())
        val pitchR = Math.toRadians(-pitch.toDouble())
        val sinY = sin(yawR);  val cosY = cos(yawR)
        val sinP = sin(pitchR); val cosP = cos(pitchR)

        val rx0 = -dx * cosY + dz * sinY
        val rz0 =  dx * sinY + dz * cosY
        val rx  =  rx0
        val ry  =  dy * cosP - rz0 * sinP
        val rz  =  dy * sinP + rz0 * cosP

        if (rz <= 0.02) return null

        val aspect      = screenW.toDouble() / screenH.toDouble()
        val tanHalfFovY = tan(Math.toRadians(fov / 2.0))
        val tanHalfFovX = tanHalfFovY * aspect

        val sx = (( rx / (rz * tanHalfFovX)) * (screenW / 2.0) + screenW / 2.0).toFloat()
        val sy = ((-ry / (rz * tanHalfFovY)) * (screenH / 2.0) + screenH / 2.0).toFloat()
        return Pair(sx, sy)
    }

    /**
     * A column-major OpenGL view-projection matrix built from the SAME camera model the pinhole
     * uses (same rotation conventions), so the two projections must agree.
     */
    private fun glMatrix(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        yaw: Float, pitch: Float, fovYDeg: Float, aspect: Double,
    ): FloatArray {
        val yawR = Math.toRadians(-yaw.toDouble()); val pitchR = Math.toRadians(-pitch.toDouble())
        val sinY = sin(yawR); val cosY = cos(yawR); val sinP = sin(pitchR); val cosP = cos(pitchR)
        // rows of R: (dx,dy,dz) -> (rx, ry, rz) exactly as in the pinhole
        val r0 = doubleArrayOf(-cosY, 0.0, sinY)
        val r1 = doubleArrayOf(-sinP * sinY, cosP, -sinP * cosY)
        val r2 = doubleArrayOf(cosP * sinY, sinP, cosP * cosY)
        val eye = doubleArrayOf(eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble())
        fun t(r: DoubleArray) = -(r[0] * eye[0] + r[1] * eye[1] + r[2] * eye[2])
        // GL view space looks down -Z, so z is negated
        val view = arrayOf(
            doubleArrayOf(r0[0], r0[1], r0[2], t(r0)),
            doubleArrayOf(r1[0], r1[1], r1[2], t(r1)),
            doubleArrayOf(-r2[0], -r2[1], -r2[2], -t(r2)),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )
        val f = 1.0 / tan(Math.toRadians(fovYDeg / 2.0))
        val near = 0.05; val far = 1000.0
        val proj = arrayOf(
            doubleArrayOf(f / aspect, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, f, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, (far + near) / (near - far), 2 * far * near / (near - far)),
            doubleArrayOf(0.0, 0.0, -1.0, 0.0),
        )
        val out = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) {
            var sum = 0.0
            for (k in 0 until 4) sum += proj[row][k] * view[k][col]
            out[col * 4 + row] = sum.toFloat()          // column-major
        }
        return out
    }

    @Test
    fun pinhole_isBitIdenticalToTheOriginalImplementation() {
        val rnd = Random(42)
        var compared = 0; var nulls = 0
        repeat(5000) {
            val selfX = rnd.nextFloat() * 400f - 200f
            val selfY = rnd.nextFloat() * 100f
            val selfZ = rnd.nextFloat() * 400f - 200f
            val wx = selfX + rnd.nextFloat() * 60f - 30f
            val wy = selfY + rnd.nextFloat() * 30f - 10f
            val wz = selfZ + rnd.nextFloat() * 60f - 30f
            val yaw = rnd.nextFloat() * 720f - 360f
            val pitch = rnd.nextFloat() * 180f - 90f
            val w = 800 + rnd.nextInt(2000); val h = 400 + rnd.nextInt(1200)
            val fov = 30f + rnd.nextFloat() * 100f

            val expected = legacy(wx, wy, wz, selfX, selfY, selfZ, yaw, pitch, w, h, fov)
            val actual = Projector.pinhole(wx, wy, wz, selfX, selfY + 1.62f, selfZ, yaw, pitch, w, h, fov)
            assertEquals(expected, actual)          // exact, including null == null
            compared++; if (expected == null) nulls++
        }
        // the test is only meaningful if it exercised both outcomes
        assertEquals(5000, compared)
        assert(nulls in 100..4900) { "degenerate sample: $nulls nulls" }
    }

    @Test
    fun pinhole_pointStraightAheadLandsOnScreenCentre() {
        // yaw 0 / pitch 0 looks along +Z
        val p = Projector.pinhole(0f, 64f, 10f, 0f, 64f, 0f, 0f, 0f, 1600, 900, 90f)
        assertNotNull(p)
        assertEquals(800f, p!!.first, 0.01f)
        assertEquals(450f, p.second, 0.01f)
    }

    @Test
    fun pinhole_behindTheCameraIsNull() {
        assertNull(Projector.pinhole(0f, 64f, -5f, 0f, 64f, 0f, 0f, 0f, 1600, 900, 90f))
    }

    @Test
    fun pinhole_horizontalHalfFovLandsOnTheScreenEdge() {
        // fovY 90 at 16:9 -> tan(halfX) = 16/9; rx = 10 * 16/9 at rz = 10 is exactly the right edge.
        // At yaw 0, rx = -dx, so the right edge is at dx = -17.7777
        val p = Projector.pinhole(-17.77778f, 64f, 10f, 0f, 64f, 0f, 0f, 0f, 1600, 900, 90f)
        assertNotNull(p)
        assertEquals(1600f, p!!.first, 0.5f)
        assertEquals(450f, p.second, 0.01f)
    }

    @Test
    fun viewProj_agreesWithPinholeForTheSameCamera() {
        val rnd = Random(7)
        var checked = 0
        repeat(3000) {
            val eyeX = rnd.nextFloat() * 200f - 100f
            val eyeY = 60f + rnd.nextFloat() * 10f
            val eyeZ = rnd.nextFloat() * 200f - 100f
            val yaw = rnd.nextFloat() * 360f - 180f
            val pitch = rnd.nextFloat() * 120f - 60f
            val fov = 40f + rnd.nextFloat() * 70f
            val w = 1280 + rnd.nextInt(1200); val h = 720 + rnd.nextInt(500)
            val vp = glMatrix(eyeX, eyeY, eyeZ, yaw, pitch, fov, w.toDouble() / h)

            val wx = eyeX + rnd.nextFloat() * 40f - 20f
            val wy = eyeY + rnd.nextFloat() * 20f - 10f
            val wz = eyeZ + rnd.nextFloat() * 40f - 20f

            val a = Projector.pinhole(wx, wy, wz, eyeX, eyeY, eyeZ, yaw, pitch, w, h, fov)
            val b = Projector.viewProj(vp, wx, wy, wz, w, h)
            // Points between the camera and the 0.02 near cut-off are handled differently on purpose; skip them.
            if (a == null || b == null) return@repeat
            // Only points that land on or near the screen matter. Far off-screen points come from a depth
            // close to zero, where float32 matrix entries legitimately differ from the double pinhole by
            // ~1e-5 relative (e.g. 0.3 px at y = -33500) — comparing those would test float noise.
            if (abs(a.first) > 3 * w || abs(a.second) > 3 * h) return@repeat
            assertEquals("x for sample", a.first.toDouble(), b.first.toDouble(), 0.25)
            assertEquals("y for sample", a.second.toDouble(), b.second.toDouble(), 0.25)
            checked++
        }
        assert(checked > 500) { "too few comparable samples: $checked" }
    }

    @Test
    fun viewProj_behindTheCameraIsNull() {
        val vp = glMatrix(0f, 64f, 0f, 0f, 0f, 90f, 16.0 / 9)
        assertNull(Projector.viewProj(vp, 0f, 64f, -5f, 1600, 900))
        assertNotNull(Projector.viewProj(vp, 0f, 64f, 5f, 1600, 900))
    }

    @Test
    fun viewProj_rejectsAShortMatrix() {
        assertNull(Projector.viewProj(FloatArray(12), 0f, 0f, 1f, 100, 100))
    }

    @Test
    fun viewProj_centreRayIsScreenCentre() {
        val vp = glMatrix(10f, 70f, 10f, 33f, -12f, 80f, 2.0)
        // a point along the exact view direction
        val yawR = Math.toRadians(-33.0); val pitchR = Math.toRadians(12.0)
        // invert the pinhole rotation for the +Z camera axis: world dir = R^T * (0,0,1)
        val sinY = sin(yawR); val cosY = cos(yawR); val sinP = sin(pitchR); val cosP = cos(pitchR)
        val dirX = cosP * sinY; val dirY = sinP; val dirZ = cosP * cosY
        val p = Projector.viewProj(vp, (10 + dirX * 20).toFloat(), (70 + dirY * 20).toFloat(), (10 + dirZ * 20).toFloat(), 2000, 1000)
        assertNotNull(p)
        assertEquals(1000.0, p!!.first.toDouble(), 1.0)
        assertEquals(500.0, p.second.toDouble(), 1.0)
    }
}
