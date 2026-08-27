package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

class PopCounter : BaseModule(
    name        = "PopCounter",
    category    = ModuleCategory.MISC,
    description = "Offhand-polling tabanlı totem pop sayacı"
) {
    companion object {
        private const val VERSION      = "v2.1"
        private const val TAG_LINE     = "Rubidium Client $VERSION"
        private const val PVP_TAIL     = "by Rubidium Client | Best Mobile Client"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30

        private const val POLL_INTERVAL_MS = 150L
        private const val DEBOUNCE_MS      = 1200L
        private const val RANGE            = 100f

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22

        private val POP_MESSAGES = listOf(
            "> @here @{name} Popped {count} Totem $PVP_TAIL | {junk}",
            "> @here @{name} is actually totemfag | {count} Popped | {junk} | Rubidium Client",
            "> @here @{name} popped {count}x already lmao | {junk} | $TAG_LINE",
            "> @here bro @{name} needs {count} totems just to survive | {junk}",
            "> @here @{name} totem #{count} down, ez clap | {junk} | Rubidium Client",
            "> @here @{name} another totem gone, {count} total now | {junk} | $TAG_LINE",
            "> @here @{name} is farming totems fr | {count}x popped | {junk}",
            "> @here L totem @{name}, {count} down already | {junk} | Rubidium Client",
            "> @here @{name} totem #{count} confirmed dead | {junk} | $TAG_LINE",
            "> @here bro @{name} popped {count} and still losing | {junk}",
            "> @here @{name} thats {count} totems wasted for nothing | {junk} | Rubidium Client",
            "> @here @{name} totem economy crashing, {count} popped | {junk}",
            "> @here @{name} {count}x totem pop detected | {junk} | $TAG_LINE",
            "> @here @{name} keeps popping ({count}) but still cooked | {junk}",
            "> @here @{name} totem #{count} — nice try | {junk} | Rubidium Client",
            "> @here @{name} popping totems like candy | {count}x already | {junk} | $TAG_LINE",
            "> @here @{name} is a totem addict | {count} pops and counting | {junk} | Rubidium Client",
            "> @here @{name} still alive? {count} totems say otherwise | {junk}",
            "> @here @{name} totem count: {count} — bro just give up | {junk} | Rubidium Client",
            "> @here @{name} wasting totems like its nothing | {count}x popped | {junk}",
            "> @here @{name} literally cannot survive without totems | {count} pops | {junk} | $TAG_LINE",
            "> @here @{name} skill issue + {count} totems wasted | {junk} | Rubidium Client",
            "> @here @{name} has popped {count} totems and im still laughing | {junk}",
            "> @here @{name} that's {count} L's in totem form | {junk} | Rubidium Client",
            "> @here @{name} keep popping, we counting | {count}x now | {junk} | $TAG_LINE",
            "> @here @{name} totem # {count} — another one bites the dust | {junk}",
            "> @here @{name} is the reason totems are expensive | {count} popped | {junk} | Rubidium Client",
            "> @here @{name} {count} totems down, how many more? | {junk}",
            "> @here @{name} bro u need {count} totems to fight? LMAO | {junk} | Rubidium Client",
            "> @here @{name} collecting L's with {count} popped totems | {junk}",
            "> @here @{name} popped {count} — still garbage | {junk} | Rubidium Client",
            "> @here @{name} i counted {count} totems, u counted? | {junk}",
            "> @here @{name} totem merchant | {count} sales today | {junk} | $TAG_LINE",
            "> @here @{name} {count} pops and still no skill | {junk} | Rubidium Client",
            "> @here @{name} that totem was #{count}, how many left? | {junk}",
            "> @here @{name} imagine needing {count} totems just to exist | {junk}",
            "> @here @{name} totem farming simulator | {count}x popped | {junk} | Rubidium Client",
            "> @here @{name} you good? {count} totems says otherwise | {junk}",
            "> @here @{name} {count} pops later and still trash | {junk} | Rubidium Client",
            "> @here @{name} keep buying totems, we keep counting | {count}x | {junk}",
            "> @here @{name} L streak continues | {count} totems popped | {junk} | $TAG_LINE",
            "> @here @{name} totem count: {count} — ratio + L | {junk} | Rubidium Client",
            "> @here @{name} popped {count} totems and i still aint impressed | {junk}",
            "> @here @{name} ur totems are crying rn | {count} wasted | {junk}",
            "> @here @{name} {count} pops = {count} L's | {junk} | Rubidium Client",
            "> @here @{name} stop wasting totems challenge: impossible | {count}x | {junk}",
            "> @here @{name} totem # {count} — bro just log out | {junk} | $TAG_LINE",
            "> @here @{name} {count} totems and still negative KD | {junk} | Rubidium Client",
            "> @here @{name} totem pop counter going crazy | {count} already | {junk}"
        )
    }

    private val sendChat = bool("Send Chat", false)
    private val shortcut = bool("Shortcut",  false)

    private val lastHadTotem = ConcurrentHashMap<Long, Boolean>()
    private val popCounts    = ConcurrentHashMap<String, Int>()
    private val recentPopMs  = ConcurrentHashMap<Long, Long>()

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var pollJob: Job? = null
    private var flushJob: Job? = null
    @Volatile private var activeSession: com.rubidiumclient.core.relay.RubidiumRelaySession? = null

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

        val p = event.packet
        if (p is EntityEventPacket && event.direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            if (p.runtimeEntityId == EntityTracker.selfRuntimeId) {
                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (typeStr.contains("DEATH")) resetState()
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
        messageQueue.clear()

        pollJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                pollTotems()
                delay(POLL_INTERVAL_MS)
            }
        }
        flushJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                flushQueue()
                delay(QUEUE_DELAY_MS)
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
        messageQueue.clear()

        pollJob?.cancel()
        flushJob?.cancel()
        pollJob = null
        flushJob = null
    }

    private fun pollTotems() {
        if (!isEnabled) return

        val activeRuntimeIds = HashSet<Long>()

        EntityTracker.getPlayers().forEach { e ->
            if (e.runtimeId == EntityTracker.selfRuntimeId) return@forEach

            if (e.isFriendEntity) {
                lastHadTotem.remove(e.runtimeId)
                return@forEach
            }

            val dist = EntityTracker.distanceTo(e)
            if (dist > RANGE) {
                lastHadTotem.remove(e.runtimeId)
                return@forEach
            }

            activeRuntimeIds.add(e.runtimeId)

            val hasTotem = runCatching {
                InventoryUtil.isTotem(e.offHandItem)
            }.getOrElse { false }

            val had = lastHadTotem[e.runtimeId] ?: false
            if (had && !hasTotem) {
                val now = System.currentTimeMillis()
                val last = recentPopMs[e.runtimeId]
                if (last == null || now - last >= DEBOUNCE_MS) {
                    recentPopMs[e.runtimeId] = now

                    val name = e.name.takeIf { it.isNotEmpty() } ?: "unknown"
                    val count = (popCounts[name] ?: 0) + 1
                    popCounts[name] = count

                    if (sendChat.value) {
                        val text = POP_MESSAGES[Random.nextInt(POP_MESSAGES.size)]
                            .replace("{name}", name)
                            .replace("{count}", count.toString())
                            .replace("{junk}", randomJunk())
                        enqueue(text)
                    }
                }
            }
            lastHadTotem[e.runtimeId] = hasTotem
        }

        lastHadTotem.keys.retainAll(activeRuntimeIds)
        recentPopMs.keys.retainAll(activeRuntimeIds)
    }

    private fun resetState() {
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
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