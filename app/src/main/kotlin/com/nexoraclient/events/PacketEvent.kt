package com.rubidiumclient.events

import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

class PacketEvent(
    val packet   : BedrockPacket,
    val direction: Direction,
    val session  : RubidiumRelaySession
) {
    enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }

    var isCancelled: Boolean = false
        private set

    var replacementPacket: BedrockPacket? = null
        private set

    fun cancel() {
        isCancelled       = true
        replacementPacket = null
    }

    fun cancelAndReplace(pkt: BedrockPacket) {
        replacementPacket = pkt
        isCancelled       = false
    }

    val isClientToServer: Boolean get() = direction == Direction.CLIENT_TO_SERVER
    val isServerToClient: Boolean get() = direction == Direction.SERVER_TO_CLIENT
    val packetName      : String  get() = packet::class.simpleName ?: "UnknownPacket"
    val effectivePacket : BedrockPacket get() = replacementPacket ?: packet
}
