package com.rubidiumclient.module.misc

import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.DiagLog
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.AdventureSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket
import java.util.Locale

/**
 * FlightProbe — passive observability module for the NoLagback v3 verification
 * checklist (docs/NO_LAGBACK_V3_VERIFICATION.md). NEVER cancels or rewrites a
 * packet; only timestamps and logs to baba.txt via DiagLog with the VP|v1|
 * prefix. One enable = one phase marker (MARK) for the analyzer.
 *
 * What it captures, per checklist item:
 *   SG    StartGame settings            → items 4, 5 (movement mode, rewind size)
 *   CORR  161 with tick/pos/delta/ogap  → items 1, 3, 6, 9, 10
 *   CORRM MovePlayer RESET-as-correction→ items 10, 12
 *   MP    every self MovePlayer + mode  → item 12
 *   PSYNC client 322 emissions          → item 2 (answer-channel cadence)
 *   MOTN/ABIL/SAD/UAT/EFF/ADV/PLAY/DISC → correction-context events (item 8, 9)
 *   MOT   AuthInput motion probe (W/D)  → item 13 (live sanity check)
 *   MPROBE optional SetEntityMotion C2S → item 7 (default OFF)
 *
 * Relay hygiene invariant: an interceptor must never throw (a throw on the
 * forwarding path silently drops the packet) — every handler is guarded.
 */
