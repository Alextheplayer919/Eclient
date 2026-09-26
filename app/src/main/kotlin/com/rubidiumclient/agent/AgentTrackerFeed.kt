package com.rubidiumclient.agent

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.utils.DiagLog

/**
 * P1 of docs/MERGE_PLAN.md — the piece that makes the merge cheap.
 *
 * Measured: 69 of 81 modules read the singleton `EntityTracker`, not the network.
 * Today it is fed by `core/relay/listener/GamingPacketListener` from decoded
 * packets. This class feeds the SAME singleton from memory, so those modules work
 * in memory mode with zero edits:
 *
 *     EntityTracker.selfX / selfYaw / selfPitch
 *     EntityTracker.entities[...]        // TrackedEntity
 *
 * `EntityTracker.ingest(...)` is the single additive entry point that makes this
 * possible; the packet path is untouched and keeps working when the app is in
 * PROXY mode.
 *
 * The feed is deliberately attached only by `Backends.useAgent(...)`: with the
 * proxy relay also running, two writers would fight over the same map. One
 * engine at a time is a rule, not a preference.
 */
class AgentTrackerFeed(
    private val client: AgentClient,
    private val myRuntimeId: Long = SELF_RUNTIME_ID,
) {
    @Volatile var lastIngestMs: Long = 0L; private set
    @Volatile var lastEntityCount: Int = 0; private set
    @Volatile var selfSeen: Boolean = false; private set

    fun attach() {
        client.onState = { c -> ingest(c) }
    }

    fun detach() {
        if (client.onState != null) client.onState = null
    }

    private fun ingest(c: AgentClient) {
        val me = c.pose ?: return
        val actors = c.entityList

        EntityTracker.ingest(
            self = EntityTracker.RemoteSelf(
                x = me.x.toFloat(), y = me.y.toFloat(), z = me.z.toFloat(),
                yaw = me.yaw, pitch = me.pitch,
                health = me.hp,
                onGround = me.onGround,
                damaged = me.hurt,
            ),
            actors = actors.map { e ->
                EntityTracker.RemoteActor(
                    runtimeId = e.id.toLong(),
                    isPlayer = e.isPlayer,
                    x = e.x.toFloat(), y = e.y.toFloat(), z = e.z.toFloat(),
                    health = e.hp,
                    hurt = e.hurt,
                )
            },
        )

        selfSeen = true
        lastIngestMs = System.currentTimeMillis()
        lastEntityCount = actors.size
    }

    /** Panel line, same convention as the modules. */
    fun statusLine(): String {
        val age = if (lastIngestMs == 0L) -1L else System.currentTimeMillis() - lastIngestMs
        val caps = client.caps.joinToString(",").ifEmpty { "none" }
        val missing = client.missing.joinToString(",").ifEmpty { "-" }
        return "feed=${client.name} entities=$lastEntityCount age=${age}ms caps=[$caps] missing=[$missing]"
    }

    companion object {
        /**
         * The local player is not an entry in the agent's actor list (it never
         * attacks itself), so self state is applied through the self fields and
         * this id is reserved — the packet path assigns itself the server's own
         * runtime id, which the agent cannot know in memory mode.
         */
        const val SELF_RUNTIME_ID = 1L

        /** Log one line about the feed — used by the mode switch. */
        fun describe(c: AgentClient) =
            DiagLog.log("agent", "feed attached (caps=${c.caps.joinToString(",").ifEmpty { "none" }})")
    }
}
