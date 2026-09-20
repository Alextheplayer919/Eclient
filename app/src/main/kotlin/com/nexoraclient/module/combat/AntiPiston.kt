package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.BlockEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

// NOT (doğrulanmadı): piston extend/retract event'inin BlockEventPacket
// üzerinden geldiği CloudburstMC'nin genel konvansiyonu — bu proje içinde
// daha önce kullanılmamış, protokol versiyonunuzda alan adları (eventType/
// eventData) farklıysa derleyici hatası verir, gerçek adları söyle düzeltirim.
class AntiPiston : BaseModule(
    name        = "AntiPiston",
    category    = ModuleCategory.MOVEMENT,
    description = "Yakındaki piston itmelerini iptal eder"
) {
    private val range        = float("Detect Range", 4f, 1f, 8f)
    private val windowMs     = int("Protection Window (ms)", 600, 100, 2000)
    private val cancelMotion = bool("Cancel Motion", true)
    private val cancelMove   = bool("Cancel Absolute Move", true)
    private val shortcut     = bool("Shortcut", false)

    @Volatile private var protectedUntil = 0L

    override fun onEnable() {
        super.onEnable()
        protectedUntil = 0L
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val p = event.packet) {
            is BlockEventPacket -> {
                val pos = p.blockPosition ?: return
                val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
                val dx = pos.x + 0.5f - sx; val dy = pos.y + 0.5f - sy; val dz = pos.z + 0.5f - sz
                if (dx * dx + dy * dy + dz * dz <= range.value * range.value) {
                    protectedUntil = System.currentTimeMillis() + windowMs.value
                }
            }

            is SetEntityMotionPacket -> {
                if (!cancelMotion.value) return
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (System.currentTimeMillis() > protectedUntil) return
                event.cancel()
            }

            is MoveEntityAbsolutePacket -> {
                if (!cancelMove.value) return
                if (p.runtimeEntityId != EntityTracker.selfRuntimeId) return
                if (System.currentTimeMillis() > protectedUntil) return
                event.cancel()
            }

            else -> {}
        }
    }
}
