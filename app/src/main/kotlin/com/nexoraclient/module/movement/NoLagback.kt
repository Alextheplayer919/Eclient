package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.proxy.MovementCompliance
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

private enum class NoLagMode { ADAPTIVE, SILENT }

/**
 * NoLagback v2 — research-driven, replaces the naive "swallow correction
 * packet" approach.
 *
 * Why v1 was weak: dropping CorrectPlayerMovePredictionPacket only hides the
 * snap from YOUR eyes. The server's authoritative model is unaffected, each
 * subsequent AuthInput mismatches harder, and (vanilla BDS defaults:
 * distance>0.3 blocks, score>20, sustained 500ms) the anomaly budget burns
 * until action. Desync doesn't fool the server, it tattles louder.
 *
 * v2 ADAPTIVE instead runs a compliance governor (core/proxy/MovementCompliance):
 *   • every correction ANCHORS a "server belief" and raises pressure;
 *   • MotionFly reads pressure+drift and SHRINKS its speed before the next
 *     anomaly window instead of after an accumulation — right after a snap,
 *     speed collapses so the re-drift takes many ticks, letting the score
 *     mechanic decay instead of ticking up;
 *   • Smart Resync: when drift exceeds a hard budget OR corrections come
 *     faster than 3/s, we perform ONE deliberate tiny rubber-band of our own
 *     (mirror the belief straight into the game). Costs you a sub-block snap,
 *     buys a full drift reset — replacing the spiraling anonymous-lagback
 *     loop with rare controlled ones.
 *
 * SILENT mode keeps the v1 behavior (drop corrections, never comply) — for
 * comparison testing or servers with no movement enforcement at all.
 *
 * Limits, stated plainly: a trajectory that is *illegal* (hovering without
 * fly permission, sprint-jump-impossible speeds) cannot be fully invisible
 * to a movement-validating server, ever. Governor minimizes flag pressure
 * and flag RATE; it cannot turn "66 m/s" into "sprint".
 */
class NoLagback : BaseModule(
    name        = "NoLagback",
    category    = ModuleCategory.MOVEMENT,
    description = "Adaptive anti-lagback: governor throttles fly under correction pressure, smart micro-resyncs reset drift"
) {

    private val mode            = enum("Mode", NoLagMode.ADAPTIVE)
    private val smartResync     = bool("Smart Resync",   true)
    private val resyncDistance  = float("Resync Distance", 1.2f, 0.3f, 5f)
    private val climbBudget     = float("Climb Budget BPS", 4f, 0f, 10f) // 0 = guard off
    private val dropResets      = bool("Drop Resets",      false) // SILENT only
    private val dropCorrection  = bool("Drop Corrections", true)  // SILENT only

    private var lastResyncMs = 0L

    override fun onEnable() {
        super.onEnable()
        MovementCompliance.adaptive = (mode.value == NoLagMode.ADAPTIVE)
        MovementCompliance.onSessionStart(
            try { ServerConfig.getHostBlocking() } catch (_: Exception) { "unknown" }
        )
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        MovementCompliance.adaptive = false
        MovementCompliance.onSessionEnd()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        if (event.isServerToClient) {
            when (val p = event.packet) {
                is CorrectPlayerMovePredictionPacket -> {
                    MovementCompliance.noteCorrection(p.position.x, p.position.y, p.position.z)
                    if (mode.value == NoLagMode.SILENT && dropCorrection.value) event.cancel()
                }
                is MovePlayerPacket -> {
                    if (p.runtimeEntityId == EntityTracker.selfRuntimeId) {
                        when (p.mode) {
                            MovePlayerPacket.Mode.TELEPORT ->
                                MovementCompliance.noteServerTeleport(p.position.x, p.position.y, p.position.z)
                            MovePlayerPacket.Mode.RESPAWN -> { // Cloudburst's name for wire-RESET corrections
                                MovementCompliance.noteCorrection(p.position.x, p.position.y, p.position.z)
                                if (mode.value == NoLagMode.SILENT && dropResets.value) event.cancel()
                            }
                            else -> { }
                        }
                    }
                }
                else -> { }
            }
            return
        }

        // Every outgoing AuthInput = our timing tick for smart resync.
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        MovementCompliance.climbBudgetBps = climbBudget.value
        if (mode.value != NoLagMode.ADAPTIVE || !smartResync.value) return

        val session = event.session
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastResyncMs < 1500L) return
        if (!MovementCompliance.beliefFresh) return

        val drift = MovementCompliance.discrepancy()
        val storming = MovementCompliance.correctionsInLast(1000) >= 3
        if (drift > resyncDistance.value || storming) {
            // Deliberate micro rubber-band of OUR choosing: snap the game to
            // the server's anchored position, zero the drift ledger.
            PacketUtil.sendMove(
                session,
                MovementCompliance.beliefX, MovementCompliance.beliefY, MovementCompliance.beliefZ,
                EntityTracker.selfYaw, EntityTracker.selfPitch,
                onGround = true, teleport = true, mirrorToClient = true
            )
            EntityTracker.selfX = MovementCompliance.beliefX
            EntityTracker.selfY = MovementCompliance.beliefY
            EntityTracker.selfZ = MovementCompliance.beliefZ
            MovementCompliance.noteLocalResync()
            lastResyncMs = nowMs
        }
    }
}
