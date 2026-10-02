package com.rubidiumclient.utils

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * World → screen math. Pure: no Android, no tracker state, no clock — so it can be
 * unit-tested on a plain JVM (see app/src/test).
 *
 * Two models live here:
 *
 *  - [pinhole]  — the original Eclient model: an eye position, yaw/pitch and a VERTICAL
 *                 field of view. This is the exact arithmetic that used to be inlined in
 *                 `MathUtil.worldToScreen`; it is kept bit-for-bit so a build without any
 *                 in-game sensor behaves exactly as it did before hybrid-lite.
 *
 *  - [viewProj] — a full view-projection matrix straight from the game's renderer
 *                 (the hybrid-lite sensor, docs/HYBRID_LITE.md). No FOV or eye-height
 *                 guesses are involved at all.
 *
 * Which one is used, and with which inputs, is decided by [RenderCamera].
 */
object Projector {

    /**
     * Near cut-off in blocks. Unchanged from the original implementation.
     *
     * History: it used to be 0.1, which made blocks/targets vanish when the player stood right
     * next to them (mining, melee — the "Xray disappears when I get close" report). 0.02 still
     * rejects everything behind the camera and the divide-by-almost-zero angles.
     */
    private const val NEAR = 0.02

    /**
     * Original pinhole projection.
     *
     * @param eyeX/eyeY/eyeZ camera position in world space (the EYE, not the feet)
     * @param yaw/pitch      degrees, same convention as the Bedrock packets
     * @param fovYDeg        VERTICAL field of view in degrees
     * @return screen position in pixels, or null when the point is behind the camera
     */
    fun pinhole(
        wx: Float, wy: Float, wz: Float,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fovYDeg: Float,
    ): Pair<Float, Float>? {
        val dx = (wx - eyeX).toDouble()
        val dy = (wy - eyeY).toDouble()
        val dz = (wz - eyeZ).toDouble()

        val yawR   = Math.toRadians(-yaw.toDouble())
        val pitchR = Math.toRadians(-pitch.toDouble())
        val sinY = sin(yawR);  val cosY = cos(yawR)
        val sinP = sin(pitchR); val cosP = cos(pitchR)

        val rx0 = -dx * cosY + dz * sinY
        val rz0 =  dx * sinY + dz * cosY
        val rx  =  rx0
        val ry  =  dy * cosP - rz0 * sinP
        val rz  =  dy * sinP + rz0 * cosP

        // Behind the camera, or so close that the division degenerates.
        if (rz <= NEAR) return null

        val aspect      = screenW.toDouble() / screenH.toDouble()
        val tanHalfFovY = tan(Math.toRadians(fovYDeg / 2.0))
        val tanHalfFovX = tanHalfFovY * aspect

        val sx = (( rx / (rz * tanHalfFovX)) * (screenW / 2.0) + screenW / 2.0).toFloat()
        val sy = ((-ry / (rz * tanHalfFovY)) * (screenH / 2.0) + screenH / 2.0).toFloat()

        return Pair(sx, sy)
    }

    /**
     * Full view-projection matrix projection.
     *
     * Contract (docs/HYBRID_LITE.md): [vp] is 16 floats, COLUMN-major, OpenGL clip space
     * (x right, y up, `clip = VP * [x y z 1]`). Normalised device coordinates are mapped
     * onto the overlay's own pixel size, so the game's viewport size is irrelevant as long
     * as the overlay covers the same area as the game surface.
     *
     * @return screen position in pixels, or null when the point is behind the camera
     */
    fun viewProj(
        vp: FloatArray,
        wx: Float, wy: Float, wz: Float,
        screenW: Int, screenH: Int,
    ): Pair<Float, Float>? {
        if (vp.size < 16) return null
        val x = wx.toDouble(); val y = wy.toDouble(); val z = wz.toDouble()

        val clipW = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (clipW <= 1e-4) return null                       // behind the camera / on the eye plane

        val clipX = vp[0] * x + vp[4] * y + vp[8]  * z + vp[12]
        val clipY = vp[1] * x + vp[5] * y + vp[9]  * z + vp[13]

        val ndcX = clipX / clipW
        val ndcY = clipY / clipW

        val sx = ((ndcX * 0.5 + 0.5) * screenW).toFloat()
        val sy = ((1.0 - (ndcY * 0.5 + 0.5)) * screenH).toFloat()
        return Pair(sx, sy)
    }
}
