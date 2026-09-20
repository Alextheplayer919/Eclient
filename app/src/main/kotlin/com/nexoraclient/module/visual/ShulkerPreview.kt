package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket

// NOT (uygulama notu): PlaceAction/SwapAction/DropAction'ın import yolunu
// TakeAction ile aynı paket (itemstack.request.action) olduğunu varsayarak
// yazdım - InventoryUtil.kt'de bu sınıflar kullanılıyor ama import satırları
// görünmüyordu, sadece TakeAction'ın gerçek yolunu (x.kt'den) doğrulayabildim.
// Diğerleri farklı bir alt pakette ise derleme hatası verir, o zaman import
// satırlarını (InventoryUtil.kt'nin tepesini) paylaşırsan tek satırda
// düzeltirim.
//
// Ayrıca "Items" NBT listesindeki alan adları (Slot/Count/Name) genel
// Bedrock container formatına göre varsayıldı - eşleşmezse panel boş açılır,
// crash olmaz.
class ShulkerPreview : BaseModule(
    name        = "ShulkerPreview",
    category    = ModuleCategory.VISUAL,
    description = "Envanterde veya bir sandıkta shulker box'a tıklandığında içeriğini açmadan gösterir"
), PacketEventBus.PacketListener {

    enum class PanelPosition { TopLeft, TopRight, BottomLeft, BottomRight, Center }

    private val position         = enum ("Panel Position",   PanelPosition.TopRight)
    private val fontSize         = float("Font Size",        22f,  14f, 34f)
    private val panelAlpha       = int  ("Panel Alpha",      170,  40,  255)
    private val showEmptyCount   = bool ("Show Empty Slots", true)
    private val showTotalItems   = bool ("Show Total Count", true)
    private val margin           = float("Screen Margin",    24f,  4f,  80f)
    private val displayDurationMs = int ("Display Duration (ms)", 5000, 1000, 20000)

    data class PreviewEntry(val name: String, val count: Int, val slot: Int)

    @Volatile private var peekItem: ItemData? = null
    @Volatile private var peekEntries: List<PreviewEntry> = emptyList()
    @Volatile private var peekShownAtMs: Long = 0L

    companion object {
        private const val SHULKER_SUFFIX = "_shulker_box"
        private const val PLAIN_SHULKER  = "minecraft:shulker_box"
        private const val TOTAL_SLOTS    = 27
    }

    override fun onEnable() {
        super.onEnable()
        peekItem = null
        peekEntries = emptyList()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        peekItem = null
        super.onDisable()
    }

    // FIX (asıl özellik): elde tutmayla ilgisi yok - envanterde veya açık bir
    // sandıkta shulker box'a TIKLANDIĞINDA (bu tıklamanın ürettiği
    // ItemStackRequestPacket paketi yakalanarak) tetiklenir. Normal tıklama
    // davranışı hiç engellenmiyor/iptal edilmiyor - sadece paralelde bir
    // önizleme paneli açılıyor.
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? ItemStackRequestPacket ?: return

        for (req in pkt.requests) {
            for (action in req.actions) {
                for (slot in extractSlots(action)) {
                    val item = EntityTracker.getInventoryItem(slot) ?: continue
                    if (!isShulker(item)) continue
                    val entries = parseContents(item)
                    peekItem = item
                    peekEntries = entries
                    peekShownAtMs = System.currentTimeMillis()
                    return // aynı pakette birden fazla eşleşme olsa da ilkini göster
                }
            }
        }
    }

    private fun extractSlots(action: ItemStackRequestAction): List<Int> = when (action) {
        is TakeAction  -> listOf(action.source.slot, action.destination.slot)
        is PlaceAction -> listOf(action.source.slot, action.destination.slot)
        is SwapAction  -> listOf(action.source.slot, action.destination.slot)
        is DropAction  -> listOf(action.source.slot)
        else -> emptyList()
    }

    private fun isShulker(item: ItemData): Boolean {
        val id = InventoryUtil.resolveIdentifier(item) ?: return false
        return id == PLAIN_SHULKER || id.endsWith(SHULKER_SUFFIX)
    }

    private fun parseContents(item: ItemData): List<PreviewEntry> {
        val tag: NbtMap = item.tag ?: return emptyList()
        val itemsList = try {
            tag.getList("Items", NbtType.COMPOUND)
        } catch (_: Exception) { null } ?: return emptyList()

        val result = ArrayList<PreviewEntry>(itemsList.size)
        for (raw in itemsList) {
            val compound = raw as? NbtMap ?: continue
            val identifier = compound.getString("Name", "").ifBlank {
                compound.getString("id", "")
            }
            if (identifier.isBlank() || identifier == "minecraft:air") continue
            val count = compound.getByte("Count", 0).toInt().let { if (it <= 0) 1 else it }
            val slot = compound.getByte("Slot", (-1).toByte()).toInt()
            result.add(PreviewEntry(prettify(identifier), count, slot))
        }
        return result.sortedBy { it.slot }
    }

    private fun prettify(identifier: String): String {
        val stripped = identifier.removePrefix("minecraft:")
        return stripped.split("_").joinToString(" ") { part ->
            part.replaceFirstChar { c -> c.uppercase() }
        }
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        val item = peekItem ?: return
        val elapsed = System.currentTimeMillis() - peekShownAtMs
        if (elapsed > displayDurationMs.value) {
            peekItem = null
            return
        }

        // Son 400ms'de yumuşak fade-out.
        val fadeMs = 400L
        val remaining = displayDurationMs.value - elapsed
        val alphaScale = if (remaining < fadeMs) (remaining.toFloat() / fadeMs).coerceIn(0f, 1f) else 1f

        drawPanel(canvas, screenW, screenH, peekEntries, alphaScale)
    }

    private fun drawPanel(canvas: Canvas, screenW: Int, screenH: Int, entries: List<PreviewEntry>, alphaScale: Float) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = fontSize.value + 4f
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        }
        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = 0xFFE0E0E0.toInt()
            textSize  = fontSize.value
            textAlign = Paint.Align.LEFT
            alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        }
        val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = 0xFFA0A0A0.toInt()
            textSize  = fontSize.value - 3f
            textAlign = Paint.Align.LEFT
            alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            alpha = (panelAlpha.value * alphaScale).toInt().coerceIn(0, 255)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 2f
            color       = 0xFF8B5CF6.toInt() // shulker mor tonu
            alpha       = (panelAlpha.value * alphaScale).toInt().coerceIn(0, 255)
        }

        val lineH = fontSize.value + 8f
        val totalItems = entries.sumOf { it.count }
        val emptySlots = (TOTAL_SLOTS - entries.size).coerceAtLeast(0)

        var lineCount = 1 // başlık
        if (showTotalItems.value) lineCount++
        lineCount += if (entries.isEmpty()) 1 else entries.size
        if (showEmptyCount.value) lineCount++

        val panelW = 260f
        val panelH = 20f + lineCount * lineH

        val (left, top) = panelOrigin(screenW, screenH, panelW, panelH)

        canvas.drawRoundRect(left, top, left + panelW, top + panelH, 10f, 10f, bgPaint)
        canvas.drawRoundRect(left, top, left + panelW, top + panelH, 10f, 10f, borderPaint)

        var cursorY = top + 26f
        val textX = left + 14f
        canvas.drawText("Shulker Önizleme", textX, cursorY, titlePaint)
        cursorY += lineH

        if (showTotalItems.value) {
            canvas.drawText("$totalItems eşya / ${entries.size} slot dolu", textX, cursorY, dimPaint)
            cursorY += lineH
        }

        if (entries.isEmpty()) {
            canvas.drawText("(boş)", textX, cursorY, dimPaint)
            cursorY += lineH
        } else {
            for (entry in entries) {
                val label = if (entry.count > 1) "${entry.name} x${entry.count}" else entry.name
                canvas.drawText(label, textX, cursorY, rowPaint)
                cursorY += lineH
            }
        }

        if (showEmptyCount.value && emptySlots > 0) {
            canvas.drawText("$emptySlots boş slot", textX, cursorY, dimPaint)
        }
    }

    private fun panelOrigin(screenW: Int, screenH: Int, panelW: Float, panelH: Float): Pair<Float, Float> {
        val m = margin.value
        return when (position.value) {
            PanelPosition.TopLeft     -> m to m
            PanelPosition.TopRight    -> (screenW - panelW - m) to m
            PanelPosition.BottomLeft  -> m to (screenH - panelH - m)
            PanelPosition.BottomRight -> (screenW - panelW - m) to (screenH - panelH - m)
            PanelPosition.Center      -> ((screenW - panelW) / 2f) to ((screenH - panelH) / 2f)
        }
    }
}
