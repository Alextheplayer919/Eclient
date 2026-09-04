package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket

class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Geceyi gündüz gibi görünür yapar"
) {
    enum class FbMode { NightVision, TimeForce, Both }

    private val mode        = enum ("Mode",          FbMode.NightVision)
    private val strength    = float("Strength",      1000f, 1f, 1000f)
    private val forceTime   = int  ("Force Time",    6000,  0,  24000)
    private val refreshSec  = int  ("Refresh (s)",   8,     1,  60)
    private val shortcut    = bool ("Shortcut",      false)

    private var loop: Job? = null

    override fun onEnable() {
        super.onEnable()
        loop = scope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && isEnabled) {
                val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
                if (needsNv) {
                    if (EntityTracker.selfUniqueId == 0L && attempts < 20) {
                        delay(500); attempts++; continue
                    }
                    attempts = 0
                    injectNightVision()
                }
                delay(refreshSec.value * 1000L)
            }
        }
    }

    override fun onDisable() {
        loop?.cancel()
        loop = null
        val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
        if (needsNv) removeNightVision()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (event.packet) {
            is StartGamePacket -> {
                val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
                if (needsNv) {
                    scope.launch { delay(200); if (isEnabled) injectNightVision() }
                }
            }
            is SetTimePacket -> {
                val needsTime = mode.value == FbMode.TimeForce || mode.value == FbMode.Both
                if (needsTime && event.direction == PacketEvent.Direction.SERVER_TO_CLIENT)
                    event.cancelAndReplace(SetTimePacket().apply { time = forceTime.value })
            }
            else -> {}
        }
    }

    private fun injectNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.ADD
            effectId        = 16
            amplifier       = (strength.value / 200f).toInt().coerceIn(0, 255)
            isParticles     = false
            duration        = 2_000_000
        })
    }

    private fun removeNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.REMOVE
            effectId        = 16
            amplifier       = 0
            isParticles     = false
            duration        = 0
        })
    }
}
