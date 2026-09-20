package com.rubidiumclient.core.commands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rubidiumclient.RubidiumClientApp
import com.rubidiumclient.config.Config
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.core.social.FriendLibrary
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.misc.Schematica
import com.rubidiumclient.utils.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.math.floor

/**
 * Chat command system ("." prefix) — see docs/CHAT-COMMANDS.md.
 *
 * Hard guarantees (user requirements, do not relax):
 *  1. LOCAL-ONLY RESPONSES. Every reply is an S2C TextPacket (Type.SYSTEM)
 *     fed straight into clientBound() — it never exists as a C2S packet, so
 *     .coords output (or any other reply) can never leak publicly.
 *  2. NO PREFIX-LINE MATCHING. A chat message is swallowed only when the token
 *     stream EXACTLY matches a registered command name (case-insensitive,
 *     longest-first including sub-commands). "." alone, "._.", unknown words
 *     like ".hello" — all pass through to the server as normal chat, and any
 *     dispatch exception fails OPEN (the packet goes through untouched).
 *  3. Interception happens on the real client's outbound path only
 *     (PacketEventBus priority -900, runs before all module listeners but
 *     after FriendGuard). Messages a module injects via serverBound() don't
 *     cross the bus at all — they can never echo-trigger a command.
 */
object ChatCommands : PacketEventBus.PacketListener {

    private const val TAG    = "ChatCommands"
    private const val PREFIX = "."

    override val priority: Int = -900
    override val pauseWhileEating: Boolean = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class Def(
        val name       : String,
        val syntax     : String,
        val category   : String,
        val description: String,
        val example    : String,
        val run        : (RubidiumRelaySession, List<String>) -> Unit
    )

    private val commands = LinkedHashMap<String, Def>()

    fun init() {
        registerAll()
        PacketEventBus.register(this)
        FriendLibrary.load()
        DiagLog.log(TAG, "registered ${commands.size} chat commands")
    }

