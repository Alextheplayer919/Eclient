package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.sqrt

class FreeLook : BaseModule(
    name        = "FreeLook",
    category    = ModuleCategory.MOVEMENT,
    description = "Sunucuya giden karakter yönünü gerçek bakıştan ayırır"
) {

    private enum class Mode { FollowMovement, LockedAtToggle, Fixed }

    private val mode        = enum ("Mode", Mode.FollowMovement)
    private val fixedYaw    = float("Fixed Yaw", 0f, -180f, 180f)
    private val keepRealPitch = bool("Keep Real Pitch", true)
    private val shortcut     = bool("Shortcut", false)

    @Volatile private var lockedYaw   = 0f
    @Volatile private var lastYaw     = 0f
    @Volatile private var lastX       = 0f
    @Volatile private var lastZ       = 0f
    @Volatile private var initialized = false

    override fun onEnable() {
        super.onEnable()
        lockedYaw   = EntityTracker.selfYaw
        lastYaw     = EntityTracker.selfYaw
        lastX       = EntityTracker.selfX
        lastZ       = EntityTracker.selfZ
        initialized = false
    }

    override fun onDisable() {
        initialized = false
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        // FIX (bkz. TPAura/KillAura koordinasyonu): TPAura açıkken rotasyon
        // önceliği ona ait — FreeLook de aynı kurala uyup devre dışı kalıyor,
        // aksi halde ikisi aynı tick'te farklı yaw göndermeye çalışıp
        // rubber-band yaratır.
        if (ModuleManager.byName("TPAura")?.isEnabled == true) return

        val curX = pkt.position.x
        val curZ = pkt.position.z

        val newYaw: Float = when (mode.value) {
            Mode.LockedAtToggle -> lockedYaw
            Mode.Fixed -> fixedYaw.value
            Mode.FollowMovement -> {
                if (!initialized) {
                    lastX = curX; lastZ = curZ; initialized = true
                    lastYaw
                } else {
                    val dx = curX - lastX
                    val dz = curZ - lastZ
                    val moveDistSq = dx * dx + dz * dz
                    if (moveDistSq > 0.0009f) {
                        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
                        lastYaw = yaw
                        yaw
                    } else {
                        lastYaw
                    }
                }
            }
        }

        lastX = curX; lastZ = curZ

        val realPitch = pkt.rotation.x
        pkt.rotation = Vector3f.from(if (keepRealPitch.value) realPitch else 0f, newYaw, newYaw)
        EntityTracker.selfYaw = newYaw
        if (!keepRealPitch.value) EntityTracker.selfPitch = 0f

        event.cancelAndReplace(pkt)
    }
}
