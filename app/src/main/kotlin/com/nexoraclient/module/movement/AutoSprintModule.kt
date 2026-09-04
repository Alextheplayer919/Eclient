package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * AutoSprintModule ("AutoSprint")
 *
 * Hareket ederken sprinti otomatik baslatir ve surekli acik tutar (aclik
 * dusuklugu, engel, vs. yuzunden sunucunun sprinti durdurmasini beklemeden).
 *
 * NOT (bilincli tasarim tercihi): "hareket ediyor mu" tespiti icin
 * `pkt.motion` (gercek hesaplanmis hiz vektoru, zaten AntiCrystal'in de
 * kopyaladigi mevcut bir alan) kullanildi — bu alan PlayerAuthInputPacket'te
 * HER ZAMAN dolu ve anlamı net. `analogMoveVector` (ileri/yanal analog joystick
 * girdisi) alternatif bir sinyal olabilirdi ama x/y eksenlerinin hangisinin
 * "ileri" oldugu bu projede baska hicbir dosyada dogrulanmadi — yanlis
 * varsayimla modulun sessizce hic tetiklenmemesi riskini almamak icin motion
 * tabanli, daha guvenilir yaklasim tercih edildi.
 */
class AutoSprintModule : BaseModule(
    name        = "AutoSprint",
    category    = ModuleCategory.MOVEMENT,
    description = "Hareket ederken sprinti otomatik ve sürekli açık tutar"
) {

    private val motionThreshold = float("Motion Threshold", 0.05f, 0.01f, 0.3f)
    private val shortcut = bool("Shortcut", false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        if (EntityTracker.selfSprinting) return

        val mx = pkt.motion.x
        val mz = pkt.motion.y // Vector2f: x = dünya X, y = dünya Z (motion 2 boyutlu)
        val speedSq = mx * mx + mz * mz
        val thresholdSq = motionThreshold.value * motionThreshold.value
        if (speedSq < thresholdSq) return // durgun / cok yavas - sprint baslatma

        pkt.inputData.add(PlayerAuthInputData.START_SPRINTING)
        EntityTracker.selfSprinting = true
        event.cancelAndReplace(pkt)
    }
}
