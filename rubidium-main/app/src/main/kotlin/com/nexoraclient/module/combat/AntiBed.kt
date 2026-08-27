package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.packet.CameraShakePacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

class AntiBed : BaseModule(
    name        = "AntiBed",
    category    = ModuleCategory.COMBAT,
    description = "Yatak patlaması efektlerini/itmesini iptal eder"
) {
    private val cancelParticles = bool("Cancel Particles", true)
    private val cancelShake     = bool("Cancel Camera Shake", true)
    private val cancelMotion    = bool("Cancel Motion", true)
    private val windowMs        = int("Protection Window (ms)", 400, 100, 2000)
    private val shortcut        = bool("Shortcut", false)

    @Volatile private var protectedUntil = 0L

    override fun onEnable() {
        super.onEnable()
        protectedUntil = 0L
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val p = event.packet) {
            is LevelEventPacket -> {
                if (!cancelParticles.value) return
                if (p.type == LevelEvent.PARTICLE_EXPLOSION || p.type == LevelEvent.PARTICLE_BLOCK_EXPLOSION) {
                    protectedUntil = System.currentTimeMillis() + windowMs.value
                    event.cancel()
                }
            }

            is LevelSoundEventPacket -> {
                if (!cancelParticles.value) return
                val name = runCatching { p.sound?.name }.getOrNull() ?: return
                if (name.contains("EXPLODE", ignoreCase = true)) event.cancel()
            }

            is CameraShakePacket -> {
                if (cancelShake.value) event.cancel()
            }

            is SetEntityMotionPacket -> {
                if (!cancelMotion.value) return
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (System.currentTimeMillis() > protectedUntil) return
                event.cancel()
            }

            is MoveEntityAbsolutePacket -> {
                if (!cancelMotion.value) return
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (System.currentTimeMillis() > protectedUntil) return
                event.cancel()
            }

            else -> {}
        }
    }
}
