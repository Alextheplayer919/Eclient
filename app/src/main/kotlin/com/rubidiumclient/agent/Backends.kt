package com.rubidiumclient.agent

/**
 * `Backends.active` is the one thing module code touches.
 *
 *   AgentBackend  - the patched Minecraft is running with the in-game agent
 *   ReplayBackend - offline: a recorded session, no Minecraft needed
 *   NullBackend   - nothing attached: inert, but never throws
 *
 * The proxy path (`PacketBackend`) keeps working untouched: it is the packets
 * that feed EntityTracker today, and this mode switch is additive — memory mode
 * has to be selected, never assumed.
 */
object Backends {
    @Volatile var active: Backend = NullBackend
        private set

    /** Which engine the app is on. PROXY is the shipped default until memory mode proves itself. */
    @Volatile var mode: Mode = Mode.PROXY

    enum class Mode { PROXY, MEMORY }

    private var client: AgentClient? = null
    private var feed: AgentTrackerFeed? = null

    /**
     * Switch to memory mode: connect to the in-game agent and feed EntityTracker
     * from it. Returns the client so callers can watch [AgentClient.caps].
     *
     * Nothing here deletes or disables the proxy path — that is deliberate: the
     * two must never run at once (double actions), so the caller picks a mode.
     */
    fun useAgent(token: String, host: String = "127.0.0.1", port: Int = 38170): AgentClient {
        stopAgent()
        val c = AgentClient(token, host, port)
        val f = AgentTrackerFeed(c).also { it.attach() }
        client = c
        feed = f
        c.start()
        active = AgentBackend(c)
        mode = Mode.MEMORY
        return c
    }

    /** Offline development: replay a recorded session deterministically. */
    fun useReplay(path: String, speed: Float = 1f, loop: Boolean = false): ReplayBackend {
        stopAgent()
        val r = ReplayBackend(path, speed, loop)
        r.start()
        active = r
        mode = Mode.MEMORY
        return r
    }

    /** Back to the packet path (the shipped default). */
    fun useProxy() {
        stopAgent()
        active = NullBackend
        mode = Mode.PROXY
    }

    fun stopAgent() {
        feed?.detach()
        client?.stop()
        feed = null
        client = null
    }

    /** Panel line: which engine, is it alive, what can it do. */
    fun statusLine(): String = when (val b = active) {
        is AgentBackend -> "memory(${b.name}) caps=${b.caps.joinToString(",").ifEmpty { "none" }}"
        else -> "${mode.name.lowercase()}(${b.name})"
    }
}

/** Nothing attached. Every read is empty, every intent is a no-op. */
object NullBackend : Backend {
    override val caps = emptySet<Cap>()
    override val name = "dormant"
    override val isLive = false
    override fun selfPose(): Pose? = null
    override fun entities() = emptyList<Entity>()
    override fun inventory() = emptyList<Item>()
    override fun attack(entityId: Int) {}
    override fun setRotation(yaw: Float, pitch: Float) {}
    override fun setInput(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean) {}
    override fun toggleModule(name: String, enabled: Boolean) {}
    override fun setSetting(module: String, setting: String, value: Any) {}
}

/** The agent client seen through the Backend interface. */
class AgentBackend(private val c: AgentClient) : Backend {
    override val caps: Set<Cap> get() = c.caps
    override val name: String get() = if (c.connected) "agent" else "agent(reconnecting)"
    override val isLive: Boolean get() = c.fresh()

    // The reader thread stores these; module ticks read them lock-free.
    override fun selfPose(): Pose? = if (c.fresh()) c.pose else null
    override fun entities(): List<Entity> = if (c.fresh()) c.entityList else emptyList()
    override fun inventory(): List<Item> = if (c.fresh()) c.inventoryList else emptyList()

    override fun attack(entityId: Int) { c.attack(entityId) }
    override fun setRotation(yaw: Float, pitch: Float) { c.setRotation(yaw, pitch) }
    override fun setInput(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean) =
        c.setInput(forward, strafe, jump, sneak)

    override fun toggleModule(name: String, enabled: Boolean) { c.toggleModule(name, enabled) }
    override fun setSetting(module: String, setting: String, value: Any) { c.setSetting(module, setting, value) }
}
