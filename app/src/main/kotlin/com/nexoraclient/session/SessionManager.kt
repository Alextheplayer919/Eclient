package com.rubidiumclient.session

import com.rubidiumclient.auth.AccountManager
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.ConnectionManager
import com.rubidiumclient.core.relay.LanBroadcaster
import com.rubidiumclient.core.relay.RubidiumRelay
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.utils.BlockTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.rubidiumclient.core.relay.TargetVersion
import com.rubidiumclient.core.relay.MinecraftLink
import com.rubidiumclient.utils.DiagLog

object SessionManager {

    private const val TAG = "SessionManager"

    // Activity/lifecycleScope'a bağlı değil: DashboardActivity.onDestroy() içinde
    // super.onDestroy() çağrıldıktan sonra lifecycleScope iptal olduğu için relay
    // kapatma işini onun scope'unda yapmak riskliydi. SessionManager singleton
    // olduğundan kendi arka plan scope'unu kullanıyor.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isActive       = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _connectedHost  = MutableStateFlow("")
    val connectedHostFlow: StateFlow<String> = _connectedHost.asStateFlow()

    private val _connectedPort  = MutableStateFlow(0)
    val connectedPortFlow: StateFlow<Int> = _connectedPort.asStateFlow()

    private val _statusMessage  = MutableStateFlow("Kapalı")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _sessionCount   = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    val connectedHost: String get() = _connectedHost.value
    val connectedPort: Int    get() = _connectedPort.value

    private var relay: RubidiumRelay? = null

    /**
     * How long the relay waits for the game before saying so.
     *
     * The failure this exists for: the player starts the relay, joins their server
     * by typing its real address in the Servers tab, and every module looks broken —
     * because the game never connected to the relay and no packet reaches us. It is
     * not a module bug, it is a wiring mistake, and it should say that in words.
     */
    private val relayWatchJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)

    private fun watchForClient() {
        relayWatchJob.get()?.cancel()
        relayWatchJob.set(ioScope.launch {
            kotlinx.coroutines.delay(WAIT_FOR_GAME_MS)
            if (_sessionCount.value == 0 && _isActive.value) {
                _statusMessage.value = com.rubidiumclient.core.relay.MinecraftLink.waitingHint()
            }
        })
    }

    private const val WAIT_FOR_GAME_MS = 20_000L

    fun start() {
        if (_isActive.value) { return }

        val account = AccountManager.getRelayReadyAccount()
        if (account == null) {
            _statusMessage.value = "Hesap bulunamadı"
            return
        }

        val host      = ServerConfig.getHostBlocking()
        val port      = ServerConfig.getPortBlocking()
        val localPort = ServerConfig.LOCAL_PROXY_PORT

        // Pointing the relay AT ITSELF is a silent loop: the relay would dial
        // 127.0.0.1:19150, which is the relay. Say so instead of hanging.
        if (MinecraftLink.isRelayAddress(host, port)) {
            _statusMessage.value =
                "Relay target is the relay itself (${MinecraftLink.relayAddress()}). " +
                    "Set the REAL server address here; the game joins the relay from the Servers tab."
            DiagLog.log(TAG, "refusing self-targeting relay configuration")
            return
        }

        _statusMessage.value = "Bağlanıyor..."

        try {
            EntityTracker.init()
            BlockTracker.clear()

            val r = RubidiumRelay(localPort = localPort)

            r.capture(remoteHost = host, remotePort = port) { session ->
                onSessionCreated(session)
            }

            relay                = r
            _isActive.value      = true
            watchForClient()
            _connectedHost.value = host
            _connectedPort.value = port
            _statusMessage.value = "Aktif — $host:$port"
            _sessionCount.value  = 0
            relayWatchJob.get()?.cancel()   // the game is in the path; stop warning

        } catch (e: Exception) {
            _isActive.value      = false
            _statusMessage.value = "Hata: ${e.message}"
            relay                = null
        }
    }

    fun stop() {
        if (!_isActive.value) return

        // UI hemen "kapalı" görsün diye state'i anında güncelliyoruz.
        val relayToStop      = relay
        relay                = null
        _isActive.value      = false
        _connectedHost.value = ""
        _connectedPort.value = 0
        _statusMessage.value = "Kapalı"
        _sessionCount.value  = 0
        PacketEventBus.setSession(null)
        EntityTracker.reset()
        BlockTracker.clear()
        ConnectionManager.onDisconnected("Relay durduruldu")

        // Asıl ağır iş (Netty event loop group shutdown) arka planda yapılıyor;
        // caller thread'i (main thread olabilir) bloklamıyor.
        ioScope.launch {
            try {
                ModuleManager.disableAll()
                relayToStop?.stop()
            } catch (e: Exception) {
            }
        }
    }

    private fun onSessionCreated(session: RubidiumRelaySession) {
        _sessionCount.value++
        _statusMessage.value = "Session #${_sessionCount.value} — ${session.clientAddress}"

        LanBroadcaster.updateInfo(
            protocolVersion = RubidiumRelay.RELAY_CODEC.protocolVersion,
            mcVersion       = RubidiumRelay.RELAY_CODEC.minecraftVersion ?: TargetVersion.MC_VERSION,
            playerCount     = 0
        )

        installSessionCloseListener(session)
    }

    private fun installSessionCloseListener(session: RubidiumRelaySession) {
        try {
            session.clientSession.peer.channel
                .closeFuture()
                .addListener { onSessionEnded(session, "channel closed") }
        } catch (e: Exception) {
        }
    }

    private fun onSessionEnded(session: RubidiumRelaySession, reason: String) {
        PacketEventBus.setSession(null)
        EntityTracker.reset()
        BlockTracker.clear()

        _sessionCount.value = maxOf(0, _sessionCount.value - 1)

        if (_isActive.value) {
            _statusMessage.value = "Session kapandı — yeniden bağlanmayı bekliyor"
            ConnectionManager.onDisconnected(reason)
        }
    }

    fun onSessionStart(host: String, port: Int) = start()
    fun onSessionStop() = stop()
}
