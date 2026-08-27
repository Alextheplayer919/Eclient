package com.rubidiumclient.core.relay.listener

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.ConnectionManager
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.ChunkParser
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

class GamingPacketListener : RubidiumPacketListener {

    companion object {
        private const val TAG = "GamingPacketListener"
    }

    override val priority: Int = 100

    @Volatile private var active = false

    private val itemDefMap = LinkedHashMap<Int, ItemDefinition>()
    private val itemDefSource = HashMap<Int, Int>()

    private fun mergeItemDefs(defs: Collection<ItemDefinition>, session: RubidiumRelaySession, priority: Int) {
        var changed = false
        for (def in defs) {
            val existingPriority = itemDefSource[def.runtimeId]
            if (existingPriority == null || priority >= existingPriority) {
                itemDefMap[def.runtimeId] = def
                itemDefSource[def.runtimeId] = priority
                changed = true
            }
        }
        if (!changed) return
        val registry = SimpleDefinitionRegistry.builder<ItemDefinition>()
            .addAll(itemDefMap.values)
            .build()
        session.clientSession.peer.codecHelper.itemDefinitions = registry
        session.serverSession?.peer?.codecHelper?.itemDefinitions = registry
    }

    private val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onSessionStart(session: RubidiumRelaySession) {
        active = true
        itemDefMap.clear()
        itemDefSource.clear()
        WorldBlockTracker.reset()
        WorldBlockTracker.init()
    }

    override fun onSessionEnd(session: RubidiumRelaySession) {
        active = false
        EntityTracker.reset()
        WorldBlockTracker.reset()
        parserScope.coroutineContext[Job]?.cancelChildren()
    }

    override fun onClientPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
        if (!active) return true
        when (packet) {
            is ClientCacheStatusPacket -> {
                packet.isSupported = false
                return true
            }
            is MovePlayerPacket          -> { }
            is PlayerAuthInputPacket     -> { }
            is PlayerActionPacket        -> { }
            is InteractPacket            -> { }
            is InventoryTransactionPacket-> { }
            is CommandRequestPacket      -> { }
            is TextPacket                -> { }
            is AnimatePacket             -> { }
            is DisconnectPacket          -> { }
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
        if (!active) return true
        when (packet) {

            is StartGamePacket -> {
                applyStartGameDefinitions(packet, session)
                BlockTracker.loadPalette(packet)
                ConnectionManager.onGameStarted()
            }

            is UpdateBlockPacket -> handleUpdateBlock(packet)
            is BlockEntityDataPacket -> handleBlockEntity(packet)
            is LevelChunkPacket -> handleChunk(packet)

            is CameraPresetsPacket -> {
                applyCameraDefinitions(packet, session)
            }

            is ItemComponentPacket -> {
                applyItemComponents(packet, session)
            }

            is CreativeContentPacket -> {
                applyCreativeItemDefinitions(packet, session)
            }

            is AddPlayerPacket          -> { }
            is AddEntityPacket          -> { }
            is RemoveEntityPacket       -> { }
            is MoveEntityAbsolutePacket -> { }
            is MovePlayerPacket         -> { }

            is RespawnPacket          -> { }
            is SetHealthPacket        -> { }
            is UpdateAttributesPacket -> { }
            is PlayerListPacket       -> { }
            is ChangeDimensionPacket  -> { }
            is TextPacket             -> { }
            is DisconnectPacket       -> { }

            is TransferPacket -> {
                val newHost = packet.address
                val newPort = packet.port

                try {
                    session.relay.updateRemoteTarget(newHost, newPort)

                    val clientReconnectHost = (session.clientSession.peer.channel.localAddress()
                        as? java.net.InetSocketAddress)?.address?.hostAddress
                        ?: "127.0.0.1"

                    session.sendToClient(TransferPacket().apply {
                        address = clientReconnectHost
                        port    = session.relay.boundLocalPort
                    })
                } catch (e: Exception) {
                }

                return false
            }
        }
        return true
    }