class FlightProbe : BaseModule(
    name        = "FlightProbe",
    category    = ModuleCategory.MISC,
    description = "Passive wire logger for NoLagback v3 verification flights (baba.txt, VP|v1| lines)"
) {

    private val wdProbe     = bool("W/D Motion Probe", false)  // phase P5 helper
    private val motionProbe = bool("Motion Packet Probe", false) // item 7, CAUTION: live-server active probe

    private var phase = 0
    private var corrIdx = 0
    private var psyncCount = 0
    private var probeSent = false
    private var lastAuthTick = -1L
    private var lastAuthX = 0f; private var lastAuthY = 0f; private var lastAuthZ = 0f
    private var lastCorrMs = 0L
    private var lastMotionMs = 0L
    private var lastTeleMs = 0L
    private var lastAbilityMs = 0L
    private var lastSadTickMs = 0L
    private var lastUatTickMs = 0L
    private var lastWdLogMs = 0L

    private fun nowMs() = System.currentTimeMillis()
    private fun age(t: Long): Long = if (t == 0L) -1L else nowMs() - t

    private fun vp(msg: String) = DiagLog.log("VPROBE", "VP|v1|$msg")

    override fun onEnable() {
        super.onEnable()
        phase++
        probeSent = false
        val host = try { ServerConfig.getHostBlocking() } catch (_: Exception) { "unknown" }
        vp("|MARK|n=$phase|host=$host|wd=${wdProbe.value}|mp=${motionProbe.value}")
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        try {
            if (event.isServerToClient) handleS2C(event.packet) else handleC2S(event)
        } catch (e: Exception) {
            // Never let an exception reach the forwarding path (it would drop the packet).
            runCatching { vp("|ERR|${e.javaClass.simpleName}:${e.message?.take(60)}") }
        }
    }

    // ── S2C ──────────────────────────────────────────────────────────────

    private fun handleS2C(p: Any) {
        when (p) {
            is StartGamePacket -> vp(
                "|SG|mode=${p.authoritativeMovementMode}|rewindHist=${p.rewindHistorySize}" +
                        "|sabb=${p.isServerAuthoritativeBlockBreaking}"
            )

            is CorrectPlayerMovePredictionPacket -> {
                corrIdx++
                val gap = if (lastCorrMs == 0L) -1L else nowMs() - lastCorrMs
                lastCorrMs = nowMs()
                val lag = if (lastAuthTick < 0) -1L else lastAuthTick - p.tick
                vp(
                    "|CORR|i=$corrIdx|tick=${p.tick}|auth=$lastAuthTick|lag=$lag" +
                            "|pos=${fmt(p.position)}|dvec=${fmt(p.delta)}|og=${p.isOnGround}|gap=$gap" +
                            "|claim=$lastAuthX,${fmt(lastAuthY)},$lastAuthZ" +
                            "|ctx=${age(lastMotionMs)},${age(lastTeleMs)},${age(lastAbilityMs)}," +
                            "${age(lastSadTickMs)},${age(lastUatTickMs)}"
                )
            }

            is MovePlayerPacket -> {
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                vp("|MP|mode=${p.mode}(${p.mode.ordinal})|pos=${fmt(p.position)}|og=${p.isOnGround}|tick=${p.tick}")
                when (p.mode) {
                    MovePlayerPacket.Mode.TELEPORT -> lastTeleMs = nowMs()
                    MovePlayerPacket.Mode.RESPAWN -> { // Cloudburst name for wire-RESET (mode 1) corrections
                        corrIdx++
                        val gap = if (lastCorrMs == 0L) -1L else nowMs() - lastCorrMs
                        lastCorrMs = nowMs()
                        vp("|CORRM|i=$corrIdx|tick=${p.tick}|pos=${fmt(p.position)}|og=${p.isOnGround}|gap=$gap")
                    }
                    else -> { }
                }
            }

            is SetEntityMotionPacket -> {
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                lastMotionMs = nowMs()
                vp("|MOTN|vec=${fmt(p.motion)}")
            }

            is UpdateAbilitiesPacket -> { lastAbilityMs = nowMs(); vp("|ABIL") }

            is AdventureSettingsPacket -> vp("|ADV")

            is SetEntityDataPacket -> { // wire packet 39
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId || p.tick == 0L) return
                lastSadTickMs = nowMs()
                vp("|SAD|tick=${p.tick}")
            }

            is UpdateAttributesPacket -> { // wire packet 29
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (p.tick != 0L) lastUatTickMs = nowMs()
                vp("|UAT|tick=${p.tick}")
            }

            is MobEffectPacket -> {
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                vp("|EFF|event=${p.event}|effect=${p.effectId}")
            }

            is PlayStatusPacket -> vp("|PLAY|${p.status}")
            is DisconnectPacket -> vp("|DISC|${p.reason}")
            else -> { }
        }
    }

    // ── C2S ──────────────────────────────────────────────────────────────

    private fun handleC2S(event: PacketEvent) {
        when (val p = event.packet) {
            is PlayerAuthInputPacket -> {
                lastAuthTick = p.tick
                lastAuthX = p.position.x; lastAuthY = p.position.y; lastAuthZ = p.position.z
                if (wdProbe.value && nowMs() - lastWdLogMs >= 250L) {
                    lastWdLogMs = nowMs()
                    vp("|MOT|mx=${fmt(p.motion.x)}|my=${fmt(p.motion.y)}|tick=${p.tick}")
                }
                if (motionProbe.value && !probeSent) {
                    probeSent = true
                    runCatching {
                        event.session.serverBound(SetEntityMotionPacket().apply {
                            runtimeEntityId = EntityTracker.selfRuntimeId
                            motion = Vector3f.from(0f, 1.0f, 0f)
                        })
                        vp("|MPROBE|sent SetEntityMotion(self, dy=+1.0)")
                    }
                }
            }

            is UnknownPacket -> {
                if (p.packetId == 322) { // ClientMovementPredictionSyncPacket — no codec def in v975
                    psyncCount++
                    vp("|PSYNC|n=$psyncCount|auth=$lastAuthTick|bytes=${runCatching { p.payload.readableBytes() }.getOrDefault(-1)}")
                }
            }

            else -> { }
        }
    }

    private fun fmt(f: Float) = String.format(Locale.US, "%.3f", f)
    private fun fmt(v: Vector3f) = "${fmt(v.x)},${fmt(v.y)},${fmt(v.z)}"
}
