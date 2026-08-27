package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

class ShulkerDupe : BaseModule(
    name = "ShulkerDupe",
    category = ModuleCategory.MISC,
    description = "Shulker + Boya crafting dupe — birden fazla method"
) {

    enum class Method {
        BURST_TAKE,      // Hızlı ardışık Take paketi (race window)
        PLACE_RETAKE,    // Place->Take->Place->Take döngüsü (grid desync)
        SWAP_LOOP,       // Output<->Inventory Swap döngüsü
        SPLIT_TAKE,      // Output'u parça parça al (count split)
        DOUBLE_REQUEST   // Aynı request'te iki kez Take
    }

    private val method      = enum("Method",      Method.BURST_TAKE)
    private val burstCount  = int("BurstCount",   3,   1, 20)
    private val delayMs     = int("DelayMs",       40,  5, 300)
    private val dropOutput  = bool("DropOutput",   true)
    private val autoDisable = bool("AutoDisable",  true)

    private var tickJob: Job? = null
    @Volatile private var lastResponseOk = false

    companion object {
        private val SHULKER_IDENTIFIERS = setOf(
            "minecraft:white_shulker_box",      "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",    "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",     "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",       "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box", "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",     "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",      "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",        "minecraft:black_shulker_box",
            "minecraft:shulker_box",            "minecraft:undyed_shulker_box"
        )

        private val DYE_IDENTIFIERS = setOf(
            "minecraft:white_dye",      "minecraft:orange_dye",
            "minecraft:magenta_dye",    "minecraft:light_blue_dye",
            "minecraft:yellow_dye",     "minecraft:lime_dye",
            "minecraft:pink_dye",       "minecraft:gray_dye",
            "minecraft:light_gray_dye", "minecraft:cyan_dye",
            "minecraft:purple_dye",     "minecraft:blue_dye",
            "minecraft:brown_dye",      "minecraft:green_dye",
            "minecraft:red_dye",        "minecraft:black_dye"
        )

        private const val GRID_SLOT_SHULKER = 0
        private const val GRID_SLOT_DYE     = 1
    }

    override fun onEnable() {
        super.onEnable()
        PacketEventBus.register(this)
        tickJob = scope.launch { runDupe() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet as? ItemStackResponsePacket ?: return
        for (r in pkt.entries) {
            if (r.result == ItemStackResponseStatus.OK) lastResponseOk = true
        }
    }

    private suspend fun runDupe() {
        while (currentCoroutineContext().isActive) {
            if (!isEnabled) break

            val session = PacketEventBus.currentSession
            if (session == null) { delay(500); continue }

            val shulkerSlot = findSlot(SHULKER_IDENTIFIERS)
            val dyeSlot     = findSlot(DYE_IDENTIFIERS)

            if (shulkerSlot == null || dyeSlot == null) {
                sendChat(session, "§c[ShulkerDupe] Envanterde shulker veya boya bulunamadı")
                setEnabled(false)
                break
            }

            val shulkerItem = EntityTracker.getInventoryItem(shulkerSlot) ?: run {
                delay(200); return@run null
            } ?: continue
            val dyeItem = EntityTracker.getInventoryItem(dyeSlot) ?: run {
                delay(200); return@run null
            } ?: continue

            lastResponseOk = false

            placeToGrid(session, shulkerSlot, shulkerItem, dyeSlot, dyeItem)
            delay(delayMs.value.toLong())

            val outputSlot = slotData(ContainerSlotType.CRAFTING_OUTPUT, 0, 0)
            val destSlot   = slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId)

            when (method.value) {
                Method.BURST_TAKE     -> methodBurstTake(session, outputSlot, destSlot, shulkerSlot, shulkerItem)
                Method.PLACE_RETAKE   -> methodPlaceRetake(session, outputSlot, destSlot, shulkerSlot, dyeSlot, shulkerItem, dyeItem)
                Method.SWAP_LOOP      -> methodSwapLoop(session, outputSlot, destSlot)
                Method.SPLIT_TAKE     -> methodSplitTake(session, outputSlot, destSlot, shulkerSlot, shulkerItem)
                Method.DOUBLE_REQUEST -> methodDoubleRequest(session, outputSlot, destSlot, shulkerSlot, shulkerItem)
            }

            delay(delayMs.value.toLong())

            if (autoDisable.value) {
                sendChat(session, "§a[ShulkerDupe] Sequence tamamlandı (method=${method.value})")
                setEnabled(false)
                break
            }

            delay(delayMs.value.toLong())
        }
    }

    // ── PLACE ────────────────────────────────────────────────────────────────

    private fun placeToGrid(
        session: RubidiumRelaySession,
        shulkerSlot: Int, shulkerItem: ItemData,
        dyeSlot: Int,     dyeItem: ItemData
    ) {
        val actions = mutableListOf<ItemStackRequestAction>()
        actions.add(PlaceAction(1,
            slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
            slotData(ContainerSlotType.CRAFTING_INPUT, GRID_SLOT_SHULKER, 0)
        ))
        actions.add(PlaceAction(1,
            slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, dyeSlot, dyeItem.netId),
            slotData(ContainerSlotType.CRAFTING_INPUT, GRID_SLOT_DYE, 0)
        ))
        sendRequest(session, actions)
    }

    // ── METHOD 1: BURST_TAKE ─────────────────────────────────────────────────
    // Aynı output'a burstCount kez ardışık TakeAction gönder.
    // Sunucu her Take'i sıralı işlediğinde grid state'ini aynı anda güncelleyemezse
    // her Take için yeni bir output üretir.

    private suspend fun methodBurstTake(
        session: RubidiumRelaySession,
        outputSlot: ItemStackRequestSlotData,
        destSlot: ItemStackRequestSlotData,
        shulkerSlot: Int,
        shulkerItem: ItemData
    ) {
        repeat(burstCount.value) {
            val actions = mutableListOf<ItemStackRequestAction>()
            actions.add(TakeAction(64, outputSlot, destSlot))
            if (dropOutput.value) {
                actions.add(DropAction(64,
                    slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
                    false
                ))
            }
            sendRequest(session, actions)
            delay(5)
        }
    }

    // ── METHOD 2: PLACE_RETAKE ───────────────────────────────────────────────
    // Take et -> grid'e geri koy -> tekrar Take et döngüsü.
    // Grid desync: sunucu grid'i hâlâ dolu sanıp output'u yeniden hesaplar.

    private suspend fun methodPlaceRetake(
        session: RubidiumRelaySession,
        outputSlot: ItemStackRequestSlotData,
        destSlot: ItemStackRequestSlotData,
        shulkerSlot: Int, dyeSlot: Int,
        shulkerItem: ItemData, dyeItem: ItemData
    ) {
        repeat(burstCount.value) {
            val take = mutableListOf<ItemStackRequestAction>()
            take.add(TakeAction(64, outputSlot, destSlot))
            if (dropOutput.value) {
                take.add(DropAction(64,
                    slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
                    false
                ))
            }
            sendRequest(session, take)
            delay(delayMs.value.toLong())

            // Grid'e geri koy
            val replace = mutableListOf<ItemStackRequestAction>()
            replace.add(PlaceAction(1,
                slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
                slotData(ContainerSlotType.CRAFTING_INPUT, GRID_SLOT_SHULKER, 0)
            ))
            replace.add(PlaceAction(1,
                slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, dyeSlot, dyeItem.netId),
                slotData(ContainerSlotType.CRAFTING_INPUT, GRID_SLOT_DYE, 0)
            ))
            sendRequest(session, replace)
            delay(delayMs.value.toLong())
        }
    }

    // ── METHOD 3: SWAP_LOOP ──────────────────────────────────────────────────
    // Output slotunu inventory ile Swap et — sunucu swap sırasında
    // output'u "teslim edildi" saymadan yeniden üretebilir.

    private suspend fun methodSwapLoop(
        session: RubidiumRelaySession,
        outputSlot: ItemStackRequestSlotData,
        destSlot: ItemStackRequestSlotData
    ) {
        repeat(burstCount.value) {
            val actions = mutableListOf<ItemStackRequestAction>()
            actions.add(SwapAction(outputSlot, destSlot))
            sendRequest(session, actions)
            delay(delayMs.value.toLong())

            if (dropOutput.value) {
                val drop = mutableListOf<ItemStackRequestAction>()
                drop.add(DropAction(64, destSlot, false))
                sendRequest(session, drop)
                delay(10)
            }
        }
    }

    // ── METHOD 4: SPLIT_TAKE ─────────────────────────────────────────────────
    // 64'ü tek seferde değil parça parça al (32+32, 16+16+16+16 gibi).
    // Bazı sunucularda her partial take ayrı bir crafting transaction sayılır.

    private suspend fun methodSplitTake(
        session: RubidiumRelaySession,
        outputSlot: ItemStackRequestSlotData,
        destSlot: ItemStackRequestSlotData,
        shulkerSlot: Int,
        shulkerItem: ItemData
    ) {
        val splitSize = (64 / burstCount.value.coerceAtLeast(1)).coerceAtLeast(1)
        repeat(burstCount.value) {
            val actions = mutableListOf<ItemStackRequestAction>()
            actions.add(TakeAction(splitSize, outputSlot, destSlot))
            if (dropOutput.value) {
                actions.add(DropAction(splitSize,
                    slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
                    false
                ))
            }
            sendRequest(session, actions)
            delay(5)
        }
    }

    // ── METHOD 5: DOUBLE_REQUEST ─────────────────────────────────────────────
    // Tek bir ItemStackRequestPacket içine birden fazla request koy.
    // Sunucu bunları sıralı işlerse ilki output'u tüketir, ikincisi
    // hâlâ geçerli grid'den yeni output alabilir.

    private fun methodDoubleRequest(
        session: RubidiumRelaySession,
        outputSlot: ItemStackRequestSlotData,
        destSlot: ItemStackRequestSlotData,
        shulkerSlot: Int,
        shulkerItem: ItemData
    ) {
        val pkt = ItemStackRequestPacket()
        repeat(burstCount.value) {
            val actions = mutableListOf<ItemStackRequestAction>()
            actions.add(TakeAction(64, outputSlot, destSlot))
            if (dropOutput.value) {
                actions.add(DropAction(64,
                    slotData(ContainerSlotType.HOTBAR_AND_INVENTORY, shulkerSlot, shulkerItem.netId),
                    false
                ))
            }
            pkt.requests.add(
                ItemStackRequest(
                    InventoryUtil.nextStackRequestId(),
                    actions.toTypedArray(),
                    emptyArray()
                )
            )
        }
        session.sendToServer(pkt)
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun findSlot(identifiers: Set<String>): Int? {
        for (slot in 0..35) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            val id   = InventoryUtil.resolveIdentifier(item) ?: continue
            if (id in identifiers) return slot
        }
        return null
    }

    private fun slotData(container: ContainerSlotType, slot: Int, netId: Int) =
        ItemStackRequestSlotData(container, slot, netId, FullContainerName(container, null))

    private fun sendRequest(session: RubidiumRelaySession, actions: List<ItemStackRequestAction>) {
        val req = ItemStackRequest(
            InventoryUtil.nextStackRequestId(),
            actions.toTypedArray(),
            emptyArray()
        )
        session.sendToServer(ItemStackRequestPacket().apply { requests.add(req) })
    }

    private fun sendChat(session: RubidiumRelaySession, message: String) {
        try {
            session.sendToClient(TextPacket().apply {
                type               = TextPacket.Type.RAW
                isNeedsTranslation = false
                sourceName         = ""
                xuid               = ""
                platformChatId     = ""
                setMessage(message)
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }
}
