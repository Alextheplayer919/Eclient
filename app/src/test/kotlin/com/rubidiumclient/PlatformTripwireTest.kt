package com.rubidiumclient

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tripwires for platform changes that would break the app <-> game communication SILENTLY.
 * (docs/BRIDGE.md, "Platform").
 *
 * These read the project files, so a careless `targetSdk` bump fails CI with an explanation instead
 * of shipping an app whose LAN entry has quietly vanished.
 */
class PlatformTripwireTest {

    private fun read(vararg candidates: String): String =
        candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("none of ${candidates.toList()} found from ${File(".").absoluteFile}")

    private val gradle get() = read("build.gradle.kts", "app/build.gradle.kts")
    private val manifest get() = read("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

    private fun targetSdk(): Int =
        Regex("""targetSdk\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
            ?: error("targetSdk not found in build.gradle.kts")

    @Test fun targetingAndroid17_requiresTheLocalNetworkPermission() {
        val target = targetSdk()
        if (target >= 37) {
            assertTrue(
                "targetSdk=$target: from Android 17, an app that targets 37 may not send or receive UDP broadcast " +
                    "(or touch any LAN address) without ACCESS_LOCAL_NETWORK, and the OS drops the packets WITHOUT any " +
                    "error. The relay advertises itself with a broadcast to 255.255.255.255:19132 (LanBroadcaster), so " +
                    "the 'rubidium' LAN entry would silently disappear from the game. Declare " +
                    "android.permission.ACCESS_LOCAL_NETWORK AND request it at runtime before starting the relay, or " +
                    "keep targetSdk <= 36 (apps that target <= 36 get LAN access implicitly). See docs/BRIDGE.md.",
                manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"),
            )
        }
    }

    @Test fun loopbackSocketsNeedTheInternetPermission() {
        // The relay (127.0.0.1:19150) and the agent bridge (127.0.0.1:38170) are both plain TCP sockets.
        assertTrue(
            "android.permission.INTERNET missing from the manifest: every loopback socket would fail.",
            manifest.contains("android.permission.INTERNET"),
        )
    }
}
