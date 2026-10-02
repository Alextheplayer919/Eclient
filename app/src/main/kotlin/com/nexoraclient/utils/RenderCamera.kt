package com.rubidiumclient.utils

/**
 * One camera report from the in-game sensor (hybrid-lite, docs/HYBRID_LITE.md).
 *
 * Every field is optional on purpose: the sensor publishes only what it has actually
 * derived, and [RenderCamera] uses the best layer that is present. A newer game build
 * where one derivation breaks therefore degrades the ESP gracefully instead of killing it.
 *
 * Conventions (the sensor must normalise to these):
 *  - [vp]      16 floats, COLUMN-major, OpenGL clip space (`clip = VP * [x y z 1]`)
 *  - [fovYDeg] VERTICAL field of view in degrees, as the renderer really uses it right now
 *              (includes the player's FOV slider and sprint/speed effects)
 *  - eye/yaw/pitch  same conventions as the Bedrock packets (degrees)
 *  - [mode]    0 = first person, 1 = third person (back), 2 = third person (front)
 *
 * @param atMs arrival time in this app (System.currentTimeMillis), used for freshness
 */
class CameraFrame(
    val atMs: Long,
    val vp: FloatArray? = null,
    val fovYDeg: Float? = null,
    val eyeX: Float? = null, val eyeY: Float? = null, val eyeZ: Float? = null,
    val yawDeg: Float? = null, val pitchDeg: Float? = null,
    val mode: Int? = null,
    val viewportW: Int? = null, val viewportH: Int? = null,
) {
    /** True when the sensor reported a full camera pose (position + rotation). */
    val hasPose: Boolean
        get() = eyeX != null && eyeY != null && eyeZ != null && yawDeg != null && pitchDeg != null
}

/**
 * The single place that decides how world points become screen points.
 *
 * Layers, best first — each is used only when present and fresh:
 *
 *  1. SENSOR_MATRIX  the game's own view-projection matrix. No guesses at all.
 *  2. SENSOR_POSE    the game's FOV + camera position + rotation.
 *  3. SENSOR_FOV     the game's FOV; position/rotation still come from packets.
 *  4. PACKETS        no sensor: the original estimate (packet position/rotation, [fov] argument).
 *
 * With no agent attached this object is inert and layer 4 is byte-for-byte the old behaviour.
 *
 * ## The eye-height question
 * The old projection assumed `selfY` is the FEET height and added 1.62. But the tracker's own
 * docs say PlayerAuthInput positions are already EYE height (`selfYFrameIsEye`), which would
 * count the eye offset twice. Nothing could settle that without ground truth, so:
 *
 *  - [FrameFixMode.OFF]  — legacy behaviour (never correct).
 *  - [FrameFixMode.ON]   — trust the tracker's frame flag and use `selfY` as the eye.
 *  - [FrameFixMode.AUTO] — (default) correct only once the sensor has MEASURED that the
 *                          tracker's Y is the eye height ([eyeFrameVerified]). Without an agent
 *                          this never triggers, so proxy-only behaviour is unchanged.
 */
object RenderCamera {

    const val EYE_HEIGHT = 1.62f

    /** A sensor frame older than this is ignored (the agent pushes at ~20 Hz). */
    const val SENSOR_MAX_AGE_MS = 300L

    enum class FrameFixMode { AUTO, ON, OFF }

    enum class Source { SENSOR_MATRIX, SENSOR_POSE, SENSOR_FOV, PACKETS }

    /** Written by the agent reader thread, read by the overlay thread. */
    @Volatile var sensor: CameraFrame? = null

    @Volatile var frameFixMode: FrameFixMode = FrameFixMode.AUTO

    /** Set by SensorAudit once it has measured `trackerY - agentFeetY ≈ 1.62` while the tracker claimed eye frame. */
    @Volatile var eyeFrameVerified: Boolean = false

    /** Whether the eye-height correction applies right now, given the tracker's own frame flag. */
    fun eyeFrameFixActive(selfYIsEye: Boolean): Boolean = when (frameFixMode) {
        FrameFixMode.ON   -> selfYIsEye
        FrameFixMode.OFF  -> false
        FrameFixMode.AUTO -> selfYIsEye && eyeFrameVerified
    }

    fun freshSensor(now: Long = System.currentTimeMillis()): CameraFrame? =
        sensor?.takeIf { now - it.atMs <= SENSOR_MAX_AGE_MS }

    /** Which layer [project] would use right now. */
    fun activeSource(now: Long = System.currentTimeMillis()): Source {
        val cam = freshSensor(now) ?: return Source.PACKETS
        return when {
            cam.vp != null -> Source.SENSOR_MATRIX
            cam.fovYDeg != null && cam.hasPose -> Source.SENSOR_POSE
            cam.fovYDeg != null -> Source.SENSOR_FOV
            else -> Source.PACKETS
        }
    }

    private fun packetEyeY(selfY: Float, selfYIsEye: Boolean): Float =
        if (eyeFrameFixActive(selfYIsEye)) selfY else selfY + EYE_HEIGHT

    /**
     * @param selfX/selfY/selfZ the tracker's own position, exactly as the callers always passed it
     * @param yaw/pitch         the tracker's rotation
     * @param fov               the packet-path FOV guess (used only by layer 4)
     * @param selfYIsEye        `EntityTracker.selfYFrameIsEye`
     */
    fun project(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float,
        selfYIsEye: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Pair<Float, Float>? {
        val cam = freshSensor(now)
        if (cam != null) {
            val matrix = cam.vp
            if (matrix != null) return Projector.viewProj(matrix, wx, wy, wz, screenW, screenH)

            val sensorFov = cam.fovYDeg
            if (sensorFov != null) {
                if (cam.hasPose) {
                    return Projector.pinhole(
                        wx, wy, wz,
                        cam.eyeX!!, cam.eyeY!!, cam.eyeZ!!,
                        cam.yawDeg!!, cam.pitchDeg!!,
                        screenW, screenH, sensorFov,
                    )
                }
                // Third person without a camera pose: a pinhole at the player's eyes would be
                // wrong, and a misplaced box is worse than none.
                if (cam.mode != null && cam.mode != 0) return null
                return Projector.pinhole(
                    wx, wy, wz, selfX, packetEyeY(selfY, selfYIsEye), selfZ,
                    yaw, pitch, screenW, screenH, sensorFov,
                )
            }
        }
        return Projector.pinhole(
            wx, wy, wz, selfX, packetEyeY(selfY, selfYIsEye), selfZ,
            yaw, pitch, screenW, screenH, fov,
        )
    }

    /** Test/diagnostic helper: forget everything. */
    fun reset() {
        sensor = null
        frameFixMode = FrameFixMode.AUTO
        eyeFrameVerified = false
    }
}
