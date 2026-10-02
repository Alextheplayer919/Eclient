package com.rubidiumclient.agent

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.utils.RenderCamera

/**
 * Hybrid-lite's counterpart of [AgentTrackerFeed]: the agent as a READ-ONLY SENSOR.
 *
 * It deliberately does NOT write into [EntityTracker]. In hybrid-lite the packets own the world
 * model, the actions and the movement; the agent only reports what packets cannot say:
 *
 *   1. the camera ([RenderCamera.sensor]) — FOV, pose and/or the full view-projection matrix, and
 *   2. ground truth for self position, used by [SensorAudit] as a referee for the packet path.
 *
 * Because nothing here writes tracker state, the "two writers on one map" hazard that forced the
 * old PROXY-xor-MEMORY rule does not exist, and the relay and the agent can run together.
 */
class SensorFeed(
    private val client: AgentClient,
    val audit: SensorAudit,
) {
    fun attach() {
        client.onState = { c -> onState(c) }
    }

    fun detach() {
        if (client.onState != null) client.onState = null
        RenderCamera.sensor = null
        RenderCamera.eyeFrameVerified = false
    }

    /** Runs on the agent reader thread. Keep it short. */
    private fun onState(c: AgentClient) {
        RenderCamera.sensor = c.camera      // null when the agent has not derived the camera: packets take over

        val me = c.pose ?: return
        audit.onSample(
            agentX = me.x, agentY = me.y, agentZ = me.z, agentOnGround = me.onGround,
            trackerX = EntityTracker.selfX, trackerY = EntityTracker.selfY, trackerZ = EntityTracker.selfZ,
            trackerClaimsEyeFrame = EntityTracker.selfYFrameIsEye,
        )
        RenderCamera.eyeFrameVerified = audit.eyeFrameConfirmed
    }

    /** Panel line, same convention as the tracker feed. */
    fun statusLine(): String {
        val caps = client.caps.joinToString(",").ifEmpty { "none" }
        val missing = client.missing.joinToString(",").ifEmpty { "-" }
        return "sensor=${client.name} camera=${RenderCamera.activeSource()} caps=[$caps] missing=[$missing]"
    }
}
