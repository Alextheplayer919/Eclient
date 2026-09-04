package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name        = "ChatSpammer",
    category    = ModuleCategory.MISC,
    description = "Chat prefix + öldürme/logout spam"
) {
    companion object {
        private const val VERSION      = "v2.1"
        private const val DEFAULT_TAG  = "Rubidium $VERSION"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30
        private const val LOGOUT_RANGE = 256f
        private const val SNAPSHOT_INTERVAL_MS = 1000L

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    private val shortcut    = bool("Shortcut", false)
    private val killSpammer = bool("KillSpammer", true)
    private val chatSuffix  = string("Chat Suffix", "Rubidium $VERSION")
    private val killMessage = string("Kill Message", "> @here @{name} Killed by Rubidium| {junk} | Rubidium v2.1")
    private val logoutMessage = string("Logout Message", "> @here @{name} Ez Logged | {junk} | Rubidium v2.1")

    private val recentDeathMs    = ConcurrentHashMap<Long, Long>()
    private val recentLogoutMs   = ConcurrentHashMap<Long, Long>()
    private val knownPlayerNames = ConcurrentHashMap<Long, String>()
    private val runtimeIdNames   = ConcurrentHashMap<Long, String>()
    private val playerUniqueIds  = ConcurrentHashMap<Long, Long>() // runtimeId -> uniqueId

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var flushJob: Job? = null
    private var snapshotJob: Job? = null
    @Volatile private var activeSession: com.rubidiumclient.core.relay.RubidiumRelaySession? = null

    private data class PlayerSnapshot(val name: String, val x: Float, val y: Float, val z: Float, val isFriend: Boolean, val uniqueId: Long)
    private val playerSnapshots = ConcurrentHashMap<Long, PlayerSnapshot>()

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

        when (val p = event.packet) {

            is TextPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.sourceName == "__ox_internal__") return

                val raw = p.message?.trim() ?: return
                if (raw.isEmpty() || raw.startsWith("/")) return

                val suffix = chatSuffix.value.takeIf { it.isNotEmpty() } ?: DEFAULT_TAG
                val formatted = "> $raw | $suffix | ${randomJunk()}"
                event.cancelAndReplace(buildTextPacket(formatted))
            }

            is EntityEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (typeStr.contains("DEATH")) handleDeath(p.runtimeEntityId)
            }

            is PlayerListPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

                when (p.action) {
                    PlayerListPacket.Action.ADD -> {
                        p.entries.forEach { entry ->
                            val name = entry.name ?: return@forEach
                            if (name.isNotEmpty()) {
                                knownPlayerNames[entry.entityId] = name
                                playerUniqueIds[entry.entityId] = entry.entityId
                            }
                        }
                    }
                    PlayerListPacket.Action.REMOVE -> {
                        p.entries.forEach { entry ->
                            val uniqueId = entry.entityId
                            
                            // Önce tracked entity'den bilgi almaya çalış
                            val tracked = EntityTracker.getByUniqueId(uniqueId)
                            val snap = playerSnapshots[uniqueId]
                            
                            val isFriend = tracked?.isFriendEntity ?: snap?.isFriend ?: false
                            if (isFriend) {
                                knownPlayerNames.remove(uniqueId)
                                playerSnapshots.remove(uniqueId)
                                playerUniqueIds.remove(uniqueId)
                                return@forEach
                            }

                            // Mesafe kontrolü
                            val dist = when {
                                tracked != null -> EntityTracker.distanceTo(tracked)
                                snap != null -> EntityTracker.distanceTo(snap.x, snap.y, snap.z)
                                else -> Float.MAX_VALUE
                            }
                            
                            // Eğer mesafe çok uzaktaysa veya range içinde değilse atla
                            if (dist > LOGOUT_RANGE) {
                                knownPlayerNames.remove(uniqueId)
                                playerSnapshots.remove(uniqueId)
                                playerUniqueIds.remove(uniqueId)
                                return@forEach
                            }

                            // İsim bulma
                            val name = tracked?.name?.takeIf { it.isNotEmpty() }
                                ?: snap?.name?.takeIf { it.isNotEmpty() }
                                ?: knownPlayerNames[uniqueId]
                                ?: return@forEach

                            // Logout mesajını gönder
                            handleLogout(uniqueId, name)
                            
                            // Temizlik
                            knownPlayerNames.remove(uniqueId)
                            playerSnapshots.remove(uniqueId)
                            playerUniqueIds.remove(uniqueId)
                        }
                    }
                }
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        recentDeathMs.clear()
        recentLogoutMs.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        runtimeIdNames.clear()
        playerUniqueIds.clear()
        messageQueue.clear()

        flushJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                flushQueue()
                delay(QUEUE_DELAY_MS)
            }
        }
        snapshotJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                refreshPlayerSnapshots()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        recentDeathMs.clear()
        recentLogoutMs.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        runtimeIdNames.clear()
        playerUniqueIds.clear()
        messageQueue.clear()

        flushJob?.cancel()
        snapshotJob?.cancel()
        flushJob = null
        snapshotJob = null
    }

    private fun refreshPlayerSnapshots() {
        EntityTracker.getPlayers().forEach { e ->
            if (e.runtimeId == EntityTracker.selfRuntimeId) return@forEach
            playerSnapshots[e.uniqueId] = PlayerSnapshot(
                name = e.name,
                x = e.x,
                y = e.y,
                z = e.z,
                isFriend = e.isFriendEntity,
                uniqueId = e.uniqueId
            )
            if (e.name.isNotEmpty()) {
                runtimeIdNames[e.runtimeId] = e.name
                playerUniqueIds[e.runtimeId] = e.uniqueId
            }
        }
    }

    private fun flushQueue() {
        val session = activeSession ?: return
        if (!session.isServerReady) return
        val msg = messageQueue.poll() ?: return
        runCatching { session.sendToServer(buildTextPacket(msg)) }
    }

    private fun enqueue(message: String) {
        if (messageQueue.size >= MAX_QUEUE_SIZE) messageQueue.poll()
        messageQueue.offer(message)
    }

    private fun handleDeath(runtimeId: Long) {
        if (!killSpammer.value) return

        val now = System.currentTimeMillis()
        val last = recentDeathMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentDeathMs[runtimeId] = now

        val entity = EntityTracker.getById(runtimeId)
        if (entity?.isFriendEntity == true) return

        val name = entity?.name?.takeIf { it.isNotEmpty() }
            ?: runtimeIdNames[runtimeId]
            ?: return

        val template = killMessage.value.takeIf { it.isNotEmpty() } 
            ?: "> @here @{name} Killed by Rubidium| {junk} | Rubidium v2.1"
        
        val message = template
            .replace("{name}", name)
            .replace("{junk}", randomJunk())
        
        enqueue(message)
    }

    private fun handleLogout(uniqueId: Long, name: String) {
        val now = System.currentTimeMillis()
        val last = recentLogoutMs[uniqueId]
        if (last != null && now - last < 1500L) return
        recentLogoutMs[uniqueId] = now

        val template = logoutMessage.value.takeIf { it.isNotEmpty() } 
            ?: "> @here @{name} Ez Logged | {junk} | Rubidium v2.1"
        
        val message = template
            .replace("{name}", name)
            .replace("{junk}", randomJunk())
        
        enqueue(message)
    }

    private fun buildTextPacket(message: String): TextPacket = TextPacket().apply {
        type               = TextPacket.Type.CHAT
        isNeedsTranslation = false
        sourceName         = "__ox_internal__"
        xuid               = ""
        platformChatId     = ""
        setMessage(message)
        setFilteredMessage("")
    }

    private fun randomJunk(): String {
        val len = Random.nextInt(JUNK_RANGE.first, JUNK_RANGE.last + 1)
        return buildString(len) { repeat(len) { append(JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)]) } }
    }
}