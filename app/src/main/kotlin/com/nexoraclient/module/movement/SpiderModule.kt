package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

/**
 * SpiderModule ("Spider")
 *
 * Duvara carpinca (HORIZONTAL_COLLISION) orumcek gibi yukari tirmanir.
 *
 * Referans dosyadan farklar (guclendirme / bug fix):
 *  - KRITIK BUG FIX: Referans hiz paketini SADECE `session.clientBound(...)`
 *    ile gonderiyordu. Bu SADECE kendi ekranini gunceller — SUNUCU tirmanmayi
 *    HIC OGRENMEZ, yani ekstra irtifa sadece kendi ekraninda kalip bir sonraki
 *    gercek pozisyon paketinde sunucu seni eski yere geri ceker (gorunmez
 *    rubber-band, tirmanma hicbir zaman gercek etki yaratmazdi). Artik hem
 *    `serverBound` hem `clientBound` gonderiliyor.
 *  - "Sadece cömelirken calissin" secenegi eklendi (bazi sunucularda cikis
 *    tirmanmasi sadece sneak+duvar kombinasyonuyla dogal gorunur).
 */
class SpiderModule : BaseModule(
    name        = "Spider",
    category    = ModuleCategory.MOVEMENT,
    description = "Duvara çarpınca örümcek gibi yukarı tırmanır"
) {

    private val climbSpeed       = float("Climb Speed", 0.5f, 0.1f, 2f)
    private val onlyWhenSneaking = bool ("Only When Sneaking", false)
    private val shortcut = bool("Shortcut", false)

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
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        if (!pkt.inputData.contains(PlayerAuthInputData.HORIZONTAL_COLLISION)) return
        if (onlyWhenSneaking.value && !pkt.inputData.contains(PlayerAuthInputData.SNEAKING)) return

        val session = event.session
        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(0f, climbSpeed.value, 0f)
        }
        session.serverBound(motionPacket)
        session.clientBound(motionPacket)
    }
}
