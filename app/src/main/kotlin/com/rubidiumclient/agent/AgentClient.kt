package com.rubidiumclient.agent

import com.rubidiumclient.utils.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The only class in the app that knows the bridge exists.
 *
 * Wire (docs/MODULE_SDK.md): newline-delimited JSON over 127.0.0.1:38170, one
 * token on every line. Transport rules:
 *   - no raw memory ops, semantics only (the agent has no readMem verb at all),
 *   - an unavailable capability is refused with a reason, never a silent success,
 *   - state arrives at 10-20 Hz and is CACHED here, so module code reads with
 *     zero latency and never blocks on IO. Scans run on the game thread inside
 *     the game; module ticks read the newest snapshot this thread stored.
 */
class AgentClient(
    private val token: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 38170,
    private val tag: String = "agent",
) {
    @Volatile var pose: Pose? = null; private set
    @Volatile var entityList: List<Entity> = emptyList(); private set
    @Volatile var inventoryList: List<Item> = emptyList(); private set
    @Volatile var caps: Set<Cap> = emptySet(); private set
    @Volatile var missing: Set<Cap> = emptySet(); private set
    @Volatile var lastPushAt: Long = 0L; private set
    @Volatile var connected: Boolean = false; private set
    /** Source name for panels: "agent" while connected, otherwise why not. */
    val name: String get() = if (connected) "agent" else "agent(offline)"

    @Volatile var lastError: String = ""; private set

    /** Last refusal the agent sent, surfaced so a dead module is never a mystery. */
    @Volatile var lastRefusal: String = ""; private set

    private val nextId = AtomicLong(1)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var running = false

    /** Called on every state push, on the reader thread. Keep it short. */
    var onState: ((AgentClient) -> Unit)? = null

    /** True when a push arrived recently enough to be trusted. */
    fun fresh(maxAgeMs: Long = 1000): Boolean =
        connected && (System.currentTimeMillis() - lastPushAt) < maxAgeMs

    fun start() {
        if (running) return
        running = true
        thread(name = "eclient-agent-reader", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        writer = null
        connected = false
    }

    private fun loop() {
        var backoff = 500L
        while (running) {
            try {
                connectAndRead()
                backoff = 500L
            } catch (t: Throwable) {
                connected = false
                lastError = t.message ?: t.javaClass.simpleName
            }
            if (!running) break
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(5_000L)   // quiet retry, never throws
        }
    }

    private fun connectAndRead() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 1500)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        connected = true
        DiagLog.log(tag, "connected to the in-game agent at $host:$port")
        request("getState")

        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        while (running) {
            val line = reader.readLine() ?: break      // agent closed
            if (line.isBlank()) continue
            runCatching { handle(JSONObject(line)) }
        }
        connected = false
    }

    private fun handle(obj: JSONObject) {
        if (obj.optString("push") != "state") {
            // Ack: only interesting when it is a refusal. Keep the last one so the
            // panel can say why a module is doing nothing.
            if (!obj.optBoolean("ok", true)) {
                lastRefusal = obj.optString("reason", "refused")
                DiagLog.log(tag, "refused: $lastRefusal")
            }
            return
        }

        lastPushAt = System.currentTimeMillis()

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
        caps = obj.optJSONArray("caps")?.mapCaps() ?: emptySet()
        missing = obj.optJSONArray("missing")?.mapCaps() ?: emptySet()
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
         * Deliberately NOT used to keep a connection: the real client starts only
         * after this says yes, so the app never runs two engines at once.
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

    fun request(op: String, body: JSONObject.() -> Unit = {}): Boolean {
        val w = writer ?: return false
        return try {
            val o = JSONObject().put("token", token).put("id", nextId.getAndIncrement()).put("op", op)
            o.body()
            synchronized(this) {
                w.write(o.toString())
                w.newLine()
                w.flush()
            }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
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
