package com.rubidiumclient.core.relay

import com.rubidiumclient.core.relay.codec.CodecRegistry
import com.rubidiumclient.utils.DiagLog

/**
 * The one place that knows which game version this build targets.
 *
 * Why this file exists: the relay used to introduce itself with
 * `CodecRegistry.getLatestCodec()` — i.e. whatever the vendored library happened
 * to support newest (currently 1.26.50 / protocol 2193). Everything that
 * PRESENTS the relay to the game (the RakNet advertisement, the LAN pong, the
 * session's pre-negotiation default) therefore claimed a version the player is
 * not running. A 1.21.111 client pinging a "1.26.50" server is a version
 * mismatch before a single packet is exchanged.
 *
 * Per-session negotiation was never the problem — `AutoCodecListener` reads the
 * client's own `RequestNetworkSettings` and picks the matching codec, so a
 * 1.21.111 client, a 1.21.130 client and a 1.26.40 client each get their own
 * codec, item definitions and block palette. What was missing is a truthful
 * DEFAULT: the version this client ships for, used wherever the relay speaks
 * before it has seen a client.
 *
 * Target: Minecraft Bedrock **1.21.111**, protocol **844** — the exact build the
 * attach runtime is pinned to (APK sha256 69f6584c…, `Offsets_1_21_111.h`), so
 * the proxy and the in-game runtime describe the same game version.
 */
object TargetVersion {

    /** Protocol version of the target build. */
    const val PROTOCOL: Int = 844

    /** Human-readable version string of the target build. */
    const val MC_VERSION: String = "1.21.111"

    /**
     * The codec the relay presents itself with before it has seen a client.
     *
     * `getClosestCodec` never throws and never returns null: if the vendored
     * library is missing v844 it degrades to the nearest older codec instead of
     * crashing the relay. That is the right failure mode, but it must not be
     * SILENT — [verify] logs the resolved version so a degraded build is visible
     * in the diagnostics file instead of showing up as mysterious packet errors.
     */
    val codec: org.cloudburstmc.protocol.bedrock.codec.BedrockCodec by lazy {
        CodecRegistry.getClosestCodec(PROTOCOL)
    }

    /** True when the codec that resolved is exactly the targeted protocol. */
    fun isExact(): Boolean = codec.protocolVersion == PROTOCOL

    /** One line for the panel / diagnostics: what this build claims to be. */
    fun describe(): String =
        "target=$MC_VERSION(protocol $PROTOCOL) resolved=${codec.minecraftVersion ?: "?"}" +
            "(protocol ${codec.protocolVersion}) exact=${isExact()}"

    /**
     * Log the resolution at startup. Called once when a session is created so the
     * support claim for 1.21.111 is checkable on the device (baba.txt) without
     * adb: it prints the target, the codec that resolved, and the data files the
     * definitions loader picked for it.
     */
    fun verify() {
        runCatching {
            DiagLog.log("TargetVersion", describe())
            if (!isExact()) {
                DiagLog.log(
                    "TargetVersion",
                    "WARNING: codec for protocol $PROTOCOL is not available in this build — " +
                        "falling back to ${codec.protocolVersion}. Packet layouts for $MC_VERSION " +
                        "will differ; re-vendor relay/Protocol.",
                )
            }
            DiagLog.log("TargetVersion", Definitions.describeClosest(PROTOCOL))
        }
    }
}
