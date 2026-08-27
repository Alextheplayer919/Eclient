package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import java.util.concurrent.atomic.AtomicInteger

object InventoryUtil {

    private const val TAG = "InventoryUtil"

    const val OFFHAND_SLOT = 119
    const val HOTBAR_START = 0
    const val HOTBAR_END   = 8
    const val INV_START    = 9
    const val INV_END      = 35

    fun sendEquip(
        session    : RubidiumRelaySession,
        runtimeId  : Long,
        containerId: Int,
        slot       : Int,
        hotbarSlot : Int,
        item       : ItemData
    ) {
        val packet = MobEquipmentPacket().apply {
            this.runtimeEntityId = runtimeId
            this.containerId = containerId
            this.inventorySlot = slot
            this.hotbarSlot = hotbarSlot
            this.item = item
        }
        session.serverBound(packet)
    }

    fun sendOffhandEquip(session: RubidiumRelaySession, fromSlot: Int, itemData: ItemData) {
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.OFFHAND,
            slot        = fromSlot,
            hotbarSlot  = 0,
            item        = itemData
        )
    }

    fun sendOffhandEquip(session: RubidiumRelaySession, fromSlot: Int, netId: Int, definition: ItemDefinition) {
        val item = ItemData.builder()
            .definition(definition)
            .netId(netId)
            .count(1)
            .damage(0)
            .usingNetId(true)
            .build()
        sendOffhandEquip(session, fromSlot, item)
    }

    fun sendHotbarSelect(session: RubidiumRelaySession, slot: Int) {
        // FIX (ASIL KÖK NEDEN — CrystalAura + AnchorAura "yerleştirildi diyor
        // ama hiçbir şey olmuyor"): item HER ZAMAN ItemData.AIR gönderiliyordu.
        // Bu paket sunucuya "artık elimde HİÇBİR ŞEY yok" diyor. Hemen ardından
        // gönderilen ITEM_USE (placement) paketi itemInHand=kristal/anchor/glowstone
        // derken, sunucu bir önceki paketten "eli boş" biliyor — çelişkili state,
        // çoğu sunucu bunu geçersiz sayıp placement'ı SESSİZCE reddediyor (hata
        // fırlatmıyor, hiçbir şey de olmuyor — kodun "başarılı" sanmasının sebebi
        // de bu: sendPlacementUseRaw sadece paket gönderiminin exception atmamasına
        // bakıyor, sunucunun gerçekten kabul edip etmediğine değil).
        // PlacementUtil.prepareItemForUse hem CrystalAura hem AnchorAura hem
        // AutoTrap/BedAura/PistonAura tarafından kullanıldığı için bu TEK
        // fonksiyondaki hata hepsini simetrik şekilde etkiliyordu.
        val actualItem = EntityTracker.getInventoryItem(slot) ?: ItemData.AIR
        session.serverBound(MobEquipmentPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            containerId     = ContainerId.INVENTORY
            inventorySlot   = slot
            hotbarSlot      = slot
            item            = actualItem
        })
    }

    fun isEmpty(item: ItemData?): Boolean {
        if (item == null) return true
        return item.count <= 0
    }

    fun isTotem(item: ItemData?): Boolean {
        if (isEmpty(item)) return false

        val identifier = resolveIdentifier(item!!)

        if (identifier != null) {
            val result = identifier == "minecraft:totem_of_undying"
            return result
        }

        val runtimeId = runCatching { item.definition?.runtimeId }.getOrElse { null } ?: -1
        val totemRuntimeId = resolveTotemRuntimeIdFromCodec()
        val result = totemRuntimeId > 0 && runtimeId == totemRuntimeId
        return result
    }

    enum class ArmorSlotType(val slotIndex: Int) { HELMET(0), CHESTPLATE(1), LEGGINGS(2), BOOTS(3) }

    fun resolveArmorSlotType(item: ItemData?): ArmorSlotType? {
        if (isEmpty(item)) return null
        val identifier = resolveIdentifier(item!!) ?: return null
        return when {
            identifier.endsWith("_helmet") || identifier == "minecraft:turtle_helmet" -> ArmorSlotType.HELMET
            identifier.endsWith("_chestplate") || identifier == "minecraft:elytra" -> ArmorSlotType.CHESTPLATE
            identifier.endsWith("_leggings") -> ArmorSlotType.LEGGINGS
            identifier.endsWith("_boots") -> ArmorSlotType.BOOTS
            else -> null
        }
    }

    private val ARMOR_MATERIAL_TIER = mapOf(
        "leather" to 1, "golden" to 2, "chainmail" to 2,
        "iron" to 3, "diamond" to 4, "netherite" to 5
    )

    fun armorMaterialTier(item: ItemData?): Int {
        if (isEmpty(item)) return -1
        val identifier = resolveIdentifier(item!!) ?: return 0
        val material = identifier.removePrefix("minecraft:").substringBefore("_")
        return ARMOR_MATERIAL_TIER[material] ?: if (identifier == "minecraft:turtle_helmet") 2 else 0
    }

    // FIX (ghost item — yerden gelen item envanterde görünmüyor, üstüne bir
    // şey koyunca görünüyor): bu sayaç 0'dan aşağı sayıyordu (-1,-2,-3...).
    // Gerçek Minecraft client'ı da KENDİ ItemStackRequest'leri için aynı
    // şekilde 0'dan aşağı sayan negatif ID üretiyor. İki taraf da aynı ID
    // aralığını kullanınca, sunucudan dönen ItemStackResponsePacket bazen
    // client'ın kendi bekleyen bir isteğiyle aynı ID'ye denk geliyor —
    // client bizim AutoArmor/AutoTotem işlemimize ait cevabı kendi (örn.
    // yerden item alma sırasında oluşan) isteğinin cevabı sanıp yanlış
    // slot'u reconcile ediyor, item slot'ta görünmez kalıyor (ghost) ta ki
    // yeni bir tam slot güncellemesi (üstüne item koyma) client'ı zorla
    // resenkronize edene kadar. Kendi aralığımızı client'ın normal bir
    // oturumda asla ulaşamayacağı kadar uzak bir negatif bölgeden
    // başlatarak ID çakışmasını engelliyoruz.
    private val stackRequestIdCounter = AtomicInteger(-1_000_000_000)
    fun nextStackRequestId(): Int = stackRequestIdCounter.decrementAndGet()

    fun sendInventoryMove(
        session: RubidiumRelaySession,
        sourceContainer: ContainerSlotType,
        sourceContainerId: Int,
        sourceSlot: Int,
        sourceItem: ItemData,
        destContainer: ContainerSlotType,
        destContainerId: Int,
        destSlot: Int,
        destItem: ItemData
    ) {
        if (!EntityTracker.inventoriesServerAuthoritative) {
            sendLegacyMove(session, sourceContainerId, sourceSlot, sourceItem, destContainerId, destSlot, destItem)
            return
        }
        sendItemStackMove(session, sourceContainer, sourceSlot, sourceItem, destContainer, destSlot, destItem)
    }

    private fun sendItemStackMove(
        session: RubidiumRelaySession,
        sourceContainer: ContainerSlotType,
        sourceSlot: Int,
        sourceItem: ItemData,
        destContainer: ContainerSlotType,
        destSlot: Int,
        destItem: ItemData
    ) {
        val srcSlotData = ItemStackRequestSlotData(
            sourceContainer, sourceSlot, sourceItem.netId, FullContainerName(sourceContainer, null)
        )
        val dstSlotData = ItemStackRequestSlotData(
            destContainer, destSlot, destItem.netId, FullContainerName(destContainer, null)
        )

        val action: ItemStackRequestAction = if (isEmpty(destItem)) {
            PlaceAction(sourceItem.count, srcSlotData, dstSlotData)
        } else {
            SwapAction(srcSlotData, dstSlotData)
        }

        val request = ItemStackRequest(nextStackRequestId(), arrayOf(action), arrayOf())
        session.serverBound(ItemStackRequestPacket().apply { requests.add(request) })
    }

    private fun sendLegacyMove(
        session: RubidiumRelaySession,
        sourceContainerId: Int,
        sourceSlot: Int,
        sourceItem: ItemData,
        destContainerId: Int,
        destSlot: Int,
        destItem: ItemData
    ) {
        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.NORMAL
            actions.add(InventoryActionData(InventorySource.fromContainerWindowId(sourceContainerId), sourceSlot, sourceItem, destItem))
            actions.add(InventoryActionData(InventorySource.fromContainerWindowId(destContainerId), destSlot, destItem, sourceItem))
        }
        session.serverBound(packet)
    }

    // Envanterdeki gereksiz/duplicate item'ları (ör. InventoryHelper'ın attığı
    // düşük tier sword/pickaxe) dünyaya bırakmak için. Server-authoritative
    // envanter (modern Bedrock, >=1.16) gerektirir — legacy (eski) inventory
    // modeli için ayrı bir "drop" paket yolu burada uygulanmadı, çünkü
    // günümüzde neredeyse tüm sunucular server-authoritative.
    fun sendDropItem(session: RubidiumRelaySession, sourceSlot: Int, sourceItem: ItemData) {
        if (!EntityTracker.inventoriesServerAuthoritative) return

        val src = ItemStackRequestSlotData(
            ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceSlot,
            sourceItem.netId,
            FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null)
        )
        val action: ItemStackRequestAction = DropAction(sourceItem.count, src, false)
        val request = ItemStackRequest(nextStackRequestId(), arrayOf(action), arrayOf())
        session.serverBound(ItemStackRequestPacket().apply { requests.add(request) })
    }

    fun sendSlotSwap(
        session: RubidiumRelaySession,
        sourceSlot: Int,
        sourceNetId: Int,
        destContainer: ContainerSlotType,
        destSlot: Int,
        destNetId: Int
    ) {
        val source = ItemStackRequestSlotData(
            ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceSlot,
            sourceNetId,
            FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null)
        )
        val destination = ItemStackRequestSlotData(
            destContainer,
            destSlot,
            destNetId,
            FullContainerName(destContainer, null)
        )
        val request = ItemStackRequest(
            nextStackRequestId(),
            arrayOf<ItemStackRequestAction>(SwapAction(source, destination)),
            arrayOf<String>()
        )
        session.serverBound(ItemStackRequestPacket().apply { requests.add(request) })
    }

    // Artık public: InventoryHelper ve CrystalAura gibi diğer modüller de
    // item.definition?.identifier'ın hashed network ID modunda null dönebildiği
    // durumlarda aynı registry-fallback'e güvenebilsin diye (kod tekrarı ve
    // farklı modüllerde birbirinden bağımsız/eksik fallback'lerin önüne geçer).
    fun resolveIdentifier(item: ItemData): String? {
        val fromDefinition = runCatching { item.definition?.identifier }.getOrElse { null }
        if (!fromDefinition.isNullOrBlank()) return fromDefinition

        val runtimeId = runCatching { item.definition?.runtimeId }.getOrElse { null } ?: return null
        if (runtimeId <= 0) return null

        val session = PacketEventBus.currentSession ?: return null
        val reg = runCatching { session.clientSession.peer.codecHelper.itemDefinitions }.getOrElse { null } ?: return null
        return runCatching { reg.getDefinition(runtimeId)?.identifier }.getOrElse { null }
    }

    private fun resolveTotemRuntimeIdFromCodec(): Int {
        val session = PacketEventBus.currentSession ?: return -1
        val reg = runCatching { session.clientSession.peer.codecHelper.itemDefinitions }.getOrElse { null } ?: return -1
        for (rid in 600..1800) {
            val def = runCatching { reg.getDefinition(rid) }.getOrElse { null } ?: continue
            if (def.identifier == "minecraft:totem_of_undying") return rid
        }
        return -1
    }

    @Deprecated("netId item tipini değil stack'i temsil eder, isTotem() kullan", ReplaceWith("isTotem(item)"))
    fun isTotemNetId(netId: Int): Boolean = false

    private val FOOD_NET_IDS = setOf(
        260, 297, 319, 320, 349, 350, 354, 355,
        357, 360, 363, 364, 365, 366, 367, 420,
        423, 424, 469, 477
    )
    private val POTION_NET_IDS = setOf(373, 438, 441)
    private val WEAPON_NET_IDS = setOf(
        271, 272, 273, 274, 275, 276, 277, 278, 279, 280,
        293, 294, 295, 296, 297, 598, 599, 600, 601, 602
    )

    fun isFoodNetId(netId: Int)   : Boolean = netId in FOOD_NET_IDS
    fun isPotionNetId(netId: Int) : Boolean = netId in POTION_NET_IDS
    fun isWeaponNetId(netId: Int) : Boolean = netId in WEAPON_NET_IDS
}
