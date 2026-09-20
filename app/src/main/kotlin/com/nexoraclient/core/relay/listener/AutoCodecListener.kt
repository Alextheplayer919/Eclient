package com.rubidiumclient.core.relay.listener

import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelay
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.core.relay.codec.CodecRegistry
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*
import com.rubidiumclient.utils.DiagLog

class AutoCodecListener(private val relay: RubidiumRelay? = null) : RubidiumPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        private fun patchCodec(codec: BedrockCodec): BedrockCodec {
            val v = codec.protocolVersion
            val (contentSerializer, slotSerializer) = when {
                v in 730..747 -> InventoryContentSerializer_v729.INSTANCE to InventorySlotSerializer_v729.INSTANCE
                else           -> return codec
            }
            return codec.toBuilder()
                .updateSerializer(InventoryContentPacket::class.java, contentSerializer)
                .updateSerializer(InventorySlotPacket::class.java, slotSerializer)
                .build()
        }

        // Sınır tamamen kaldırıldı — hiçbir paket boyutu/liste uzunluğu/NBT
        // derinliği yüzünden decode aşamasında reddedilmesin diye tüm alanlar
        // Int.MAX_VALUE'ya çekildi.
        private val SAFE_LIMITS = EncodingSettings.builder()
            .maxListSize(Int.MAX_VALUE)
            .maxByteArraySize(Int.MAX_VALUE)
            .maxNetworkNBTSize(Int.MAX_VALUE)
            .maxItemNBTSize(Int.MAX_VALUE)
            .maxStringLength(Int.MAX_VALUE)
            .build()
    }

    override val priority: Int = -10

    @Volatile private var done = false

    override fun onSessionStart(session: RubidiumRelaySession) {
        done = false
        com.rubidiumclient.utils.PlacementUtil.reset()
    }

    override fun onClientPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
        if (packet !is RequestNetworkSettingsPacket) return true
        if (done) return false
        done = true

        val protocol = packet.protocolVersion

        try {
            val raw   = CodecRegistry.getClosestCodec(protocol)
            val codec = patchCodec(raw)

            session.clientSession.codec = codec
            session.activeCodec = codec

            val defs = Definitions.getClosestDefinitions(codec.protocolVersion)
            session.clientSession.peer.codecHelper.apply {
                itemDefinitions         = defs.itemDefinitions
                blockDefinitions        = defs.blockDefinitions
                cameraPresetDefinitions = Definitions.cameraPresetDefinitions
                encodingSettings        = SAFE_LIMITS
            }

            session.sendToClient(NetworkSettingsPacket().apply {
                compressionThreshold = 1
                compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
            })

            session.clientSession.setCompression(PacketCompressionAlgorithm.ZLIB)

            relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: "")

        } catch (e: Exception) {
            DiagLog.log(TAG, "NetworkSettings EXCEPTION: ${e.stackTraceToString()}")
            session.disconnect("NetworkSettings hatası: ${e.message}")
        }

        return false
    }
}
