package com.rubidiumclient.core.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A Minecraft Bedrock world/server discovered on the local network via its
 *  periodic UNCONNECTED_PONG broadcast (the same mechanism vanilla Minecraft
 *  uses for "Play with Friends" / LAN visible worlds). */
data class LanServerInfo(
    val host            : String,
    val port            : Int,
    val motd            : String,
    val subMotd         : String,
    val protocolVersion : Int,
    val mcVersion       : String,
    val playerCount     : Int,
    val maxPlayers      : Int,
    val lastSeenMs       : Long
)

/**
 * Listens for LAN broadcast pongs (port 19132) from other devices AND from
 * the user's own Minecraft app if they've opened a world with "Visible to
 * LAN Players" turned on — both look identical on the wire, so no separate
 * "my own world" handling is needed. Lets the relay be pointed at a
 * discovered world's real IP/port directly, without the user having to type
 * or otherwise mark a target server by hand.
 */
object LanServerScanner {

    private const val TAG               = "LanServerScanner"
    private const val MC_PORT           = 19132
    private const val UNCONNECTED_PONG  = 0x1C.toByte()
    private const val SOCKET_TIMEOUT_MS = 2000
    private const val STALE_AFTER_MS    = 4000L
    private const val PRUNE_INTERVAL_MS = 1000L

    // RubidiumRelay's own LAN advertisement (see RubidiumRelay.PONG_SUB_MOTD) —
    // filtered out so the relay never lists itself as a connectable "world".
    private const val SELF_SUB_MOTD = "RubidiumClient"

    private val running = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    private var prunerThread  : Thread? = null

    private val discovered = ConcurrentHashMap<String, LanServerInfo>()

    private val _servers = MutableStateFlow<List<LanServerInfo>>(emptyList())
    val servers: StateFlow<List<LanServerInfo>> = _servers.asStateFlow()

    fun start() {
        if (running.getAndSet(true)) return
        discovered.clear()
        _servers.value = emptyList()
        startListener()
        startPruner()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        listenerThread?.interrupt(); listenerThread = null
        prunerThread?.interrupt();   prunerThread = null
        discovered.clear()
        _servers.value = emptyList()
    }

    val isRunning: Boolean get() = running.get()

    private fun startListener() {
        listenerThread = Thread({
            var socket: DatagramSocket? = null
            try {
                // Bind unbound-then-reuseAddress-then-bind so SO_REUSEADDR is set
                // *before* bind() — required for this socket to receive LAN
                // broadcasts alongside LanBroadcaster's own listener on the same
                // port 19132, since Android/Linux only honours SO_REUSEADDR for
                // co-binding when it's set pre-bind on every socket sharing the port.
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast    = true
                    soTimeout    = SOCKET_TIMEOUT_MS
                    bind(InetSocketAddress(MC_PORT))
                }

                val buf = ByteArray(1024)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        if (packet.length > 0 && buf[0] == UNCONNECTED_PONG) {
                            val host = packet.address?.hostAddress ?: continue
                            parsePong(buf, packet.length, host)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "RubidiumLan-Scanner").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPruner() {
        prunerThread = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(PRUNE_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    val removed = discovered.entries.removeIf { now - it.value.lastSeenMs > STALE_AFTER_MS }
                    if (removed) publish()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }, "RubidiumLan-ScannerPruner").apply {
            isDaemon = true
            start()
        }
    }

    private fun parsePong(buf: ByteArray, len: Int, host: String) {
        // Layout mirrors LanBroadcaster.buildPongPacket(): id(1) + pingTime(8) +
        // serverGuid(8) + RakNet magic(16) + strLen(2) + str(strLen)
        if (len < 35) return
        try {
            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
            bb.get()          // id
            bb.long           // pingTime
            bb.long           // serverGuid
            bb.position(bb.position() + 16) // RakNet magic
            val strLen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < strLen) return
            val strBytes = ByteArray(strLen)
            bb.get(strBytes)
            val parts = String(strBytes, Charsets.UTF_8).split(";")
            if (parts.size < 3 || parts[0] != "MCPE") return

            val motd            = parts.getOrNull(1) ?: ""
            val protocolVersion = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val mcVersion       = parts.getOrNull(3) ?: ""
            val playerCount     = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val maxPlayers      = parts.getOrNull(5)?.toIntOrNull() ?: 0
            val subMotd         = parts.getOrNull(7) ?: ""
            val port            = parts.getOrNull(10)?.toIntOrNull() ?: MC_PORT

            if (subMotd == SELF_SUB_MOTD) return // that's our own relay, not a world to connect to

            val key = "$host:$port"
            discovered[key] = LanServerInfo(
                host            = host,
                port            = port,
                motd            = motd,
                subMotd         = subMotd,
                protocolVersion = protocolVersion,
                mcVersion       = mcVersion,
                playerCount     = playerCount,
                maxPlayers      = maxPlayers,
                lastSeenMs      = System.currentTimeMillis()
            )
            publish()
        } catch (_: Exception) {
        }
    }

    private fun publish() {
        _servers.value = discovered.values.sortedBy { it.motd.lowercase() }
    }
}
