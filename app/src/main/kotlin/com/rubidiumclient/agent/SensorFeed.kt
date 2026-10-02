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

    /** True while the link is live and the game tick is advancing. */
    val isLive: Boolean get() = client.linkState == LinkState.LIVE

    /** Runs on the agent reader thread. Keep it short. */
    private fun onState(c: AgentClient) {
        // A paused or frozen game keeps re-sending its LAST snapshot (the bridge thread does not stop).
        // That must never pass as a live camera, nor as ground truth for the audit.
        val live = c.linkState == LinkState.LIVE
        RenderCamera.sensor = if (live) c.camera else null   // null: packets take over
        if (!live) return

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
        return "sensor=${client.name} link=${client.linkState} camera=${RenderCamera.activeSource()} caps=[$caps] missing=[$missing]"
    }

    /** The link measurements, for `.camera` and the log. */
    fun bridgeLine(): String {
        val proto = if (client.agentProto > 0 || client.agentBuild.isNotEmpty()) " proto=${client.agentProto} agent=${client.agentBuild.ifEmpty { "?" }}" else " proto=legacy"
        val err = if (client.lastError.isNotEmpty() && client.linkState != LinkState.LIVE) " lastError=\"${client.lastError}\"" else ""
        return "link=${client.linkState} ${client.health.summary()}$proto$err"
    }
}
