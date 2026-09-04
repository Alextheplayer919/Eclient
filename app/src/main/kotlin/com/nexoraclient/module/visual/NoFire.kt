package com.rubidiumclient.module.visual

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import java.util.EnumMap

class NoFire : BaseModule(
    name        = "NoFire",
    category    = ModuleCategory.VISUAL,
    description = "Yanma efektini ve ekran overlay'ini gizler"
) {

    private val shortcut = bool("Shortcut", false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
        val pkt = event.packet as? SetEntityDataPacket ?: return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return

        // ON_FIRE ayrı bir metadata key'i değil, FLAGS altında toplu
        // EnumMap<EntityFlag, Boolean> olarak geliyor -- eski kod EnumSet
        // varsaydığı için derleme zamanında tip uyuşmazlığı veriyordu.
        val currentFlags = pkt.metadata.get(EntityDataTypes.FLAGS) ?: return
        if (currentFlags[EntityFlag.ON_FIRE] != true) return

        val newFlags = EnumMap(currentFlags).apply { put(EntityFlag.ON_FIRE, false) }
        pkt.metadata.put(EntityDataTypes.FLAGS, newFlags)

        event.cancelAndReplace(pkt)
    }
}
