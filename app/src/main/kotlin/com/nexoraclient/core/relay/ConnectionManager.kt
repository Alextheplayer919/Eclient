package com.rubidiumclient.core.relay

import com.rubidiumclient.core.relay.listener.*
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.utils.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionManager {

    private const val TAG = "ConnectionManager"

    enum class State {
        IDLE,
        CONNECTING,
        HANDSHAKING,
        PLAYING,
        DISCONNECTED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _ping = MutableStateFlow(-1L)
    val ping: StateFlow<Long> = _ping.asStateFlow()

    fun setupSession(session: RubidiumRelaySession, relay: RubidiumRelay? = null) {
        PacketEventBus.setSession(session)

        val listeners = listOf<RubidiumPacketListener>(
            AutoCodecListener(relay),
            LoginPacketListener(),
            GamingPacketListener(),
        ).sortedBy { it.priority }

        listeners.forEach { listener ->
            session.listeners.add(listener)
        }

        try {
            ModuleManager.registerToSession(session)
        } catch (e: Exception) {
            DiagLog.log(TAG, "ModuleManager.registerToSession EXCEPTION: ${e.stackTraceToString()}")
        }

        _state.value = State.CONNECTING
    }

    fun onHandshaking() {
        _state.value = State.HANDSHAKING
    }

    fun onGameStarted() {
        _state.value = State.PLAYING
    }

    fun onDisconnected(reason: String = "") {
        PacketEventBus.setSession(null)
        _ping.value  = -1L
        _state.value = State.IDLE
    }

    fun updatePing(ms: Long) {
        _ping.value = ms
    }

    val isPlaying   : Boolean get() = _state.value == State.PLAYING
    val isConnected : Boolean get() = _state.value == State.PLAYING || _state.value == State.HANDSHAKING
    val isIdle      : Boolean get() = _state.value == State.IDLE
}
