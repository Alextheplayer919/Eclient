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
            _connectedHost.value = host
            _connectedPort.value = port
            _statusMessage.value = "Aktif — $host:$port"
            _sessionCount.value  = 0

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
            mcVersion       = RubidiumRelay.RELAY_CODEC.minecraftVersion ?: "1.21.60",
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