    // ── interception ─────────────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!event.isClientToServer) return
        val p = event.packet as? TextPacket ?: return
        if (p.type != TextPacket.Type.CHAT) return                // only real chat lines
        val msg = p.message ?: return
        if (!msg.startsWith(PREFIX)) return
        try {
            dispatch(event, msg)                                    // fail-open anywhere below
        } catch (e: Exception) {
            DiagLog.log(TAG, "dispatch exception — passing packet through: ${e.message}")
        }
    }

    private fun dispatch(event: PacketEvent, raw: String) {
        val toks = raw.trim().split(Regex("\\s+"))
        if (toks.isEmpty()) return
        val firstToken = toks[0].removePrefix(PREFIX).lowercase()
        if (firstToken.isEmpty()) return                               // "." alone → normal chat

        // longest exact command-name match, up to 3 tokens ("build start", "friend add", ...)
        var matched: Def? = null
        var nameToks = 0
        for (n in 3 downTo 1) {
            if (n > toks.size) continue
            val candidate = toks.subList(0, n).joinToString(" ")
                .removePrefix(PREFIX).lowercase()
            commands[candidate]?.let { matched = it; nameToks = n }
            if (matched != null) break
        }
        val def = matched ?: return                               // NOT a command → let the chat line through

        event.cancel()                                            // swallowed — the server never sees it
        DiagLog.log(TAG, "matched '${def.name}' → swallowed and executing locally")
        val args    = toks.subList(nameToks, toks.size)
        val session = event.session
        scope.launch {
            runCatching { def.run(session, args) }
                .onFailure {
                    DiagLog.log(TAG, "${def.name} failed: ${it.message}")
                    say(session, "§c[Cmd]§r ${def.name} failed: ${it.message}")
                }
        }
    }

    // ── local-only reply ─────────────────────────────────────────────────────

    private fun say(session: RubidiumRelaySession, message: String) {
        runCatching {
            session.clientBound(TextPacket().apply {
                type               = TextPacket.Type.SYSTEM
                isNeedsTranslation = false
                sourceName         = ""
                xuid               = ""
                platformChatId     = ""
                setMessage(message)
                setFilteredMessage("")
            })
        }
    }

    // ── registry ─────────────────────────────────────────────────────────────

    private fun reg(def: Def) { commands[def.name.lowercase()] = def }

    private fun registerAll() {
        if (commands.isNotEmpty()) return

        reg(Def("help", ".help [command]", "general",
            "Lists every command, or full usage for one command.", ".help friend add") { s, a ->
            if (a.isEmpty()) {
                say(s, "§b[Cmd]§r ${commands.size} commands — prefix '${PREFIX}', responses are local-only:")
                commands.values.groupBy { it.category }.forEach { (cat, defs) ->
                    say(s, "§7$cat: §f" + defs.joinToString("§7, §f") { it.syntax })
                }
            } else {
                val q = a.joinToString(" ").lowercase()
                val d = commands[q] ?: commands.entries.firstOrNull { (k, _) -> k.startsWith(q) }?.value
                if (d == null) say(s, "§c[Cmd]§r no such command: $q")
                else say(s, "§b[Cmd]§r ${d.syntax} — ${d.description} e.g. ${d.example}")
            }
        })

        reg(Def("coords", ".coords [copy]", "general",
            "Prints your X/Y/Z in LOCAL chat. 'copy' also puts it on the clipboard (Android shows a toast).",
            ".coords copy") { s, a ->
            val (x, y, z) = Triple(floor(EntityTracker.selfX).toInt(),
                floor(EntityTracker.selfY).toInt(), floor(EntityTracker.selfZ).toInt())
            val text = "$x $y $z"
            say(s, "§b[Cmd]§r your coords: §f$x §7/§f $y §7/§f $z")
            if (a.firstOrNull()?.equals("copy", true) == true) {
                DiagLog.log(TAG, "coords copy requested")
                val app = RubidiumClientApp.instance
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("coords", text))
                        DiagLog.log(TAG, "clipboard write OK: $text")
                        Toast.makeText(app, "coords copied ($text)", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        DiagLog.log(TAG, "clipboard write FAILED: ${it.message}")
                    }
                }
            }
        })

        reg(Def("toggle", ".toggle <module>", "general",
            "Enables or disables a module without opening the UI. Partial, case-insensitive names OK.",
            ".toggle killaura") { s, a ->
            if (a.isEmpty()) { say(s, "§c[Cmd]§r usage: .toggle <module>"); return@Def }
            val q = a.joinToString(" ")
            val exact = ModuleManager.byName(q)
            val module: BaseModule? = exact ?: run {
                val cands = ModuleManager.getAll().filter { it.name.contains(q, true) }
                if (cands.size > 1) { say(s, "§c[Cmd]§r ambiguous '$q': " + cands.joinToString { it.name }); null }
                else cands.firstOrNull()
            }
            if (module == null) { if (exact == null && ModuleManager.getAll().none { it.name.contains(q, true) }) say(s, "§c[Cmd]§r no module matches '$q'"); return@Def }
            ModuleManager.toggle(module)
            say(s, "§b[Cmd]§r ${module.name} is now ${if (module.isEnabled) "§aON" else "§cOFF"}")
        })

        reg(Def("panic", ".panic", "general",
            "Disables every active module immediately.", ".panic") { s, _ ->
            val n = ModuleManager.enabledCount()
            ModuleManager.disableAll()
            say(s, "§c[Cmd]§r PANIC — $n module(s) disabled.")
        })

        reg(Def("list", ".list", "general",
            "Lists every module with its on/off state.", ".list") { s, _ ->
            ModuleManager.modules.forEach {
                say(s, "${if (it.isEnabled) "§aON §r" else "§7off §r"}${it.name} §8(${it.category.displayName})")
            }
        })

        // ── config ──────────────────

        reg(Def("config list", ".config list", "config",
            "Lists every saved config.", ".config list") { s, _ ->
            scope.launch {
                val profiles = runCatching { Config.getProfilesBlocking() }.getOrElse { emptyList() }
                if (profiles.isEmpty()) say(s, "§7[Cfg]§r no saved configs")
                else say(s, "§7[Cfg]§r saved: " + profiles.joinToString { it.name })
            }
        })

        reg(Def("config current", ".config current", "config",
            "Shows the config last saved/loaded through chat (best-effort tracking).", ".config current") { s, _ ->
            say(s, "§7[Cfg]§r current: ${currentConfig ?: "§8unknown (load or save one first)"}")
        })

        reg(Def("config save", ".config save <name>", "config",
            "Saves the current module state under a name.", ".config save base1") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cfg]§r usage: .config save <name>"); return@Def }
            scope.launch {
                val ok = runCatching { Config.save(name) }.getOrDefault(false)
                if (ok) { currentConfig = name; say(s, "§b[Cfg]§r saved as §f$name") }
                else say(s, "§c[Cfg]§r save failed")
            }
        })

        reg(Def("config load", ".config load <name>", "config",
            "Loads a named config (same code path as the GUI).", ".config load base1") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cfg]§r usage: .config load <name>"); return@Def }
            scope.launch {
                val ok = runCatching { Config.load(name) }.getOrDefault(false)
                if (ok) { currentConfig = name; say(s, "§b[Cfg]§r loaded §f$name") }
                else say(s, "§c[Cfg]§r config '$name' not found")
            }
        })

        // ── friends / enemies ──────────────────────────────────────────

        reg(Def("friend add", ".friend add <name>", "social",
            "Adds a player to the GLOBAL friend list. /w's them if online (configurable).",
            ".friend add Steve") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cmd]§r usage: .friend add <name>"); return@Def }
            if (FriendLibrary.addFriend(name)) {
                say(s, "§b[Friends]§r §a+§f $name added")
                FriendLibrary.whisper(s, name, FriendLibrary.msgAdd)
            } else say(s, "§7[Friends]§r $name was already a friend")
        })

        reg(Def("friend remove", ".friend remove <name>", "social",
            "Removes a player from the friend list.", ".friend remove Steve") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cmd]§r usage: .friend remove <name>"); return@Def }
            if (FriendLibrary.removeFriend(name)) {
                say(s, "§b[Friends]§r §c-§f $name removed")
                FriendLibrary.whisper(s, name, FriendLibrary.msgRemove)
            } else say(s, "§7[Friends]§r $name wasn't on the list")
        })

        reg(Def("friend list", ".friend list", "social",
            "Lists all friends (global scope).", ".friend list") { s, _ ->
            val l = FriendLibrary.friendList()
            say(s, if (l.isEmpty()) "§7[Friends]§r list is empty" else "§b[Friends]§r §f" + l.joinToString("§7, §f"))
        })

        reg(Def("friend notify", ".friend notify [on|off]", "social",
            "Toggles the automatic /w notification on friend add/remove.", ".friend notify off") { s, a ->
            FriendLibrary.notifyEnabled = when (a.firstOrNull()?.lowercase()) {
                "on" -> true; "off" -> false; else -> !FriendLibrary.notifyEnabled
            }
            FriendLibrary.save()
            say(s, "§b[Friends]§r notifications ${if (FriendLibrary.notifyEnabled) "§aON" else "§cOFF"}")
        })

        reg(Def("friend msg", ".friend msg <add|remove> <text...>", "social",
            "Sets the /w text sent on friend add / remove.", ".friend msg add hi there") { s, a ->
            if (a.size < 2) { say(s, "§c[Cmd]§r usage: .friend msg <add|remove> <text>"); return@Def }
            val text = a.drop(1).joinToString(" ")
            when (a[0].lowercase()) {
                "add"    -> FriendLibrary.msgAdd    = text
                "remove" -> FriendLibrary.msgRemove = text
                else     -> { say(s, "§c[Cmd]§r usage: .friend msg <add|remove> <text>"); return@Def }
            }
            FriendLibrary.save()
            say(s, "§b[Friends]§r ${a[0]} message = §f\"$text\"")
        })

        reg(Def("enemy add", ".enemy add <name>", "social",
            "Adds a player to the GLOBAL enemy list (KillAura priority input in a later phase).",
            ".enemy add Steve") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cmd]§r usage: .enemy add <name>"); return@Def }
            if (FriendLibrary.addEnemy(name)) say(s, "§b[Enemies]§r §c+§f $name added")
            else say(s, "§7[Enemies]§r $name was already an enemy")
        })

        reg(Def("enemy remove", ".enemy remove <name>", "social",
            "Removes a player from the enemy list.", ".enemy remove Steve") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cmd]§r usage: .enemy remove <name>"); return@Def }
            if (FriendLibrary.removeEnemy(name)) say(s, "§b[Enemies]§r §a-§f $name removed")
            else say(s, "§7[Enemies]§r $name wasn't on the list")
        })

        reg(Def("enemy list", ".enemy list", "social",
            "Lists all enemies (global scope).", ".enemy list") { s, _ ->
            val l = FriendLibrary.enemyList()
            say(s, if (l.isEmpty()) "§7[Enemies]§r list is empty" else "§b[Enemies]§r §f" + l.joinToString("§7, §f"))
        })

        // ── schematic / build ──────────────────────────────────────────

        reg(Def("schem load", ".schem load <file>", "build",
            "Loads a schematic by filename from the schematics folder (enables Schematica if needed).",
            ".schem load base.litematic") { s, a ->
            val name = a.firstOrNull() ?: run { say(s, "§c[Cmd]§r usage: .schem load <file>"); return@Def }
            val schem = schematica(s) ?: return@Def
            if (!schem.isEnabled) ModuleManager.enable(schem)
            schem.cmdSchemLoad(name)
            say(s, "§b[Schem]§r loading §f$name§r…")
        })

        reg(Def("schem toggle", ".schem toggle", "build",
            "Toggles just the ghost render on/off.", ".schem toggle") { s, _ ->
            val schem = schematica(s) ?: return@Def
            val vis = schem.cmdSchemToggleGhost()
            say(s, "§b[Schem]§r ghost ${if (vis) "§aVISIBLE" else "§cHIDDEN"}")
        })

        reg(Def("schem layer", ".schem layer <n>", "build",
            "Sets the visible Y layer (0 = all).", ".schem layer 6") { s, a ->
            val n = a.firstOrNull()?.toIntOrNull() ?: run { say(s, "§c[Cmd]§r usage: .schem layer <n>"); return@Def }
            val schem = schematica(s) ?: return@Def
            schem.cmdSchemLayer(n)
            say(s, "§b[Schem]§r layer = $n")
        })

        reg(Def("schem nudge", ".schem nudge <x> <y> <z>", "build",
            "Offsets the ghost anchor (recomputed from your position).", ".schem nudge 1 0 -2") { s, a ->
            if (a.size < 3) { say(s, "§c[Cmd]§r usage: .schem nudge <x> <y> <z>"); return@Def }
            val (x, y, z) = Triple(a[0].toIntOrNull(), a[1].toIntOrNull(), a[2].toIntOrNull())
            if (x == null || y == null || z == null) { say(s, "§c[Cmd]§r numbers only"); return@Def }
            val schem = schematica(s) ?: return@Def
            schem.cmdSchemNudge(x, y, z)
            say(s, "§b[Schem]§r nudged ($x, $y, $z)")
        })

        reg(Def("build start", ".build start", "build",
            "Starts the printer (enables Schematica if needed).", ".build start") { s, _ ->
            val schem = schematica(s) ?: return@Def
            if (!schem.isEnabled) ModuleManager.enable(schem)
            say(s, "§b[Build]§r " + schem.cmdBuildStart())
        })

        reg(Def("build stop", ".build stop", "build",
            "Stops the printer cleanly — in-flight placements complete, pending cells abandoned.",
            ".build stop") { s, _ ->
            val schem = schematica(s) ?: return@Def
            say(s, "§b[Build]§r " + schem.cmdBuildStop())
        })

        reg(Def("build status", ".build status", "build",
            "Progress: placed / total, pending, in-flight, failed, circuit-breaker.",
            ".build status") { s, _ ->
            val schem = schematica(s) ?: return@Def
            say(s, "§b[Build]§r " + schem.cmdBuildStatus())
        })
    }

    @Volatile private var currentConfig: String? = null

    private fun schematica(s: RubidiumRelaySession): Schematica? {
        val schem = ModuleManager.byName("Schematica") as? Schematica
        if (schem == null) say(s, "§c[Cmd]§r Schematica module not found")
        return schem
    }
}
