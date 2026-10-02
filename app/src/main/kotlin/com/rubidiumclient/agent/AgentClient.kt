package com.rubidiumclient.agent

import com.rubidiumclient.utils.CameraFrame
import com.rubidiumclient.utils.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/** How the link to the in-game agent is doing right now (docs/BRIDGE.md). */
enum class LinkState {
    /** No TCP connection: the agent is not running, or we are between retries. */
    OFFLINE,
    /** TCP connect in progress, or connected and waiting for the agent's first reply. */
    CONNECTING,
    /** The agent answers (ack/pong) but has no world data — usually the game is in a menu. Healthy. */
    TCP_UP,
    /** Receiving state pushes whose game tick keeps advancing. */
    LIVE,
    /** Pushes arrive but the game tick stopped advancing: the game thread is paused or frozen. */
    STALLED,
    /** The agent refused our token. */
    REJECTED,
}

/** Every timeout in one place, so tests can run the real code in milliseconds. */
data class Timing(
    val connectTimeoutMs: Int = 1_500,
    /** Ping cadence. Also the socket read timeout, so a silent agent still gets pinged. */
    val pingEveryMs: Long = 1_000,
    /** No line of ANY kind (push, ack, pong) for this long: the agent is hung, or busy with another client. */
    val deadAfterMs: Long = 3_000,
    val backoffMinMs: Long = 500,
    val backoffMaxMs: Long = 5_000,
    val rejectedBackoffMaxMs: Long = 30_000,
    /** The game tick must advance at least this often for the data to count as fresh. */
    val staleAfterMs: Long = 1_000,
)

/**
 * The only class in the app that knows the bridge exists.
 *
 * Wire (docs/MODULE_SDK.md + docs/BRIDGE.md): newline-delimited JSON over 127.0.0.1:38170, one
 * token on every line. Transport rules:
 *   - no raw memory ops, semantics only (the agent has no readMem verb at all),
 *   - an unavailable capability is refused with a reason, never a silent success,
 *   - state arrives at 10-20 Hz and is CACHED here, so module code reads with zero latency and
 *     never blocks on IO.
 *
 * What the link logic guarantees (each point is covered by AgentClientProtocolTest):
 *   - FRESH means the GAME advanced, not that bytes arrived. The agent's bridge thread keeps
 *     re-sending its last snapshot at 20 Hz even when the game thread is frozen, so arrival time
 *     alone would report stale data as live. Freshness follows the pushed tick counter `t`.
 *   - A silent agent is not a dead agent. In a menu the agent has nothing to push but still
 *     answers `ping`, so liveness is "any line", and we ping to provoke one. No reconnect churn.
 *   - The agent serves ONE client at a time and the kernel completes the TCP handshake for a
 *     connection that is merely queued. "TCP connected" therefore proves nothing: [LinkState] only
 *     leaves CONNECTING once the agent actually answers.
 *   - Reconnect backoff resets only after a VALID push, so a wrong token or a busy agent can never
 *     turn into a tight reconnect loop; a refused token backs off much more slowly.
 */
