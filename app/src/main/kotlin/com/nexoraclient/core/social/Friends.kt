package com.rubidiumclient.core.social

import com.rubidiumclient.RubidiumClientApp
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.DiagLog
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Global friend / enemy list + the ABSOLUTE friend-attack guard.
 *
 * Design decisions (see docs/FRIENDS.md):
 *  - Lists are GLOBAL (user decision), matching gamertags case-insensitively
 *    since that's what every server displays/whispers on.
 *  - The attack guard is NOT a module and NOT on the PacketEventBus: modules
 *    (including ported combat modules that know nothing about this client)
 *    inject their attacks through serverBound()/sendToServer() which bypasses
 *    the event bus entirely. RubidiumRelaySession.sendToServer() is the single
 *    funnel every outbound packet passes through — real client taps as well
 *    as module injections — so FriendGuard.isHostileAttackOnFriend() runs
 *    there. No module can bypass it, by construction.
 *  - Friend/enemy RANKING for KillAura (enemies first → neutrals → friends)
 *    is a later milestone; today the lists drive: absolute attack block,
 *    whisper notifications, and the .friend/.enemy chat commands.
 */
object FriendLibrary {

    private const val TAG  = "FriendLibrary"
    private const val FILE = "friends.json"

    /** case-insensitive gamertag sets (stored lowercased, canonical kept in casemap) */
    private val friends = Collections.synchronizedSet(LinkedHashSet<String>())
    private val enemies = Collections.synchronizedSet(LinkedHashSet<String>())
    private val friendCanonical = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val enemyCanonical  = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile var notifyEnabled: Boolean = true
    @Volatile var msgAdd:    String = "Added you to my friend list"
    @Volatile var msgRemove: String = "Removed you from my friend list"

    private val ioLock = Any()

