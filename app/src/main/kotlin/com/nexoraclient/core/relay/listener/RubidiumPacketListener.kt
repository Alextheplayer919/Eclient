package com.rubidiumclient.core.relay.listener

import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

interface RubidiumPacketListener {

    fun onClientPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean = true

    fun onServerPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean = true

    fun onSessionStart(session: RubidiumRelaySession) {}

    fun onSessionEnd(session: RubidiumRelaySession) {}

    val priority: Int get() = 0
}
