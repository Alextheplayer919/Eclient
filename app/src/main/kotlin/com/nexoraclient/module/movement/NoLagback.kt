package com.rubidiumclient.module.movement

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

/**
 * NoLagback — EXPERIMENTAL, built for anarchy servers.
 *
 * Bedrock rubber-banding works like this: the server disagrees with where
 * your movement put you, ships a correction packet (CorrectPlayerMovePrediction
 * for prediction mismatches, MovePlayer RESET for hard teleports back), and
 * the GAME client snaps you back obediently. This module simply never lets
 * those packets reach the game — from the client's perspective the correction
 * never happened, so fly keeps going.
 *
 * Cost of the lie: the server still knows where it thinks you are. Servers
 * that escalate (anticheat "moved too quickly" kicks, watchdog bans) keep
 * escalating — this hides the *visual snap*, not the *server's opinion*.
 * That's why it's a MOVEMENT experiment for anarchy, not a default-on feature.
 *
 * Drop Corrections (default on)  — filters prediction-mismatch packets. This
 *    is the common source of fly stutter/lagback on anarchy.
 * Drop Resets (default off)      — also swallows hard MovePlayer RESET
 *    teleports. More invasive: real teleports (death respawns, /tp,
 *    dimension changes) ALSO ride RESET-bearing packets in some flows, so
 *    leave this off unless flying legit teleports start feeling broken in
 *    the OPPOSITE direction.
 */
class NoLagback : BaseModule(
    name        = "NoLagback",
    category    = ModuleCategory.MOVEMENT,
    description = "Experimental: never let server correction packets rubber-band you (anarchy fly)"
) {

    private val dropCorrections = bool("Drop Corrections", true)
    private val dropResets      = bool("Drop Resets",      false)

    override fun onEnable() {
        super.onEnable()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isServerToClient) return
        when (val p = event.packet) {
            is CorrectPlayerMovePredictionPacket ->
                if (dropCorrections.value) event.cancel()
            is MovePlayerPacket ->
                if (dropResets.value && p.mode == MovePlayerPacket.Mode.RESET) event.cancel()
            else -> { }
        }
    }
}
