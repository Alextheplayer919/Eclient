package com.rubidiumclient.agent

import com.rubidiumclient.utils.CameraFrame
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes the optional `camera` object of an agent state push (docs/HYBRID_LITE.md).
 *
 * ```
 * "camera": {
 *   "fovY": 74.5,                                  // degrees, VERTICAL, as the renderer uses it right now
 *   "mode": 0,                                     // 0 first person, 1 third person back, 2 third person front
 *   "eye": {"x": 123.5, "y": 65.62, "z": -88.25},  // camera position in world space
 *   "yaw": 137.5, "pitch": -4.2,                   // same convention as the Bedrock packets
 *   "viewport": {"w": 2400, "h": 1080},
 *   "vp": [ ...16 floats, column-major, OpenGL clip space... ]
 * }
 * ```
 *
 * Total and defensive: a field that is missing, non-finite or out of range is dropped on its
 * own and the rest of the frame is still used. Returns null when nothing usable is left, which
 * makes the app fall back to the packet estimate (a sensor that has not derived the camera yet
 * simply omits the object).
 */
object CameraFrameParser {

    fun parse(obj: JSONObject, nowMs: Long): CameraFrame? {
        val vp = obj.optJSONArray("vp")?.toMatrixOrNull()
        val fov = obj.finite("fovY")?.takeIf { it > 1.0 && it < 170.0 }?.toFloat()
        val mode = obj.optInt("mode", -1).takeIf { it in 0..2 }

        // A camera pose only counts when it is complete: position AND rotation.
        val eye = obj.optJSONObject("eye")
        val ex = eye?.finite("x"); val ey = eye?.finite("y"); val ez = eye?.finite("z")
        val yaw = obj.finite("yaw"); val pitch = obj.finite("pitch")
        val hasPose = ex != null && ey != null && ez != null && yaw != null && pitch != null

        val vpObj = obj.optJSONObject("viewport")
        val vw = vpObj?.optInt("w", 0)?.takeIf { it > 0 }
        val vh = vpObj?.optInt("h", 0)?.takeIf { it > 0 }

        if (vp == null && fov == null && !hasPose && mode == null) return null

        return CameraFrame(
            atMs = nowMs,
            vp = vp,
            fovYDeg = fov,
            eyeX = if (hasPose) ex!!.toFloat() else null,
            eyeY = if (hasPose) ey!!.toFloat() else null,
            eyeZ = if (hasPose) ez!!.toFloat() else null,
            yawDeg = if (hasPose) yaw!!.toFloat() else null,
            pitchDeg = if (hasPose) pitch!!.toFloat() else null,
            mode = mode,
            viewportW = vw, viewportH = vh,
        )
    }

    private fun JSONObject.finite(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf { it.isFinite() }
    }

    private fun JSONArray.toMatrixOrNull(): FloatArray? {
        if (length() != 16) return null
        val out = FloatArray(16)
        for (i in 0 until 16) {
            val d = optDouble(i, Double.NaN)
            if (!d.isFinite()) return null
            out[i] = d.toFloat()
        }
        return out
    }
}
