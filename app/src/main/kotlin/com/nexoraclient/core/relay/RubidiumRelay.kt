
package com.rubidiumclient.core.relay

import com.rubidiumclient.core.relay.codec.CodecRegistry
import com.rubidiumclient.utils.DiagLog
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class RubidiumRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG           = "RubidiumRelay"
        private const val PONG_MOTD     = "rubidium"
        private const val PONG_SUB_MOTD = "RubidiumClient"

        /**
         * The codec this relay presents itself with before it has seen a client:
         * the version this build targets (TargetVersion), NOT the newest codec the
         * vendored library happens to contain. Per-session negotiation in
         * AutoCodecListener still gives every client its own codec; this only
         * fixes what the advertisement and the pre-negotiation default claim.
         */
        val RELAY_CODEC: BedrockCodec by lazy { TargetVersion.codec }
    }

    @Volatile private var running      = false
    private var bossGroup    : NioEventLoopGroup? = null
    private var workerGroup  : NioEventLoopGroup? = null
    private var serverChannel: Channel?           = null

    val sessions = CopyOnWriteArrayList<RubidiumRelaySession>()

    @Volatile var remoteHost: String = ""
        internal set
    @Volatile var remotePort: Int = 19132
        internal set

    val boundLocalPort: Int get() = localPort

    fun capture(
        remoteHost       : String,
        remotePort       : Int = 19132,
        onSessionCreated : ((RubidiumRelaySession) -> Unit)? = null
    ) {
        // Önceki oturum düzgün stop() ile kapanmadan yeni bir capture() çağrısı
        // gelirse eskiden burada sessizce return ediliyordu; relay "running" kalmaya
        // devam ediyor ama ConnectionManager state'i de sıfırlanmadığı için UI
        // tarafında "Already connected" olarak görünüyordu. Artık önce eski
        // oturumu temiz şekilde kapatıp yeni bağlantıya devam ediyoruz.
        if (running) { stop() }

        this.remoteHost = remoteHost
        this.remotePort = remotePort

        // Which game version this build claims to be, and whether the codec for it
        // actually resolved. One line per capture in baba.txt, so "does the proxy
        // really support 1.21.111?" is answerable on the device.
        TargetVersion.verify()

        bossGroup   = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup(4)

        try {
            val pong = buildPong(RELAY_CODEC.protocolVersion, RELAY_CODEC.minecraftVersion ?: TargetVersion.MC_VERSION)

            val future = ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .group(bossGroup, workerGroup)
                .childHandler(object : BedrockChannelInitializer<RubidiumRelaySession.ServerSession>() {

                    override fun createSession0(peer: BedrockPeer, subClientId: Int): RubidiumRelaySession.ServerSession {
                        val session = RubidiumRelaySession(
                            peer        = peer,
                            subClientId = subClientId,
                            remoteHost  = this@RubidiumRelay.remoteHost,
                            remotePort  = this@RubidiumRelay.remotePort,
                            relay       = this@RubidiumRelay
                        )

                        sessions.add(session)

                        ConnectionManager.setupSession(session, this@RubidiumRelay)

                        session.init()

                        try {
                            onSessionCreated?.invoke(session)
                        } catch (e: Exception) {
                            DiagLog.log(TAG, "onSessionCreated EXCEPTION: ${e.stackTraceToString()}")
                        }

                        return session.clientSession
                    }

                    override fun initSession(session: RubidiumRelaySession.ServerSession) {
                    }

                    override fun preInitChannel(channel: Channel) {
                        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND)
                        super.preInitChannel(channel)
                    }
                })
                .bind(InetSocketAddress("0.0.0.0", localPort))
                .syncUninterruptibly()

            serverChannel = future.channel()
            running = true

            LanBroadcaster.start(
                relayPort       = localPort,
                motd            = PONG_MOTD,
                subMotd         = PONG_SUB_MOTD,
                protocolVersion = RELAY_CODEC.protocolVersion,
                mcVersion       = RELAY_CODEC.minecraftVersion ?: TargetVersion.MC_VERSION,
                maxPlayers      = 10
            )

        } catch (e: Exception) {
            shutdownGroups()
            throw e
        }
    }

    /**
     * Realm'a bağlanmak için önce Realms API'den (kısa ömürlü, oturuma özel)
     * gerçek sunucu adresini alır, sonra normal capture() akışını başlatır.
     * Adres her join isteğinde değişebildiği için burada cache'lenmiyor --
     * capture() öncesi her seferinde taze alınıyor.
     */
    suspend fun captureRealm(
        realmId          : Long,
        onSessionCreated : ((RubidiumRelaySession) -> Unit)? = null
    ) {
        val addr = com.rubidiumclient.core.relay.realms.RealmsApi.joinRealm(realmId)
        capture(addr.host, addr.port, onSessionCreated)
    }

    fun updateRemoteTarget(host: String, port: Int) {
        remoteHost = host
        remotePort = port
    }

    fun updatePong(protocolVersion: Int, minecraftVersion: String) {
        if (!running) return
        val pong = buildPong(protocolVersion, minecraftVersion)
        try {
            serverChannel?.config()?.setOption(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
        } catch (e: Exception) {
        }
        LanBroadcaster.updateInfo(
            protocolVersion = protocolVersion,
            mcVersion       = minecraftVersion,
            motd            = PONG_MOTD
        )
    }

    fun stop() {
        if (!running) return
        running = false
        LanBroadcaster.stop()
        sessions.toList().forEach { it.disconnect("Relay kapatıldı") }
        sessions.clear()
        shutdownGroups()
        ConnectionManager.onDisconnected("Relay durduruldu")
    }

    internal fun removeSession(session: RubidiumRelaySession) = sessions.remove(session)

    private fun shutdownGroups() {
        // Fire-and-forget: shutdownGracefully() zaten arka planda kendi thread'inde
        // kapanıyor. .sync() ile beklemek çağıran thread'i (bizim durumumuzda main
        // thread) varsayılan 2sn quiet period + 15sn timeout kadar bloklayabiliyordu;
        // boss + worker art arda sync edilince donma 10sn'ye kadar çıkıyordu.
        // quietPeriod=0 / maxTimeout=1sn verip sync çağırmayarak thread'i serbest
        // bırakıyoruz, grup arka planda kendi kapanır.
        val boss   = bossGroup
        val worker = workerGroup
        bossGroup     = null
        workerGroup   = null
        serverChannel = null

        try { boss?.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        try { worker?.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    val isRunning: Boolean get() = running

    private fun buildPong(protocol: Int, mc: String) = BedrockPong()
        .edition("MCPE")
        .motd(PONG_MOTD)
        .subMotd(PONG_SUB_MOTD)
        .playerCount(0)
        .maximumPlayerCount(10)
        .gameType("Survival")
        .nintendoLimited(false)
        .protocolVersion(protocol)
        .version(mc)
        .ipv4Port(localPort)
        .ipv6Port(localPort)
}
