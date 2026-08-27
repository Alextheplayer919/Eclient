package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.sqrt

class NoSlowdown : BaseModule(
    name        = "NoSlowdown",
    category    = ModuleCategory.MOVEMENT,
    description = "Sprint sırasındaki hız düşüşlerini pozisyon telafisiyle gizler"
) {

    private val expectedSpeed    = float("Expected Speed", 0.28f, 0.1f, 1f)
    private val deficitRatio     = float("Deficit Ratio",  0.8f,  0.1f, 0.99f)
    private val onlySprinting    = bool ("Only While Sprinting", true)
    private val shortcut         = bool ("Shortcut", false)

    @Volatile private var lastX      = 0f
    @Volatile private var lastZ      = 0f
    @Volatile private var initialized = false

    override fun onEnable() {
        super.onEnable()
        lastX = EntityTracker.selfX
        lastZ = EntityTracker.selfZ
        initialized = false
    }

    override fun onDisable() {
        super.onDisable()
        initialized = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val curX = pkt.position.x
        val curZ = pkt.position.z

        if (!initialized) {
            lastX = curX; lastZ = curZ; initialized = true
            return
        }

        val prevX = lastX
        val prevZ = lastZ
        val dx = curX - prevX
        val dz = curZ - prevZ
        val actualSpeed = sqrt(dx * dx + dz * dz)

        lastX = curX; lastZ = curZ

        if (onlySprinting.value) {
            // FIX: EntityTracker.selfSprinting diye bir alan yok (sadece
            // BAŞKA entity'ler için TrackedEntity.isSprinting metadata'dan
            // izleniyor — kendi sprint durumun için hiç alan yok, derlenmezdi).
            // Zaten elimizdeki paket PlayerAuthInputPacket — sprint bilgisi
            // onun kendi inputData bayrağında. NOT: PlayerAuthInputData.SPRINTING
            // ismi bu projede henüz doğrulanmadı (WANT_DOWN/JUMPING doğrulandı,
            // SPRINTING doğrulanmadı) — derlenmezse PlayerAuthInputData.java'yı
            // atıp kesin ismi teyit ettir.
            if (!pkt.inputData.contains(PlayerAuthInputData.SPRINTING)) return
        }
        if (actualSpeed <= 0.001f) return

        val expected = expectedSpeed.value
        val threshold = expected * deficitRatio.value
        if (actualSpeed >= threshold) return

        val scale = expected / actualSpeed
        val newX  = prevX + dx * scale
        val newZ  = prevZ + dz * scale

        pkt.position = Vector3f.from(newX, pkt.position.y, newZ)
        lastX = newX; lastZ = newZ

        event.cancelAndReplace(pkt)
    }
}
