package com.rubidiumclient.core.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.utils.DiagLog

/**
 * The fix for "modules do nothing when I join from the Servers tab".
 *
 * Why it happened: the relay only sees traffic that passes THROUGH it. Joining a
 * server by typing its real address in the Servers tab connects the game straight
 * to that server — the relay is skipped entirely, so no packet ever reaches a
 * module. It looks like "all modules are broken"; it is really "the game is not
 * talking to us". The LAN entry worked because that entry IS the relay.
 *
 * Bedrock exposes deep links for exactly this, so both directions can be one tap:
 *
 *   minecraft://?addExternalServer=<name>|<address>:<port>   add a server entry
 *   minecraft://connect?serverUrl=<address>&serverPort=<port> connect right now
 *
 * Pointing either one at 127.0.0.1:<relay port> puts the relay back in the path
 * while still using the Servers tab, which is what the player expects.
 *
 * Reference: Minecraft Bedrock deep-link handlers (Microsoft Learn).
 */
object MinecraftLink {

    private const val TAG = "MinecraftLink"

    /** Name the relay entry gets in the Servers tab. */
    const val RELAY_SERVER_NAME = "Rubidium Relay"

    /** The address the game must connect to for the relay to be in the path. */
    fun relayAddress(): String = "127.0.0.1:${ServerConfig.LOCAL_PROXY_PORT}"

    /** True when [host] already points at the relay (localhost + relay port). */
    fun isRelayAddress(host: String, port: Int): Boolean {
        val h = host.trim().lowercase()
        return (h == "127.0.0.1" || h == "localhost" || h == "::1") && port == ServerConfig.LOCAL_PROXY_PORT
    }

    /**
     * Adds (or refreshes) the relay as an entry in Minecraft's own server list, so
     * joining from the Servers tab goes through the relay.
     *
     * All three parts of the value are required by the game: name, address, port.
     */
    fun addRelayToServers(ctx: Context, name: String = RELAY_SERVER_NAME): Boolean =
        open(ctx, "minecraft://?addExternalServer=" +
            Uri.encode("$name|127.0.0.1:${ServerConfig.LOCAL_PROXY_PORT}"))

    /**
     * Launches the game straight into the relay — no server list, no chance of
     * bypassing it. This is the "just play" button.
     */
    fun connectThroughRelay(ctx: Context): Boolean =
        open(ctx, "minecraft://connect?serverUrl=127.0.0.1&serverPort=${ServerConfig.LOCAL_PROXY_PORT}")

    private fun open(ctx: Context, uri: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            DiagLog.log(TAG, "opened $uri")
            true
        } catch (e: ActivityNotFoundException) {
            DiagLog.log(TAG, "no handler for $uri (is Minecraft installed?) — ${e.message}")
            false
        } catch (e: Throwable) {
            DiagLog.log(TAG, "failed to open $uri — ${e.message}")
            false
        }
    }

    /**
     * What to tell the player when the relay is up but the game is not in the
     * path. Kept here so the wording and the address can never drift apart.
     */
    fun waitingHint(): String =
        "Minecraft is not connected through the relay. In the Servers tab join " +
            "\"$RELAY_SERVER_NAME\" (${relayAddress()}), or use Launch — a typed server " +
            "address bypasses the relay and no module can act."
}
