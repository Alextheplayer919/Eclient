package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.ItemIconProvider

/**
 * ArmorHudModule ("ArmorHud")
 *
 * Kendi zırhını (kask/göğüslük/pantolon/bot) ve canını ekranın köşesinde
 * gösterir. İkonlar `ItemIconProvider` üzerinden, senin yüklediğin
 * `item_netherite_helmet` / `item_netherite_chestplate` /
 * `item_netherite_leggings` / `item_netherite_boots` gibi drawable
 * kaynaklarından (identifier'daki "minecraft:" öneki otomatik düşürülüyor,
 * ItemIconProvider zaten bunu yapıyor) çekiliyor.
 *
 * Slot kaynağı: `EntityTracker.getArmorSnapshot()` — Bedrock ARMOR
 * konteynerinin sabit slot sırası 0=Helmet, 1=Chestplate, 2=Leggings,
 * 3=Boots (EntityTracker.handleInventoryContent'teki ARMOR case ile aynı
 * sıra, CloudburstMC ContainerId.ARMOR standardı).
 *
 * NOT (bağımlılık uyarısı): `render(canvas, screenW, screenH)` imzası
 * ChunkFinder'daki ile birebir aynı kalıp — o modülün nasıl bir merkezi
 * Canvas/overlay dispatcher'dan çağrıldığını gösteren dosya elimde değildi,
 * bu yüzden ArmorHud'u da AYNI kalıpla yazdım. ChunkFinder'ı çağıran yere
 * (örn. `is ChunkFinder -> it.render(...)` gibi bir yer) `is ArmorHudModule
 * -> it.render(...)` satırını da eklemen gerekecek — o dosya bende olmadığı
 * için bu adımı ben yapamadım.
 */
class ArmorHudModule : BaseModule(
    name        = "ArmorHud",
    category    = ModuleCategory.VISUAL,
    description = "Kendi zırhını ve canını ekranın köşesinde gösterir"
) {

    enum class Corner { BOTTOM_LEFT, TOP_LEFT }

    private val corner     = enum ("Corner", Corner.BOTTOM_LEFT)
    private val iconSize   = int  ("Icon Size", 32, 16, 64)
    private val spacing    = int  ("Spacing", 4, 0, 16)
    private val marginX    = int  ("Margin X", 12, 0, 100)
    private val marginY    = int  ("Margin Y", 12, 0, 100)
    private val showHealth = bool ("Show Health", true)
    private val shortcut = bool("Shortcut", false)

    private val armorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val healthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.LEFT
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val emptySlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(120, 255, 255, 255)
    }

    // Bedrock ARMOR konteyner slot sirasi: 0=Helmet,1=Chestplate,2=Leggings,3=Boots.
    // Ekranda daima bu sirada (yukaridan asagi kask->bot) diziliyor.
    private val slotOrder = listOf(0, 1, 2, 3)

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return

        val size = iconSize.value.toFloat()
        val gap  = spacing.value.toFloat()
        val armor = EntityTracker.getArmorSnapshot()

        val healthRowHeight = if (showHealth.value) size + gap else 0f
        val totalHeight = slotOrder.size * size + (slotOrder.size - 1) * gap + healthRowHeight

        val startX = marginX.value.toFloat()
        val startY = when (corner.value) {
            Corner.TOP_LEFT    -> marginY.value.toFloat()
            Corner.BOTTOM_LEFT -> screenH - marginY.value.toFloat() - totalHeight
        }

        var y = startY
        for (slot in slotOrder) {
            val item = armor[slot]
            val rect = RectF(startX, y, startX + size, y + size)
            val bmp = item?.let { ItemIconProvider.get(InventoryUtil.resolveIdentifier(it)) }
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, rect, armorPaint)
            } else {
                canvas.drawRect(rect, emptySlotPaint)
            }
            y += size + gap
        }

        if (showHealth.value) {
            val hp    = EntityTracker.selfHealth
            val maxHp = EntityTracker.selfMaxHealth
            canvas.drawText(
                "${"%.1f".format(hp)} / ${"%.0f".format(maxHp)}",
                startX, y + size * 0.7f, healthPaint
            )
        }
    }
}
