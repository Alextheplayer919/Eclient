package com.rubidiumclient.agent

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * An in-process stand-in for the native LoopbackBridge (Eclient-Attach, LoopbackBridge.cpp), modelling
 * exactly the behaviours that matter for link logic — each one verified against the native source:
 *
 *  - ONE client at a time: it accepts, then serves that client until it leaves (listen backlog 1).
 *    The kernel still completes the TCP handshake of a queued connection, so a client can be
 *    "connected" without ever being served.
 *  - a token on EVERY line; a bad token gets `bad or missing token` and a hang-up.
 *  - state is pushed only while a world snapshot exists (menu = silence), but `ping` is always answered.
 *  - the bridge thread keeps re-sending the LAST snapshot when the game tick is frozen.
 *
 * Behaviour knobs can be flipped from the test thread at any time.
 */
class FakeAgent(private val token: String = "secret", private val pushEveryMs: Long = 20) : AutoCloseable {

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 50 }
    val port: Int get() = server.localPort

    /** false: like the main menu — no state lines at all, pings still answered. */
    @Volatile var snapshotAvailable = true
    /** true: the game thread is frozen — the same tick is re-sent forever. */
    @Volatile var freezeTick = false
    /** true: accept the connection but never write a byte (hung bridge). */
    @Volatile var silent = false
    /** false: never call accept() (agent busy with another client): the client waits in the backlog. */
    @Volatile var acceptClients = true
    /** >= 0: hang up after this many pushes on a connection (a game/agent restart). */
    @Volatile var closeAfterPushes = -1
    @Volatile var includeCamera = false
    @Volatile var announceProto = false
    /** true: a legacy push without the `t` tick counter. */
    @Volatile var omitTick = false

    val accepted = AtomicInteger()
    val tokenRejections = AtomicInteger()
    private val tick = AtomicLong(0)
    @Volatile private var closed = false
    private val acceptThread = thread(name = "fake-agent", isDaemon = true) { acceptLoop() }

    private fun acceptLoop() {
        while (!closed) {
            if (!acceptClients) { Thread.sleep(10); continue }
            val c = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            accepted.incrementAndGet()
            try { serve(c) } catch (_: Exception) { } finally { runCatching { c.close() } }
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = pushEveryMs.toInt().coerceAtLeast(1)
        c.tcpNoDelay = true
        val input = c.getInputStream()
        val out = c.getOutputStream().bufferedWriter()
        val buf = StringBuilder()
        val chunk = ByteArray(2048)
        var authed = false
        var pushes = 0

        fun send(line: String) {
            if (silent) return
            out.write(line); out.write("\n"); out.flush()
        }

        while (!closed) {
            try {
                val n = input.read(chunk)
                if (n < 0) return                                  // the app hung up
                buf.append(String(chunk, 0, n, Charsets.UTF_8))
            } catch (_: SocketTimeoutException) {
                // nothing to read this interval
            }

            while (true) {
                val nl = buf.indexOf("\n")
                if (nl < 0) break
                val line = buf.substring(0, nl)
                buf.delete(0, nl + 1)
                if (line.isBlank()) continue

                if (field(line, "token") != token) {
                    tokenRejections.incrementAndGet()
                    send("""{"id":0,"ok":false,"reason":"bad or missing token"}""")
                    return                                          // the real bridge hangs up on a bad token
                }
                authed = true
                val id = number(line, "id") ?: 0L
                when (field(line, "op")) {
                    "getState" -> {
                        if (snapshotAvailable) send(stateLine())
                        send("""{"id":$id,"ok":true,"note":"state follows"}""")
                    }
                    "ping" -> send("""{"id":$id,"ok":true,"pong":true}""")
                    else -> send("""{"id":$id,"ok":false,"reason":"unknown op"}""")
                }
            }

            // like the real bridge: push the latest snapshot every interval, whether or not the game moved
            if (authed && snapshotAvailable) {
                send(stateLine())
                pushes++
                if (closeAfterPushes >= 0 && pushes >= closeAfterPushes) return
            }
        }
    }

    private fun stateLine(): String {
        val t = if (freezeTick) tick.get() else tick.incrementAndGet()
        val tickPart = if (omitTick) "" else "\"t\":$t,"
        val proto = if (announceProto) "\"proto\":1,\"agent\":\"fake-r1\"," else ""
        val cam = if (includeCamera) {
            ""","camera":{"fovY":74.5,"mode":0,"eye":{"x":1.0,"y":65.62,"z":2.0},"yaw":10.0,"pitch":5.0}"""
        } else ""
        return """{"push":"state",$proto$tickPart"live":true,"self":{"x":1.0,"y":64.0,"z":2.0,"yaw":10.0,"pitch":5.0,"hp":20.0,"onGround":true},"entities":[],"caps":["Pose","Entities"],"missing":["Rotation"]$cam}"""
    }

    private fun field(line: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(line)?.groupValues?.get(1)

    private fun number(line: String, key: String): Long? =
        Regex(""""$key"\s*:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toLong()

    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { acceptThread.join(500) }
    }
}
