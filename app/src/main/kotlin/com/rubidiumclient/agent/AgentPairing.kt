package com.rubidiumclient.agent

import android.content.Context
import com.rubidiumclient.utils.DiagLog
import java.io.File

/**
 * Pairing between the app and the in-game agent, and the switch that decides which
 * engine feeds the modules.
 *
 * Two engines exist and they must never both be active (double actions):
 *
 *   PROXY  - stock Minecraft + this app as a relay. Needs the game to join the
 *            relay (see MinecraftLink). Works on an unpatched device.
 *   MEMORY - patched Minecraft with the in-game agent on 127.0.0.1:38170. No
 *            relay, no second connection; the app reads state from memory and
 *            sends intents back.
 *
 * AUTO picks MEMORY when an agent answers and PROXY otherwise, which is what the
 * user expects: install the patched build and it just gets better.
 *
 * The token: the agent generates one and writes it to /sdcard/Download/
 * eclient_agent.token (it also honours a file that already exists). So if this app
 * writes a token there first, pairing is automatic — the agent picks it up on its
 * next start and both sides agree without any manual step.
 */
object AgentPairing {

    private const val TAG = "AgentPairing"
    private const val PREFS = "eclient_agent"
    private const val KEY_TOKEN = "token"

    /** Where the in-game agent looks for (and writes) the token. */
    private val sharedPaths = listOf(
        "/sdcard/Download/eclient_agent.token",
        "/sdcard/eclient_agent.token",
    )

    /**
     * The token this device uses for the agent, created once and reused.
     *
     * Not a password and not pretending to be one: it exists so another app on the
     * phone cannot drive the agent, and the agent treats it exactly that way.
     */
    fun token(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { if (it.length >= 8) return it }

        // Adopt a token the agent already published, so an existing pairing keeps
        // working instead of silently splitting into two.
        readShared()?.let { existing ->
            prefs.edit().putString(KEY_TOKEN, existing).apply()
            DiagLog.log(TAG, "adopted existing agent token from disk")
            return existing
        }

        val fresh = generate()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        writeShared(fresh)
        DiagLog.log(TAG, "generated a new agent token and published it for the agent")
        return fresh
    }

    private fun generate(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Best-effort: the app may be sandboxed away from /sdcard on newer Androids. */
    private fun writeShared(token: String) {
        for (path in sharedPaths) {
            try {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.writeText(token + "\n")
                DiagLog.log(TAG, "token published to $path")
                return
            } catch (t: Throwable) {
                DiagLog.log(TAG, "cannot write $path (${t.message})")
            }
        }
    }

    private fun readShared(): String? {
        for (path in sharedPaths) {
            try {
                val f = File(path)
                if (!f.exists()) continue
                val v = f.readText().trim()
                if (v.length >= 8) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }
}

/**
 * Decides the engine at startup and keeps the status surface honest.
 *
 * Called once from Application.onCreate. It never blocks and never throws: on a
 * stock device there is simply no agent, the probe fails, and PROXY stays active —
 * which is exactly the shipped behaviour.
 */
object AgentRuntime {

    private const val TAG = "AgentRuntime"

    @Volatile var engine: Backends.Mode = Backends.Mode.PROXY
        private set

    @Volatile var lastStatus: String = "not probed"
        private set

    /**
     * Probe for the in-game agent on a background thread; if it answers, switch to
     * memory mode (which attaches the EntityTracker feed). If it does not, PROXY
     * stays active untouched — the shipped behaviour on a stock device.
     */
    fun autoStart(ctx: Context) {
        val token = AgentPairing.token(ctx)
        Thread({
            try {
                val found = AgentClient.probe(token)
                if (found) {
                    Backends.useAgent(token)          // starts the client + tracker feed
                    engine = Backends.Mode.MEMORY
                    lastStatus = "agent connected (memory mode)"
                } else {
                    engine = Backends.Mode.PROXY
                    lastStatus = "no agent on 127.0.0.1:38170 (proxy mode)"
                }
            } catch (t: Throwable) {
                lastStatus = "probe failed: ${t.message}"
            }
            DiagLog.log(TAG, lastStatus)
        }, "eclient-agent-probe").apply { isDaemon = true }.start()
    }

    fun stop() {
        Backends.stopAgent()
        engine = Backends.Mode.PROXY
        lastStatus = "stopped"
    }

    /** One line for the panel: which engine is live and why. */
    fun statusLine(): String =
        "engine=${if (engine == Backends.Mode.MEMORY) "memory" else "proxy"} ($lastStatus)"
}
