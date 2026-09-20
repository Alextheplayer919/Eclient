package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket

class FOVChanger : BaseModule(
    name        = "FOVChanger",
    category    = ModuleCategory.VISUAL,
    description = "Sunucuya bildirmeden client FOV'unu genişletir"
) {
    private companion object {
        const val EFFECT_SPEED = 1
    }

    private val amplifier  = int  ("Amplifier",   2, 0,  10)
    private val refreshSec = int  ("Refresh (s)", 8, 1,  60)
    private val shortcut   = bool ("Shortcut",    false)

    private var loop: Job? = null

    override fun onEnable() {
        super.onEnable()
        loop = scope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && isEnabled) {
                if (EntityTracker.selfUniqueId == 0L && attempts < 20) {
                    delay(500); attempts++; continue
                }
                attempts = 0
                injectFov()
                delay(refreshSec.value * 1000L)
            }
        }
    }

    override fun onDisable() {
        loop?.cancel()
        loop = null
        removeFov()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.packet is StartGamePacket) {
            scope.launch { delay(200); if (isEnabled) injectFov() }
        }
    }

    private fun injectFov() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        val amp = amplifier.value.coerceIn(0, 255)
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.ADD
            effectId        = EFFECT_SPEED
            amplifier       = amp
            isParticles     = false
            duration        = 2_000_000
        })
    }

    private fun removeFov() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.REMOVE
            effectId        = EFFECT_SPEED
            amplifier       = 0
            isParticles     = false
            duration        = 0
        })
    }
}
