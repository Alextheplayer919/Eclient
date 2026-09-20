package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import java.util.concurrent.ConcurrentHashMap

object PlacementUtil {

    private const val BLOCK_DEF_SCAN_CAP   = 20000
    private const val BLOCK_DEF_MISS_LIMIT = 64

    data class PreparedItem(val slot: Int, val item: ItemData, val revertTo: Int?)

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    fun reset() {
        blockDefCache.clear()
    }

    private val FALLBACK_IDS = mapOf(
        "minecraft:obsidian"    to 49,
        "minecraft:cobblestone" to 4,
        "minecraft:bedrock"     to 7,
        "minecraft:end_crystal" to 198,
        "minecraft:piston"      to 33,
        "minecraft:sticky_piston" to 29,
        "minecraft:lever"       to 69,
        "minecraft:red_bed"     to 26,
        "minecraft:respawn_anchor" to 502,
        "minecraft:glowstone"   to 89
    )

    fun findItemInInventory(identifier: String): Pair<Int, ItemData>? {
        EntityTracker.getHeldItem()?.let { held ->
            if (held.count > 0 && InventoryUtil.resolveIdentifier(held) == identifier) {
                return EntityTracker.selfHotbarSlot to held
            }
        }
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            if (InventoryUtil.resolveIdentifier(item) == identifier) return slot to item
        }
        return null
    }

    fun prepareItemForUse(
        session: RubidiumRelaySession,
        identifier: String,
        noSwitch: Boolean = true,
        debugSink: ((String) -> Unit)? = null
    ): PreparedItem? {
        val (slot, item) = findItemInInventory(identifier) ?: run {
            debugSink?.invoke("prepareItemForUse: '$identifier' hotbarda bulunamadı, envanterden aranıyor")
            return moveFromInventory(session, identifier, noSwitch, debugSink)
        }

        debugSink?.invoke("prepareItemForUse: '$identifier' hotbar slot=$slot netId=${item.netId} count=${item.count} damage=${item.damage} (aktif hotbar=${EntityTracker.selfHotbarSlot})")

        if (slot == EntityTracker.selfHotbarSlot) return PreparedItem(slot, item, null)
        val original = EntityTracker.selfHotbarSlot
        debugSink?.invoke("prepareItemForUse: hotbar değiştiriliyor $original -> $slot")
        InventoryUtil.sendHotbarSelect(session, slot)
        EntityTracker.selfHotbarSlot = slot
        return PreparedItem(slot, item, if (noSwitch) original else null)
    }

    private fun moveFromInventory(
        session: RubidiumRelaySession,
        identifier: String,
        noSwitch: Boolean,
        debugSink: ((String) -> Unit)?
    ): PreparedItem? {
        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0 || InventoryUtil.resolveIdentifier(item) != identifier) continue
            val destSlot = findEmptyHotbarSlot() ?: continue
            val destItem = EntityTracker.getInventoryItem(destSlot) ?: ItemData.AIR
            debugSink?.invoke("prepareItemForUse: envanter slot=$slot -> hotbar slot=$destSlot taşınıyor (item netId=${item.netId})")
            try {
                InventoryUtil.sendInventoryMove(
                    session = session,
                    sourceContainer = ContainerSlotType.HOTBAR_AND_INVENTORY,
                    sourceContainerId = ContainerId.INVENTORY,
                    sourceSlot = slot, sourceItem = item,
                    destContainer = ContainerSlotType.HOTBAR_AND_INVENTORY,
                    destContainerId = ContainerId.INVENTORY,
                    destSlot = destSlot, destItem = destItem
                )
            } catch (e: Exception) {
                debugSink?.invoke("prepareItemForUse: sendInventoryMove exception: ${e.message}")
                continue
            }
            val original = EntityTracker.selfHotbarSlot
            InventoryUtil.sendHotbarSelect(session, destSlot)
            EntityTracker.selfHotbarSlot = destSlot
            return PreparedItem(destSlot, item, if (noSwitch) original else null)
        }
        debugSink?.invoke("prepareItemForUse: '$identifier' ne hotbarda ne envanterde bulunamadı")
        return null
    }

    private fun findEmptyHotbarSlot(): Int? {
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || item.count <= 0) return slot
        }
        return null
    }

    fun revert(session: RubidiumRelaySession, prepared: PreparedItem) {
        prepared.revertTo?.let {
            InventoryUtil.sendHotbarSelect(session, it)
            EntityTracker.selfHotbarSlot = it
        }
    }

    private fun blockDefIdentifierOf(def: BlockDefinition): String = when (def) {
        is SimpleBlockDefinition -> def.identifier
        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name") ?: "?"
        else -> "?(${def::class.simpleName})"
    }

    fun sendPlacementUseRaw(
        session: RubidiumRelaySession,
        prepared: PreparedItem,
        blockPos: Vector3i,
        blockId: String,
        blockFace: Int = 1,
        clickPosition: Vector3f = Vector3f.from(0.5f, 1.0f, 0.5f),
        debugSink: ((String) -> Unit)? = null
    ): Boolean {
        val blockDef = getBlockDefinition(session, blockId, debugSink) ?: run {
            debugSink?.invoke("SEND ABORT: blockDef bulunamadı ($blockId)")
            return false
        }
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val freshItem = EntityTracker.getInventoryItem(prepared.slot) ?: prepared.item
        val dist = MathUtil.dist3(playerPos.x, playerPos.y, playerPos.z, blockPos.x.toFloat(), blockPos.y.toFloat(), blockPos.z.toFloat())

        if (prepared.slot != EntityTracker.selfHotbarSlot) {
            debugSink?.invoke("UYARI: prepared.slot=${prepared.slot} != aktif hotbar=${EntityTracker.selfHotbarSlot} (rejected riski)")
        }
        if (freshItem.count <= 0) {
            debugSink?.invoke("UYARI: gönderilecek item count<=0 (boş item, rejected riski)")
        }

        debugSink?.invoke(
            "SEND place: pos=(${blockPos.x},${blockPos.y},${blockPos.z}) face=$blockFace dist=${"%.2f".format(dist)} " +
            "blockDef(id=${blockDefIdentifierOf(blockDef)}, runtimeId=${blockDef.runtimeId}, type=${blockDef::class.simpleName}) " +
            "hotbarSlot=${prepared.slot} item(id=${InventoryUtil.resolveIdentifier(freshItem)}, netId=${freshItem.netId}, count=${freshItem.count}, dmg=${freshItem.damage}) " +
            "playerPos=(${"%.2f".format(playerPos.x)},${"%.2f".format(playerPos.y)},${"%.2f".format(playerPos.z)})"
        )

        // FIX (KÖK SEBEP): InventoryUtil.sendInventoryMove kendi transaction'ında
        // "actions" listesini dolduruyor ama burada HİÇ doldurulmuyordu. Gerçek
        // Bedrock client'ı her ITEM_USE (blok yerleştirme) transaction'ında elden
        // tüketilen item'ı gösteren bir InventoryActionData gönderir — bu alan
        // boşsa sunucu (özellikle sıkı anti-cheat/doğrulamalı sunucular) transaction'ı
        // sessizce reddediyor: hiçbir hata paketi gelmiyor, AddEntityPacket hiç
        // gelmiyor. baba.txt'deki "REDDEDİLDİ ... AddEntityPacket gelmedi" spam'inin
        // ve rakiplerin kristal aurasının çalışıp bizimkinin çalışmamasının asıl
        // sebebi buydu.
        val toItem = if (freshItem.count > 1)
            freshItem.toBuilder().count(freshItem.count - 1).build()
        else ItemData.AIR

        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                this.blockFace           = blockFace
                hotbarSlot               = prepared.slot
                itemInHand               = freshItem
                playerPosition           = playerPos
                this.clickPosition       = clickPosition
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                actions.add(InventoryActionData(InventorySource.fromContainerWindowId(ContainerId.INVENTORY), prepared.slot, freshItem, toItem))
            })
            true
        } catch (e: Exception) {
            debugSink?.invoke("SEND EXCEPTION: ${e.message}")
            false
        }
    }

    fun sendInteract(
        session: RubidiumRelaySession,
        blockPos: Vector3i,
        blockId: String,
        blockFace: Int = 1,
        clickPosition: Vector3f = Vector3f.from(0.5f, 0.5f, 0.5f)
    ): Boolean {
        val blockDef  = getBlockDefinition(session, blockId) ?: return false
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val heldItem = EntityTracker.getInventoryItem(EntityTracker.selfHotbarSlot) ?: ItemData.AIR
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                this.blockFace           = blockFace
                hotbarSlot               = EntityTracker.selfHotbarSlot
                itemInHand               = ItemData.AIR
                playerPosition           = playerPos
                this.clickPosition       = clickPosition
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                actions.add(InventoryActionData(InventorySource.fromContainerWindowId(ContainerId.INVENTORY), EntityTracker.selfHotbarSlot, heldItem, heldItem))
            })
            true
        } catch (_: Exception) { false }
    }

    fun getBlockDefinition(
        session: RubidiumRelaySession,
        targetId: String,
        debugSink: ((String) -> Unit)? = null
    ): BlockDefinition? {
        val namesToTry = if (targetId.startsWith("minecraft:")) {
            listOf(targetId, targetId.removePrefix("minecraft:"))
        } else {
            listOf("minecraft:$targetId", targetId)
        }

        for (name in namesToTry) {
            blockDefCache[name]?.let {
                debugSink?.invoke("blockDef: cache hit '$name' -> runtimeId=${it.runtimeId}")
                return it
            }
        }

        val blockDefs = session.clientSession.peer.codecHelper.blockDefinitions
        debugSink?.invoke("blockDef: registry type=${blockDefs?.let { it::class.simpleName } ?: "null"} için '$targetId' aranıyor")

        if (blockDefs is Definitions.NbtBlockDefinitionRegistry) {
            for (name in namesToTry) {
                blockDefs.findByName(name)?.let {
                    debugSink?.invoke("blockDef: NBT registry hit '$name' -> runtimeId=${it.runtimeId}")
                    blockDefCache[targetId] = it
                    blockDefCache[name] = it
                    return it
                }
            }
            debugSink?.invoke("blockDef: NBT registry'de '$targetId' bulunamadı")
        } else {
            try {
                if (blockDefs != null) {
                    var i = 0; var misses = 0
                    while (i < BLOCK_DEF_SCAN_CAP && misses < BLOCK_DEF_MISS_LIMIT) {
                        val def = try { blockDefs.getDefinition(i) } catch (_: Exception) { null }
                        if (def == null) { misses++; i++; continue }
                        misses = 0
                        val id = when (def) {
                            is SimpleBlockDefinition -> def.identifier
                            is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                            else -> null
                        }
                        if (id in namesToTry) {
                            debugSink?.invoke("blockDef: index-scan hit i=$i -> runtimeId=${def.runtimeId}")
                            blockDefCache[targetId] = def
                            return def
                        }
                        i++
                    }
                    debugSink?.invoke("blockDef: index-scan '$targetId' bulamadı (taranan=$i, miss=$misses)")
                }
            } catch (e: Exception) {
                debugSink?.invoke("blockDef: index-scan exception: ${e.message}")
            }
        }

        val fallbackKey = namesToTry.firstOrNull { FALLBACK_IDS.containsKey(it) } ?: targetId
        val fallbackId = FALLBACK_IDS[fallbackKey] ?: FALLBACK_IDS[targetId] ?: run {
            debugSink?.invoke("blockDef: FALLBACK yok, '$targetId' için tanım bulunamadı -> null dönüyor")
            return null
        }
        debugSink?.invoke("blockDef: FALLBACK kullanılıyor '$targetId' -> uydurma runtimeId=$fallbackId (GERÇEK REGISTRY'DE BULUNAMADI)")
        val fallback = SimpleBlockDefinition(
            targetId, fallbackId,
            org.cloudburstmc.nbt.NbtMap.builder()
                .putString("name", targetId)
                .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                .build()
        )
        blockDefCache[targetId] = fallback
        return fallback
    }

    fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)

    private val NON_SOLID = setOf(
        "minecraft:air", "minecraft:water", "minecraft:flowing_water",
        "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:void_air", "minecraft:cave_air"
    )

    // (offset'ten hedefe göre komşu konum) -> (o komşunun tıklanacak yüzü).
    // Yerleştirme her zaman VAR OLAN bir bloğun yüzüne tıklanarak yapılır;
    // yeni blok, tıklanan yüzün normali yönünde belirir. AnchorAura/BedAura/
    // CrystalAura zaten bu deseni kullanıyor (altındaki gerçek zemine face=UP
    // ile tıklayıp üstüne yerleştiriyorlar) — bu fonksiyon aynı deseni hedefin
    // 6 komşusu için genelleştirir.
    private val NEIGHBOR_FACES = listOf(
        Triple(0, -1, 0) to 1,
        Triple(0, 1, 0)  to 0,
        Triple(0, 0, -1) to 3,
        Triple(0, 0, 1)  to 2,
        Triple(1, 0, 0)  to 4,
        Triple(-1, 0, 0) to 5
    )

    fun findClickableNeighbor(x: Int, y: Int, z: Int): Triple<Vector3i, String, Int>? {
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            return Triple(Vector3i.from(x, y - 1, z), "minecraft:obsidian", 1)
        }
        for ((offset, face) in NEIGHBOR_FACES) {
            val nx = x + offset.first; val ny = y + offset.second; val nz = z + offset.third
            val id = WorldBlockTracker.getBlockIdentifier(nx, ny, nz) ?: continue
            if (id in NON_SOLID) continue
            return Triple(Vector3i.from(nx, ny, nz), id, face)
        }
        return null
    }
}
