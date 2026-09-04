package com.rubidiumclient.module.player

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.AttributeData
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket

/**
 * GodModeModule ("GodMode")
 *
 * SADECE hile korumasiz / anti-cheat'siz sunucular (LAN, Aternos vb.) icin
 * dusunulmus, TAMAMEN CLIENT-SIDE bir illuzyon. Sunucunun kendi ic can/olum
 * takibini degistirmez (bu bir relay/proxy oldugu icin bu mumkun de degil) -
 * yaptigi tek sey, saglik dusuren paketleri client'a ULASMADAN once
 * "maximum can" olarak yeniden yazmak. Boylece ekranda can hic azalmiyormus,
 * "oldun" ekrani hic gelmiyormus gibi gorunur. Korumali/anti-cheat'li gercek
 * bir sunucuda sunucu zaten kendi tarafinda olumu isliyor olacagindan bu bir
 * ise yaramaz - bu yuzden aciklamada net belirtiyoruz.
 *
 * AttributeData gercek constructor'i (kullanicidan alinan kaynakla dogrulandi):
 *   AttributeData(name, minimum, maximum, value, defaultMinimum,
 *                 defaultMaximum, defaultValue, modifiers)
 * Bu yuzden artik paketi TAMAMEN iptal etmek yerine (onceki versiyon, diger
 * attribute'lari da bloke ediyordu), sadece minecraft:health girdisini
 * value=maximum olacak sekilde YENIDEN YAZIYORUZ; paketteki hunger/speed
 * gibi diger attribute'lar olduğu gibi client'a geçiyor.
 */
class GodModeModule : BaseModule(
    name        = "GodMode",
    category    = ModuleCategory.PLAYER,
    description = "Sadece LAN / hile korumasiz sunucularda calisir — sunucunun kendi can takibini degistirmez, sadece ekrandaki hasari/olumu gizler"
) {

    private val hideDamage = bool("HideDamage", true)
    private val hideHurtAnim = bool("HideHurtAnimation", true)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isServerToClient) return

        when (val pkt = event.packet) {
            is UpdateAttributesPacket -> {
                if (!hideDamage.value) return
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return

                val healthAttr = pkt.attributes.firstOrNull { it.name == "minecraft:health" } ?: return
                if (healthAttr.value >= healthAttr.maximum) return // zaten tam can, dokunma

                // Sadece health girdisini maximum'a zorla, geri kalanini (hunger,
                // speed vb.) oldugu gibi birak.
                pkt.attributes = pkt.attributes.map { attr ->
                    if (attr.name == "minecraft:health") {
                        AttributeData(
                            attr.name,
                            attr.minimum,
                            attr.maximum,
                            attr.maximum, // value = maximum -> HUD hep dolu gorunur
                            attr.defaultMinimum,
                            attr.defaultMaximum,
                            attr.defaultValue,
                            attr.modifiers
                        )
                    } else attr
                }
            }
            is EntityEventPacket -> {
                if (!hideHurtAnim.value) return
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val typeName = pkt.type?.toString() ?: return
                if (typeName.contains("HURT") || typeName.contains("DEATH")) event.cancel()
            }
            else -> {}
        }
    }
}
