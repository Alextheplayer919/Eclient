package com.rubidiumclient.core.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

object LanBroadcaster {

    private const val TAG = "LanBroadcaster"

    private const val MC_PORT            = 19132
    private const val UNCONNECTED_PING   = 0x01.toByte()
    private const val UNCONNECTED_PONG   = 0x1C.toByte()
    private const val BROADCAST_INTERVAL = 1500L
    private const val SOCKET_TIMEOUT_MS  = 2000

    private val RAKNET_MAGIC = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12, 0x34, 0x56, 0x78
    )

    private val running       = AtomicBoolean(false)
    private val serverGuid    = System.currentTimeMillis()

    @Volatile private var relayPort      : Int    = 19150
    @Volatile private var motd           : String = "NoxeraClient"
    @Volatile private var subMotd        : String = "NoxeraClient"
    @Volatile private var protocolVersion: Int    = 748
    @Volatile private var mcVersion      : String = TargetVersion.MC_VERSION
    @Volatile private var playerCount    : Int    = 0
    @Volatile private var maxPlayers     : Int    = 10

    private var listenerThread : Thread? = null
    private var broadcasterThread: Thread? = null

    fun start(
        relayPort      : Int,
        motd           : String = "NoxeraClient",
        subMotd        : String = "NoxeraClient",
        protocolVersion: Int,
        mcVersion      : String,
        maxPlayers     : Int = 10
    ) {
        if (running.getAndSet(true)) {
            return
        }

        this.relayPort       = relayPort
        this.motd            = motd
        this.subMotd         = subMotd
        this.protocolVersion = protocolVersion
        this.mcVersion       = mcVersion
        this.maxPlayers      = maxPlayers

        startPingListener()
        startActiveBroadcaster()
    }

    fun updateInfo(
        protocolVersion: Int,
        mcVersion      : String,
        motd           : String = this.motd,
        playerCount    : Int    = this.playerCount
    ) {
        this.protocolVersion = protocolVersion
        this.mcVersion       = mcVersion
        this.motd            = motd
        this.playerCount     = playerCount
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        listenerThread?.interrupt()
        broadcasterThread?.interrupt()
        listenerThread    = null
        broadcasterThread = null
    }

    val isRunning: Boolean get() = running.get()

    private fun startPingListener() {
        listenerThread = Thread({
            var socket: DatagramSocket? = null
            try {
                // FIX: reuseAddress artık bind'dan ÖNCE set ediliyor. Önceden
                // `DatagramSocket(MC_PORT)` constructor'ı zaten bind ediyordu ve
                // reuseAddress sonradan set edilince Android/Linux'ta etkisiz
                // kalıyordu — bu da aynı 19132 portunu dinlemesi gereken
                // LanServerScanner ile çakışıp "Address already in use" riski
                // doğuruyordu. Şimdi unbound socket + pre-bind reuseAddress ile
                // ikisi aynı anda aynı porta bind olabiliyor.
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast    = true
                    soTimeout    = SOCKET_TIMEOUT_MS
                    bind(java.net.InetSocketAddress(MC_PORT))
                }

                val buf = ByteArray(512)
                while (running.get()) {
                    try {
                        val incoming = DatagramPacket(buf, buf.size)
                        socket.receive(incoming)

                        if (incoming.length >= 25 && buf[0] == UNCONNECTED_PING) {
                            val pingTime = ByteBuffer.wrap(buf, 1, 8)
                                .order(ByteOrder.BIG_ENDIAN).getLong()

                            val pong = buildPongPacket(pingTime)
                            val response = DatagramPacket(
                                pong, pong.size,
                                incoming.address,
                                incoming.port
                            )
                            socket.send(response)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "RubidiumLan-PingListener").apply {
            isDaemon = true
            start()
        }
    }

    private fun startActiveBroadcaster() {
        broadcasterThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                while (running.get()) {
                    try {
                        val pong = buildPongPacket(System.currentTimeMillis())
                        socket.send(DatagramPacket(pong, pong.size, broadcastAddr, MC_PORT))
                    } catch (e: Exception) {
                    }
                    Thread.sleep(BROADCAST_INTERVAL)
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "RubidiumLan-Broadcaster").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildPongPacket(pingTime: Long): ByteArray {
        val pongStr = buildPongString()
        val strBytes = pongStr.toByteArray(Charsets.UTF_8)

        val buf = ByteBuffer.allocate(1 + 8 + 8 + 16 + 2 + strBytes.size)
            .order(ByteOrder.BIG_ENDIAN)

        buf.put(UNCONNECTED_PONG)
        buf.putLong(pingTime)
        buf.putLong(serverGuid)
        buf.put(RAKNET_MAGIC)
        buf.putShort(strBytes.size.toShort())
        buf.put(strBytes)

        return buf.array()
    }

    private fun buildPongString(): String {
        val p  = protocolVersion
        val mv = mcVersion
        val m  = motd.take(64)
        val sm = subMotd.take(20)
        val pc = playerCount
        val mp = maxPlayers
        val rp = relayPort

        return "MCPE;$m;$p;$mv;$pc;$mp;$serverGuid;$sm;Survival;1;$rp;$rp;"
    }
}