    fun load() {
        try {
            val f = file()
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            fun fill(arr: JSONArray?, set: MutableSet<String>, canon: java.util.concurrent.ConcurrentHashMap<String, String>) {
                set.clear(); canon.clear()
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i).takeIf { it.isNotBlank() } ?: continue
                    set.add(n.lowercase()); canon[n.lowercase()] = n
                }
            }
            fill(j.optJSONArray("friends"), friends, friendCanonical)
            fill(j.optJSONArray("enemies"), enemies, enemyCanonical)
            notifyEnabled = j.optBoolean("notifyEnabled", true)
            msgAdd        = j.optString("msgAdd", msgAdd)
            msgRemove     = j.optString("msgRemove", msgRemove)
            DiagLog.log(TAG, "loaded ${friends.size} friends, ${enemies.size} enemies")
        } catch (e: Exception) {
            DiagLog.log(TAG, "load failed: ${e.message}")
        }
    }

    fun save() {
        synchronized(ioLock) {
            try {
                val j = JSONObject()
                j.put("friends", JSONArray(friendCanonical.values.toSortedSet(String.CASE_INSENSITIVE_ORDER)))
                j.put("enemies", JSONArray(enemyCanonical.values.toSortedSet(String.CASE_INSENSITIVE_ORDER)))
                j.put("notifyEnabled", notifyEnabled)
                j.put("msgAdd", msgAdd)
                j.put("msgRemove", msgRemove)
                file().writeText(j.toString(2))
            } catch (e: Exception) {
                DiagLog.log(TAG, "save failed: ${e.message}")
            }
        }
    }

    private fun file(): File = File(RubidiumClientApp.instance.filesDir, FILE)

    /** Returns true when the state actually changed (was not already a friend). */
    fun addFriend(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return false
        // mutual exclusion: a friend can't be an enemy
        if (enemies.remove(key)) {
            enemyCanonical.remove(key)
        }
        val changed = friends.add(key)
        if (changed) { friendCanonical[key] = name.trim(); save() }
        return changed
    }

    fun removeFriend(name: String): Boolean {
        val key = name.trim().lowercase()
        val changed = friends.remove(key)
        if (changed) { friendCanonical.remove(key); save() }
        return changed
    }

    fun addEnemy(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return false
        if (friends.remove(key)) {
            friendCanonical.remove(key)
        }
        val changed = enemies.add(key)
        if (changed) { enemyCanonical[key] = name.trim(); save() }
        return changed
    }

    fun removeEnemy(name: String): Boolean {
        val key = name.trim().lowercase()
        val changed = enemies.remove(key)
        if (changed) { enemyCanonical.remove(key); save() }
        return changed
    }

    fun isFriend(name: String?): Boolean = name != null && friends.contains(name.trim().lowercase())
    fun isEnemy (name: String?): Boolean = name != null && enemies.contains(name.trim().lowercase())

    fun friendList(): List<String> = friendCanonical.values.toSortedSet(String.CASE_INSENSITIVE_ORDER).toList()
    fun enemyList (): List<String> = enemyCanonical .values.toSortedSet(String.CASE_INSENSITIVE_ORDER).toList()

    /** Online canonical name (player-list casing first; entities as fallback). */
    fun canonicalOnlineName(name: String): String? = EntityTracker.onlineNameOf(name)

    // ── whisper notifications (rate-limited queue) ─────────────────────────

    private data class Whisper(val session: RubidiumRelaySession, val to: String, val text: String)
    private val whisperQueue = ConcurrentLinkedQueue<Whisper>()
    private val whisperPump  = java.util.concurrent.atomic.AtomicBoolean(false)

    /** /w <name> <msg> via CommandRequestPacket; silent drop if not online / disabled. */
    fun whisper(session: RubidiumRelaySession?, name: String, text: String) {
        val s = session ?: return
        if (!notifyEnabled) return
        val canon = canonicalOnlineName(name) ?: run {
            DiagLog.log(TAG, "whisper skipped: '$name' not resolvable online")
            localSay(s, "§7[Friends]§r $name isn't online — no notification sent.")
            return
        }
        whisperQueue.add(Whisper(s, canon, text))
        DiagLog.log(TAG, "whisper queued: /w $canon $text")
        startPumpIfIdle()
    }

    private fun startPumpIfIdle() {
        if (!whisperPump.compareAndSet(false, true)) return
        thread(name = "friend-whisper-pump", isDaemon = true) {
            try {
                while (true) {
                    val w = whisperQueue.poll()
                    if (w == null) {
                        // drain is atomic w.r.t. the CAS: check once more before
                        // releasing, so a concurrent add() never gets stranded.
                        whisperPump.set(false)
                        if (whisperQueue.peek() != null) {
                            if (!whisperPump.compareAndSet(false, true)) return@thread
                            continue
                        }
                        return@thread
                    }
                    try {
                        // Command origin is mandatory on the wire (0x4D layout) —
                        // mirrors the proven builder in AutoTravel/AutoBaseFinder;
                        // requestId gets a proper UUID string (some PMMP forks
                        // reject an empty one).
                        w.session.serverBound(CommandRequestPacket().apply {
                            command = "/w ${w.to} ${w.text}"
                            commandOriginData = org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData(
                                org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType.PLAYER,
                                java.util.UUID.randomUUID(),
                                java.util.UUID.randomUUID().toString(),
                                0L
                            )
                            isInternal = false
                        })
                        DiagLog.log(TAG, "whisper sent: /w ${w.to} ${w.text}")
                    } catch (e: Exception) {
                        DiagLog.log(TAG, "whisper send exception: ${e.message}")
                    }
                    // one server command per 350 ms — batches never burst within one tick
                    try { Thread.sleep(350) } catch (_: InterruptedException) { return@thread }
                }
            } finally {
                whisperPump.set(false)
            }
        }
    }

    private fun localSay(session: RubidiumRelaySession, msg: String) {
        try {
            session.clientBound(TextPacket().apply {
                type               = TextPacket.Type.SYSTEM
                isNeedsTranslation = false
                sourceName         = ""
                xuid               = ""
                platformChatId     = ""
                setMessage(msg)
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }
}

/**
 * The choke-point check itself. Called from RubidiumRelaySession.sendToServer —
 * the single outbound funnel shared by real client packets AND module
 * injections — so there is literally no packet path an attack on a friend
 * can take.
 *
 * Bedrock C2S attack = InventoryTransactionPacket / ITEM_USE_ON_ENTITY /
 * actionType == 1 (matches utils/PacketUtil.sendAttack). Interact/interact-at
 * variants (actionType 2, right-click use) are NOT combat and pass through.
 */
object FriendGuard {
    private const val TAG = "FriendGuard"
    private const val NOTICE_THROTTLE_MS = 2000L

    private val lastNotice = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** true = DROP this packet (hostile attack on a friend). */
    fun isAttackOnFriend(packet: BedrockPacket, session: RubidiumRelaySession?): Boolean {
        val p = packet as? org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
            ?: return false
        if (p.transactionType != org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE_ON_ENTITY) return false
        if (p.actionType != 1) return false                                     // 1 = ATTACK only
        val rid = p.runtimeEntityId
        val name = try {
            EntityTracker.nameOf(rid)
        } catch (_: Exception) { null }
        if (!FriendLibrary.isFriend(name)) return false

        val now = System.currentTimeMillis()
        if (now - (lastNotice[rid] ?: 0L) >= NOTICE_THROTTLE_MS) {
            lastNotice[rid] = now
            runCatching {
                DiagLog.log(TAG, "blocked attack on friend '$name' (rid=$rid)")
                session?.clientBound(TextPacket().apply {
                    type               = TextPacket.Type.SYSTEM
                    isNeedsTranslation = false
                    sourceName         = ""
                    xuid               = ""
                    platformChatId     = ""
                    setMessage("§e[Friends]§r blocked an attack on friend §b$name§r (that's what friend protection is for).")
                    setFilteredMessage("")
                })
            }
        }
        return true
    }
}
