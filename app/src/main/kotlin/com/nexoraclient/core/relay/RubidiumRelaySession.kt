package com.rubidiumclient.core.relay

import com.rubidiumclient.core.relay.listener.RubidiumPacketListener
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.utils.DiagLog
import io.netty.buffer.Unpooled
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class RubidiumRelaySession internal constructor(
    peer: BedrockPeer,
    subClientId: Int,
    val remoteHost: String,
    val remotePort: Int,
    internal val relay: RubidiumRelay
) {
    companion object {
        private const val TAG       = "RubidiumRelaySession"
        private const val MAX_QUEUE = 1024

        private fun minecraftUnconnectedMagic() = Unpooled.wrappedBuffer(
            byteArrayOf(
                0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
                0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
                0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
                0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
            )
        )

        private fun generateClientGuid(): Long {
            val timestamp = System.currentTimeMillis()
            val random    = kotlin.random.Random.nextLong(0, 0xFFFFFF)
            return (timestamp shl 24) or random
        }
    }

    val clientSession: ServerSession = ServerSession(peer, subClientId)

    @Volatile var serverSession: ClientSession? = null
        internal set

    @Volatile var activeCodec: BedrockCodec = RubidiumRelay.RELAY_CODEC
        internal set

    val listeners = CopyOnWriteArrayList<RubidiumPacketListener>()

    private val closed           = AtomicBoolean(false)
    private val serverConnecting = AtomicBoolean(false)
    private val serverConnected  = AtomicBoolean(false)

    private val pendingQueue = ConcurrentLinkedQueue<Pair<BedrockPacket, Boolean>>()

    private val serverEventLoop = NioEventLoopGroup(2)

    fun init() {
        clientSession.codec = activeCodec
        installClientDisconnectHandler()
    }

    fun connectToServer(onConnected: (() -> Unit)? = null) {
        if (!serverConnecting.compareAndSet(false, true)) {
            return
        }

        val addr = InetSocketAddress(remoteHost, remotePort)

        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(serverEventLoop)
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
            .option(RakChannelOption.RAK_GUID, generateClientGuid())
            .option(RakChannelOption.RAK_MTU, 1400)
            .option(RakChannelOption.RAK_UNCONNECTED_MAGIC, minecraftUnconnectedMagic())
            .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
            .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 10_000L)
            .option(RakChannelOption.RAK_SESSION_TIMEOUT, 20_000L)
            .handler(object : BedrockChannelInitializer<ClientSession>() {

                override fun createSession0(peer: BedrockPeer, subClientId: Int): ClientSession {
                    return this@RubidiumRelaySession.ClientSession(peer, subClientId)
                }

                override fun initSession(session: ClientSession) {
                    session.codec = activeCodec
                    session.peer.codecHelper.apply {
                        blockDefinitions        = clientSession.peer.codecHelper.blockDefinitions
                        itemDefinitions         = clientSession.peer.codecHelper.itemDefinitions
                        cameraPresetDefinitions = clientSession.peer.codecHelper.cameraPresetDefinitions
                        encodingSettings        = clientSession.peer.codecHelper.encodingSettings
                    }
                    installServerDisconnectHandler(session)

                    serverSession = session
                    serverConnected.set(true)

                    listeners.forEach { l ->
                        try { l.onSessionStart(this@RubidiumRelaySession) }
                        catch (e: Exception) {
                            DiagLog.log(TAG, "${l::class.simpleName}.onSessionStart EXCEPTION: ${e.stackTraceToString()}")
                        }
                    }

                    try { onConnected?.invoke() }
                    catch (e: Exception) { }

                    flushQueue(session)
                }

                override fun preInitChannel(channel: Channel) {
                    channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND)
                    super.preInitChannel(channel)
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    disconnect("Server bağlantısı kurulamadı")
                }
            }
    }

    private fun flushQueue(session: ClientSession) {
        while (true) {
            val (pkt, immediate) = pendingQueue.poll() ?: break
            sendToServer(pkt, immediate)
        }
    }

    fun handleClientPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onClientPacket(effective, this)) {
                event.replacementPacket?.let { sendToServer(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToServer(event.replacementPacket!!)
            return false
        }
        return true
    }

    fun handleServerPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.SERVER_TO_CLIENT, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onServerPacket(effective, this)) {
                event.replacementPacket?.let { sendToClient(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToClient(event.replacementPacket!!)
            return false
        }
        return true
    }

    // KÖK SEBEP ("body stays behind while soul moves" / rubber-band raporları):
    // clientSession ve serverSession'ın gerçek gönderim çağrıları (sendPacketImmediately)
    // eskiden HANGİ THREAD'DEN gelirse gelsin doğrudan çağrılıyordu. Ama pakederler
    // buraya en az üç farklı thread'den geliyor: (1) client'ın kendi RakNet event loop'u
    // (gerçek MovePlayerPacket/PlayerAuthInputPacket akışını buradan forward ediyoruz),
    // (2) modül tick coroutine'leri (CrystalAura vb. Dispatchers.Default), (3) overlay/UI
    // tarafı (Dispatchers.Main, örn. PacketUtil.sendMoveAtSelf ile crit hareketi enjekte
    // ederken). RakNet oturumunun güvenilir/sıralı paket sayaçları (reliable message
    // index, ordering index) thread-safe değil — iki thread aynı anda sendPacketImmediately
    // çağırırsa bu sayaçlar bozulabiliyor, server ara sıra "eksik" bir sıra numarası
    // bekleyip zaman aşımına kadar sonraki hareket paketlerini tutuyor. Sonuç: gerçek
    // input (soul) akmaya devam ederken server'ın onayladığı pozisyon (body) donup
    // sonra sıçrıyor. KillAura'nın crit/rotasyon paketleri bunu tetiklemeye daha yatkın
    // (daha sık enjekte ediyor) ama düz yürürken de aynı yarış nadiren oluşabiliyor.
    // Çözüm: her gönderimi ilgili kanalın KENDİ event loop'una (tek thread'li executor)
    // yönlendirip aynı anda yalnızca bir thread'in sendPacketImmediately'e dokunmasını
    // garanti ediyoruz — hem karşılıklı dışlama hem de göndericiler arası FIFO sıra elde
    // ediliyor (aynı kanala art arda gelen çağrılar kuyruğa girdikleri sırayla işlenir).
    private inline fun runOnChannelLoop(channel: Channel?, crossinline block: () -> Unit) {
        val loop = channel?.eventLoop()
        if (loop == null || loop.inEventLoop()) block() else loop.execute { block() }
    }

    fun sendToClient(packet: BedrockPacket) {
        if (closed.get()) return
        runOnChannelLoop(clientSession.peer.channel) {
            try { clientSession.sendPacketImmediately(packet) }
            catch (e: Exception) { }
        }
    }

    fun sendToServer(packet: BedrockPacket, immediate: Boolean = true) {
        if (closed.get()) return
        // ABSOLUTE friend-attack guard. This is deliberately NOT on the
        // PacketEventBus: module-injected packets (KillAura & friends from
        // other clients) reach sendToServer without ever crossing the bus, so
        // this is the only code point that covers every outbound path. No
        // module can bypass it without building its own socket. See
        // docs/FRIENDS.md.
        try {
            if (com.rubidiumclient.core.social.FriendGuard.isAttackOnFriend(packet, this)) return
        } catch (e: Exception) { DiagLog.log(TAG, "FriendGuard exception (fail-open): ${e.message}") }
        val srv = serverSession
        if (srv != null && serverConnected.get()) {
            runOnChannelLoop(srv.peer.channel) {
                try {
                    if (immediate) srv.sendPacketImmediately(packet)
                    else srv.sendPacket(packet)
                } catch (e: Exception) { }
            }
        } else {
            if (pendingQueue.size < MAX_QUEUE) pendingQueue.add(packet to immediate)
        }
    }

    fun clientBound(packet: BedrockPacket)  = sendToClient(packet)
    fun serverBound(packet: BedrockPacket)  = sendToServer(packet)

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        pendingQueue.clear()
        try { clientSession.disconnect() } catch (_: Exception) {}
        closeServerConnection()
        relay.removeSession(this)
        listeners.forEach { try { it.onSessionEnd(this) } catch (_: Exception) {} }
        listeners.clear()
        ConnectionManager.onDisconnected(reason)
    }

    /**
     * Sunucu tarafı RakNet bağlantısını kapatır. Event loop'u disconnect
     * paketi gönderilir gönderilmez değil, channel gerçekten kapandıktan
     * SONRA kapatır. Aksi halde disconnect bildirimi karşı sunucuya hiç
     * ulaşmadan UDP soketi öldürülüyor, sunucu eski oturumu
     * RAK_SESSION_TIMEOUT (20sn) dolana kadar canlı sanıyor ve hızlı
     * yeniden girişte "already connected" hatası veriyordu.
     */
    private fun closeServerConnection() {
        val srv = serverSession
        val channel = try { srv?.peer?.channel } catch (_: Exception) { null }

        try { srv?.disconnect() } catch (_: Exception) {}

        if (channel != null && channel.isOpen) {
            channel.closeFuture().addListener {
                try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
            }
        } else {
            try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
        }
    }

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel.pipeline()
                .addLast("ox-client-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (closed.compareAndSet(false, true)) {
                            pendingQueue.clear()
                            closeServerConnection()
                            relay.removeSession(this@RubidiumRelaySession)
                            listeners.forEach { try { it.onSessionEnd(this@RubidiumRelaySession) } catch (_: Exception) {} }
                            listeners.clear()
                            ConnectionManager.onDisconnected("Client bağlantısı koptu")
                        }
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { }
    }

    private fun installServerDisconnectHandler(session: ClientSession) {
        try {
            session.peer.channel.pipeline()
                .addLast("ox-server-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) disconnect("Server kesildi")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) { disconnect(evt.toString()) }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { }
    }

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleClientPacket(wrapper)) return

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToServer(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                }, immediate = true)
            } catch (e: Exception) {
                DiagLog.log(TAG, "ServerSession.onPacket EXCEPTION (packetId=${wrapper.packetId}): ${e.stackTraceToString()}")
            }
        }
    }

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleServerPacket(wrapper)) return

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToClient(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                })
            } catch (e: Exception) {
                DiagLog.log(TAG, "ClientSession.onPacket EXCEPTION (packetId=${wrapper.packetId}): ${e.stackTraceToString()}")
            }
        }
    }

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
    val isServerReady : Boolean get() = serverConnected.get() && !closed.get()
}