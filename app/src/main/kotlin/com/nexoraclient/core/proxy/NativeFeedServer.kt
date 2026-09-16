package com.rubidiumclient.core.proxy

import com.rubidiumclient.utils.DiagLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Native feed server — HYBRID phase A.
 *
 * The patched Minecraft APK's libeclient_attach.so hooks ClientInstance::update
 * and streams the local player's real in-game state to us over a localhost
 * socket, one text line per frame (throttled ~33 Hz native-side):
 *
 *   EA1 <x> <y> <z> <rotA> <rotB> <vx> <vy> <vz> <tick>
 *
 * (rotA/rotB come straight from ActorRotationComponent — which of the two is
 * pitch vs yaw is validated on-device; the parser treats rotA=pitch, rotB=yaw,
 * see docs/HYBRID.md if they read swapped.)
 *
 * Every parsed line becomes EntityTracker self-state via ingestNativeFrame().
 * While the feed is fresh (EntityTracker.nativeActive), AuthInput echoes stop
 * overwriting self position/rotation — entity tracking otherwise behaves
 * exactly as it always has; the feed is an upgrade, not a dependency.
 *
 * Safety: binds 127.0.0.1 only, never the LAN. No data leaves the device.
 */
object NativeFeedServer {

    private const val TAG = "NativeFeed"
    const val PORT = 19137

    private var thread: Thread? = null
    @Volatile var started: Boolean = false
        private set
    @Volatile var connected: Boolean = false
        private set

    /** Idempotent-ish: second calls are no-ops while a serve thread lives. */
    fun start() {
        synchronized(this) {
            if (thread != null) return
            thread = Thread({ serve() }, "native-feed").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve() {
        started = true
        while (true) {
            try {
                ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                    DiagLog.log(TAG, "listening on 127.0.0.1:$PORT")
                    while (true) {
                        val client = server.accept()
                        Thread({ handle(client) }, "native-feed-client").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            } catch (e: Exception) {
                DiagLog.log(TAG, "accept loop failed: ${e.message} — retrying in 2s")
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun handle(sock: Socket) {
        connected = true
        DiagLog.log(TAG, "native feed connected (${sock.inetAddress})")
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                parse(line)
            }
        } catch (_: Exception) {
        } finally {
            connected = false
            try { sock.close() } catch (_: Exception) {}
            DiagLog.log(TAG, "native feed disconnected")
        }
    }

    /**
     * EA1 x y z rotA rotB vx vy vz tick
     *  0  1 2 3 4    5    6  7  8  9
     */
    private fun parse(line: String) {
        val p = line.split(' ')
        if (p.size != 10 || p[0] != "EA1") return
        val x    = p[1].toFloatOrNull() ?: return
        val y    = p[2].toFloatOrNull() ?: return
        val z    = p[3].toFloatOrNull() ?: return
        val rotA = p[4].toFloatOrNull() ?: return
        val rotB = p[5].toFloatOrNull() ?: return
        val vx   = p[6].toFloatOrNull() ?: return
        val vy   = p[7].toFloatOrNull() ?: return
        val vz   = p[8].toFloatOrNull() ?: return
        val tick = p[9].toLongOrNull()  ?: return
        // rotA=pitch, rotB=yaw per current assumption — flip here if the
        // on-device sanity log says they're swapped.
        EntityTracker.ingestNativeFrame(x, y, z, rotA, rotB, vx, vy, vz, tick)
    }
}