    private fun handleUpdateBlock(pkt: UpdateBlockPacket) {
        val pos = pkt.blockPosition
        val runtimeId = pkt.definition?.runtimeId ?: return
        val type = BlockTracker.resolveBlockId(runtimeId)
        if (type != null) BlockTracker.add(pos.x, pos.y, pos.z, type)
        else BlockTracker.remove(pos.x, pos.y, pos.z)
    }

    private fun handleBlockEntity(pkt: BlockEntityDataPacket) {
        val pos = pkt.blockPosition
        val tag = pkt.data ?: return
        val id  = tag.getString("id") ?: return
        val type = BlockTracker.resolveBlockName(id) ?: return
        BlockTracker.add(pos.x, pos.y, pos.z, type)
    }

    private fun handleChunk(pkt: LevelChunkPacket) {
        val buf = try { pkt.data } catch (_: Throwable) { null }
        if (buf == null) return
        buf.retain()

        WorldBlockTracker.handleLevelChunkPacket(pkt)

        parserScope.launch {
            try {
                val entities = try {
                    ChunkParser.extractBlockEntities(pkt)
                } catch (e: Exception) {
                    emptyList()
                }
                for (be in entities) {
                    val id = be.tag.getString("id") ?: continue
                    val type = BlockTracker.resolveBlockName(id) ?: continue
                    BlockTracker.add(be.x, be.y, be.z, type)
                }
            } finally {
                buf.release()
            }
        }
    }

    private fun applyStartGameDefinitions(packet: StartGamePacket, session: RubidiumRelaySession) {
        try {
            if (packet.itemDefinitions.isNotEmpty()) {
                mergeItemDefs(packet.itemDefinitions, session, priority = 0)
            }
        } catch (e: Exception) {
        }

        try {
            val protocolVersion = session.activeCodec.protocolVersion
            val defs = Definitions.getClosestDefinitions(protocolVersion)

            val correctRegistry = if (packet.isBlockNetworkIdsHashed) {
                defs.blockDefinitionsHashed
            } else {
                defs.blockDefinitions
            }

            session.clientSession.peer.codecHelper.blockDefinitions = correctRegistry
            session.serverSession?.peer?.codecHelper?.blockDefinitions = correctRegistry
        } catch (e: Exception) {
        }
    }

    private fun applyItemComponents(packet: ItemComponentPacket, session: RubidiumRelaySession) {
        try {
            val items = packet.items
            if (items.isEmpty()) {
                return
            }

            mergeItemDefs(items, session, priority = 2)
        } catch (e: Exception) {
        }
    }

    private fun applyCreativeItemDefinitions(packet: CreativeContentPacket, session: RubidiumRelaySession) {
        try {
            val contents = packet.contents
            if (contents.isEmpty()) {
                return
            }

            val byRuntimeId = LinkedHashMap<Int, ItemDefinition>()
            for (creativeItem in contents) {
                val itemData = creativeItem.item
                val def = runCatching { itemData.definition }.getOrNull()
                if (def == null) continue
                val identifier = def.identifier
                if (identifier.isNullOrBlank()) continue
                if (identifier == "minecraft:air") continue
                byRuntimeId.putIfAbsent(def.runtimeId, def)
            }

            if (byRuntimeId.isEmpty()) {
                return
            }

            mergeItemDefs(byRuntimeId.values, session, priority = 1)
        } catch (e: Exception) {
        }
    }

    private fun applyCameraDefinitions(packet: CameraPresetsPacket, session: RubidiumRelaySession) {
        try {
            val cameraDefs = SimpleDefinitionRegistry.builder<NamedDefinition>()
                .addAll(
                    packet.presets.mapIndexed { i, preset ->
                        SimpleNamedDefinition(preset.identifier, i)
                    }
                )
                .build()

            session.clientSession.peer.codecHelper.cameraPresetDefinitions = cameraDefs
            session.serverSession?.peer?.codecHelper?.cameraPresetDefinitions = cameraDefs
        } catch (e: Exception) {
        }
    }
}
