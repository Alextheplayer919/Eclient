package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

object PacketUtil {

    fun sendSwing(session: RubidiumRelaySession) {
        session.serverBound(AnimatePacket().apply {
            action          = AnimatePacket.Action.SWING_ARM
            runtimeEntityId = EntityTracker.selfRuntimeId
        })
    }

    fun sendAttack(
        session: RubidiumRelaySession,
        targetRid: Long,
        hotbarSlot: Int = EntityTracker.selfHotbarSlot,
        clickPos: Vector3f? = null
    ) {
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: ItemData.AIR
        val target   = EntityTracker.getById(targetRid)

        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

        val finalClickPos = clickPos ?: if (target != null) {
            val heightOffset = if (target.isPlayer) 1.5f else 0.5f
            Vector3f.from(target.x, target.y + heightOffset, target.z)
        } else {
            playerPos
        }

        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            runtimeEntityId = targetRid
            actionType      = 1
            this.hotbarSlot = hotbarSlot
            itemInHand      = heldItem
            playerPosition  = playerPos
            clickPosition   = finalClickPos
        })
    }

    fun sendSwingAndAttack(session: RubidiumRelaySession, targetRid: Long, hotbarSlot: Int = EntityTracker.selfHotbarSlot) {
        sendSwing(session)
        sendAttack(session, targetRid, hotbarSlot)
    }

    // mirrorToClient: paketi sunucuya EK OLARAK client'a da geri gönderir, yani
    // telefonun kendi render pozisyonunu da günceller. Gerçek bir pozisyon
    // değişimi (TP, ışınlanma) için ŞART — aksi halde sunucu seni yeni yerde
    // görür ama client'ın kendi fizik motoru eski pozisyona geri "düzeltir" ve
    // hareket görsel olarak hiç çalışmıyormuş gibi durur (KillAuraPro TP bug'ı
    // buydu). Crit enjeksiyonu gibi sahte mikro-hareketlerde bunu KAPALI
    // tutmak gerekir — o hareketlerin client'ta görünmesi istenmez.
    fun sendMove(
        session        : RubidiumRelaySession,
        x              : Float,
        y              : Float,
        z              : Float,
        yaw            : Float,
        pitch          : Float,
        onGround       : Boolean = true,
        teleport       : Boolean = false,
        mirrorToClient : Boolean = false
    ) {
        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(x, y, z)
            rotation              = Vector3f.from(pitch, yaw, yaw)
            mode                  = if (teleport) MovePlayerPacket.Mode.TELEPORT
                                    else          MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(movePacket)
        if (mirrorToClient) session.clientBound(movePacket)
    }

    fun sendMoveAtSelf(
        session        : RubidiumRelaySession,
        yaw            : Float   = EntityTracker.selfYaw,
        pitch          : Float   = EntityTracker.selfPitch,
        dyOffset       : Float   = 0f,
        onGround       : Boolean = true,
        teleport       : Boolean = false,
        mirrorToClient : Boolean = false
    ) = sendMove(
        session,
        EntityTracker.selfX,
        EntityTracker.selfY + dyOffset,
        EntityTracker.selfZ,
        yaw, pitch, onGround, teleport, mirrorToClient
    )
}