class AgentClient(
    private val token: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 38170,
    private val tag: String = "agent",
    private val log: (String, String) -> Unit = { t, m -> DiagLog.log(t, m) },
    private val timing: Timing = Timing(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var pose: Pose? = null; private set
    @Volatile var entityList: List<Entity> = emptyList(); private set
    @Volatile var inventoryList: List<Item> = emptyList(); private set
    @Volatile var caps: Set<Cap> = emptySet(); private set
    @Volatile var missing: Set<Cap> = emptySet(); private set
    /** Hybrid-lite camera report; null while the agent has not derived any camera data. */
    @Volatile var camera: CameraFrame? = null; private set
    /** Arrival time of the newest state push. NOT a freshness signal — use [fresh] / [linkState]. */
    @Volatile var lastPushAt: Long = 0L; private set

    @Volatile var linkState: LinkState = LinkState.OFFLINE; private set

    /** Wire protocol the agent announced (`proto` in its pushes); 0 = a legacy agent that announces none. */
    @Volatile var agentProto: Int = 0; private set
    @Volatile var agentBuild: String = ""; private set

    @Volatile var lastError: String = ""; private set

    /** Last refusal the agent sent, surfaced so a dead module is never a mystery. */
    @Volatile var lastRefusal: String = ""; private set

    /** Link measurements (push rate, gaps, RTT, reconnects…), shown by `.camera`. */
    val health = BridgeHealth(clock)

    /** TCP is up and the agent answers (kept for callers that predate [linkState]). */
    val connected: Boolean
        get() = linkState == LinkState.TCP_UP || linkState == LinkState.LIVE || linkState == LinkState.STALLED

    /** Source name for panels: "agent" while connected, otherwise why not. */
    val name: String get() = if (connected) "agent" else "agent(offline)"

    /** Called on every state push, on the reader thread. Keep it short. */
    var onState: ((AgentClient) -> Unit)? = null

    /** True while the link is live AND the game tick advanced within [maxAgeMs]. */
    fun fresh(maxAgeMs: Long = timing.staleAfterMs): Boolean =
        linkState == LinkState.LIVE && (clock() - lastTickChangeAt) <= maxAgeMs

    private val nextId = AtomicLong(1)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null

    @Volatile private var lastTick = -1L
    @Volatile private var lastTickChangeAt = 0L
    @Volatile private var pushedThisConnection = false
    @Volatile private var rejectedThisConnection = false
    @Volatile private var pendingPingId = 0L
    @Volatile private var pendingPingSentAt = 0L

    fun start() {
        if (running) return
        running = true
        readerThread = thread(name = "eclient-agent-reader", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        readerThread?.interrupt()
        readerThread = null
        socket = null
        writer = null
        linkState = LinkState.OFFLINE
    }

    private fun loop() {
        var backoff = timing.backoffMinMs
        while (running) {
            pushedThisConnection = false
            rejectedThisConnection = false
            try {
                connectAndRead()
            } catch (t: Throwable) {
                if (running) lastError = t.message ?: t.javaClass.simpleName
            }
            if (!running) break

            // Reset only after a VALID push. Resetting on "the connection ended" would turn a refused
            // token or a busy agent into a reconnect loop every few hundred milliseconds.
            backoff = if (pushedThisConnection) {
                timing.backoffMinMs
            } else {
                val cap = if (rejectedThisConnection) timing.rejectedBackoffMaxMs else timing.backoffMaxMs
                (backoff * 2).coerceIn(timing.backoffMinMs, cap)
            }
            try {
                Thread.sleep((backoff * (0.8 + 0.4 * Random.nextDouble())).toLong())
            } catch (_: InterruptedException) {
                if (!running) break
            }
        }
    }

    private fun connectAndRead() {
        linkState = LinkState.CONNECTING
        val s = Socket()
        var tcpUp = false
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timing.connectTimeoutMs)
            // The read timeout is the ping cadence: a quiet agent still gets pinged and must answer.
            s.soTimeout = timing.pingEveryMs.toInt().coerceAtLeast(1)
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            tcpUp = true
            health.onConnect()
            lastTick = -1L                 // a restarted agent starts its tick counter again
            lastTickChangeAt = 0L
            log(tag, "connected to the in-game agent at $host:$port (waiting for its first reply)")
            requestWithId("getState")

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            var lastRx = clock()
            var lastPingAt = lastRx
            while (running) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    val now = clock()
                    if (now - lastRx >= timing.deadAfterMs) {
                        lastError = "agent did not answer for ${timing.deadAfterMs} ms (busy with another client, or hung)"
                        health.onTimeout()
                        break
                    }
                    sendPing(now); lastPingAt = now
                    continue
                } ?: break                                     // the agent closed the connection

                lastRx = clock()
                if (line.isBlank()) continue
                try {
                    handle(JSONObject(line))
                } catch (e: Exception) {
                    health.onParseError()
                    lastError = "unreadable line from the agent: ${line.take(80)}"
                }
                val now = clock()
                if (now - lastPingAt >= timing.pingEveryMs) { sendPing(now); lastPingAt = now }
            }
        } finally {
            runCatching { s.close() }
            if (tcpUp) health.onDisconnect()
            socket = null
            writer = null
            linkState = if (rejectedThisConnection) LinkState.REJECTED else LinkState.OFFLINE
        }
    }

    private fun sendPing(now: Long) {
        val id = requestWithId("ping")
        if (id != 0L) { pendingPingId = id; pendingPingSentAt = now }
    }

    private fun handle(obj: JSONObject) {
        val now = clock()

        if (obj.optString("push") != "state") {
            // An ack. Any ack proves the agent is alive and has accepted us.
            val id = obj.optLong("id", -1L)
            if (id > 0 && id == pendingPingId) {
                health.onRtt(now - pendingPingSentAt)
                pendingPingId = 0L
            }
            if (linkState == LinkState.CONNECTING) linkState = LinkState.TCP_UP
            if (!obj.optBoolean("ok", true)) {
                // Only interesting when it is a refusal: keep the last one so the panel can say why a module is idle.
                lastRefusal = obj.optString("reason", "refused")
                log(tag, "refused: $lastRefusal")
                if (lastRefusal.contains("token", ignoreCase = true)) {
                    rejectedThisConnection = true
                    linkState = LinkState.REJECTED
                    health.onRejected()
                }
            }
            return
        }

        lastPushAt = now

        obj.optJSONObject("self")?.let { j ->
            pose = Pose(
                x = j.optDouble("x"), y = j.optDouble("y"), z = j.optDouble("z"),
                yaw = j.optDouble("yaw").toFloat(), pitch = j.optDouble("pitch").toFloat(),
                onGround = j.optBoolean("onGround", true), hp = j.optDouble("hp").toFloat(),
                vx = j.optDouble("vx"), vy = j.optDouble("vy"), vz = j.optDouble("vz"),
                hurt = j.optBoolean("hurt", false),
            )
        }
        entityList = obj.optJSONArray("entities")?.mapEntities() ?: emptyList()
        inventoryList = obj.optJSONArray("inventory")?.mapItems() ?: emptyList()
        camera = obj.optJSONObject("camera")?.let { CameraFrameParser.parse(it, now) }
        caps = obj.optJSONArray("caps")?.mapCaps() ?: emptySet()
        missing = obj.optJSONArray("missing")?.mapCaps() ?: emptySet()

        // Optional self-description from newer agents (see docs/BRIDGE.md). Legacy agents send neither.
        if (obj.has("proto")) agentProto = obj.optInt("proto", 0)
        if (obj.has("agent")) agentBuild = obj.optString("agent", "")

        // Freshness is about the GAME advancing, not about bytes arriving: the bridge thread keeps
        // re-sending the last snapshot at 20 Hz even when the game thread is frozen. `t` is the
        // game-tick counter, so "t stopped changing" means "the game stopped".
        val t = if (obj.has("t")) obj.optLong("t", -1L) else -1L
        if (t < 0 || t != lastTick) { lastTick = t; lastTickChangeAt = now }
        val stalled = t >= 0 && now - lastTickChangeAt > timing.staleAfterMs
        if (stalled && linkState != LinkState.STALLED) health.onStall()
        linkState = if (stalled) LinkState.STALLED else LinkState.LIVE

        pushedThisConnection = true
        health.onPush(now)
        onState?.invoke(this)
    }

    // -------------------------------------------------------------- probe ---

    companion object {
        /**
         * One-shot liveness + pairing check: connect, ask for state, confirm the
         * agent answered with a state push (i.e. it accepted our token), then hang
         * up. Never throws — a stock device simply has no agent listening, and that
         * is a normal outcome, not an error.
         *
         * Only used by the legacy MEMORY mode. Do NOT call it while a client is attached:
         * the agent serves one client at a time, so the probe would just wait in the backlog.
         */
        fun probe(
            token: String,
            host: String = "127.0.0.1",
            port: Int = 38170,
            timeoutMs: Long = 2500L,
        ): Boolean {
            return try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                s.soTimeout = timeoutMs.toInt()
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val id = 1L
                val req = JSONObject()
                    .put("token", token).put("id", id).put("op", "getState")
                w.write(req.toString()); w.newLine(); w.flush()

                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                var ok = false
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val line = r.readLine() ?: break
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (o.optString("push") == "state") { ok = true; break }
                    if (o.has("id") && !o.optBoolean("ok", true)) {
                        DiagLog.log("agent", "probe rejected: ${o.optString("reason")}")
                        break
                    }
                }
                runCatching { s.close() }
                ok
            } catch (t: Throwable) {
                false
            }
        }
    }

    // ------------------------------------------------------------ intents ---

    /** Sends one request; returns false when there is no connection. */
    fun request(op: String, body: JSONObject.() -> Unit = {}): Boolean = requestWithId(op, body) != 0L

    private fun requestWithId(op: String, body: JSONObject.() -> Unit = {}): Long {
        val w = writer ?: return 0L
        return try {
            val id = nextId.getAndIncrement()
            val o = JSONObject().put("token", token).put("id", id).put("op", op)
            o.body()
            synchronized(this) {
                w.write(o.toString())
                w.newLine()
                w.flush()
            }
            id
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            0L
        }
    }

    fun attack(entityId: Int) = request("attack") { put("target", entityId) }

    fun setRotation(yaw: Float, pitch: Float) = request("setRotation") {
        put("yaw", yaw.toDouble()); put("pitch", pitch.toDouble())
    }

    fun setInput(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean) = request("setInput") {
        put("forward", forward.toDouble()); put("strafe", strafe.toDouble())
        put("jump", jump); put("sneak", sneak)
    }

    fun toggleModule(name: String, enabled: Boolean) = request("toggleModule") {
        put("module", name); put("enabled", enabled)
    }

    fun setSetting(module: String, setting: String, value: Any) = request("setSetting") {
        put("module", module); put("setting", setting); put("value", value)
    }
}

// ------------------------------------------------------------- decoders ---

private fun JSONArray.mapEntities(): List<Entity> = (0 until length()).mapNotNull { i ->
    val j = optJSONObject(i) ?: return@mapNotNull null
    Entity(
        id = j.optInt("id"), typeId = j.optInt("type"),
        isPlayer = j.optBoolean("player", false),
        x = j.optDouble("x"), y = j.optDouble("y"), z = j.optDouble("z"),
        dist = j.optDouble("dist"), hp = j.optDouble("hp").toFloat(),
        hurt = j.optBoolean("hurt", false),
    )
}

private fun JSONArray.mapItems(): List<Item> = (0 until length()).mapNotNull { i ->
    val j = optJSONObject(i) ?: return@mapNotNull null
    Item(j.optInt("slot"), j.optString("id"), j.optInt("count"))
}

private fun JSONArray.mapCaps(): Set<Cap> = (0 until length()).mapNotNull { i ->
    runCatching { Cap.valueOf(optString(i)) }.getOrNull()
}.toSet()
