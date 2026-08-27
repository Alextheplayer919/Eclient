package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.ModuleManager
import kotlin.math.min

/**
 * "Cool" varyant — kategoriye göre sabit bir taban renk var ama üstüne
 * yavaşça kayan bir hue-shift (rainbow chase) biniyor, üstelik LinearGradient/
 * Shader ALLOCATE ETMEDEN. HSVToColor() tek bir float[3] buffer üzerinde
 * çalışıyor (alan başına ~1 kayan nokta hesaplama, GC'siz).
 *
 * FPS notu: render() her frame çağrıldığı için burada YENİ List/Pair/Shader
 * allocation'ı yapmaktan kaçınıyoruz (GC duraklamaları frame time'a doğrudan
 * yansır). measureText/fontMetrics gibi paint sorguları da her frame yerine
 * sadece gerektiğinde hesaplanıp cache'leniyor. Rainbow efekti bile GC-free:
 * tek bir float[3] hsvBuffer reuse ediliyor, her item için Shader/Gradient
 * OLUŞTURULMUYOR.
 */
class ArrayListModule : BaseModule(
    name = "ArrayList",
    category = ModuleCategory.VISUAL,
    description = "Aktif modülleri sağ üst köşede havalı bir liste halinde gösterir"
) {
    private val activationTimestamps = HashMap<String, Long>()
    private val slideProgress = HashMap<String, Float>()
    private var lastFrameTimeNs = 0L

    // Rainbow chase fazı (derece, 0-360 arası döner). dt bazlı ilerliyor,
    // frame rate'ten bağımsız sabit hızda akıyor.
    private var huePhase = 0f
    private val hsvBuffer = FloatArray(3)

    companion object {
        // Kategoriye göre taban hue (derece) — rainbow chase bunun üstüne
        // biniyor, böylece kategoriler her zaman ayırt edilebilir kalıyor
        // ama statik/donuk durmuyor.
        // NOT: ModuleCategory.SOCIAL derlemede "Unresolved reference" verdi —
        // bu enum sabiti gerçekten yok, o yüzden kaldırıldı.
        private fun baseHueFor(category: ModuleCategory): Float = when (category) {
            ModuleCategory.COMBAT   -> 348f  // kırmızı/pembe
            ModuleCategory.MOVEMENT -> 205f  // camgöbeği/mavi
            ModuleCategory.VISUAL   -> 268f  // mor
            ModuleCategory.MISC     -> 132f  // yeşil
            else                    -> 0f
        }

        private const val HUE_SPEED_DEG_PER_SEC = 55f
        private const val CHASE_SPREAD_DEG = 26f // aynı listede item'lar arası hafif faz farkı
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        color = Color.rgb(0xF4, 0xF4, 0xF6)
        setShadowLayer(3f, 0f, 0f, Color.argb(180, 0, 0, 0))
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val reusableRect = RectF()

    private var cachedVersion = -1
    private var cachedSorted: List<BaseModule> = emptyList()
    private var cachedActiveNames: Set<String> = emptySet()

    // İsim -> genişlik / kategori cache'leri: modül isimleri ve kategorileri
    // çalışma zamanında değişmez, o yüzden measureText/byName lookup'ını
    // her frame tekrar etmek yerine bir kere hesaplayıp saklıyoruz.
    private val textWidthCache = HashMap<String, Float>()
    private val categoryCache = HashMap<String, ModuleCategory>()

    // Frame başına yeni List/Pair oluşturmamak için reuse edilen buffer'lar.
    private val reusableFadingOut = ArrayList<String>()
    private val reusableToRemove = ArrayList<String>()
    private val reusableDrawNames = ArrayList<String>()
    private val reusableDrawCategories = ArrayList<ModuleCategory>()

    // textSize hiç değişmediği için fontMetrics de değişmez — bir kere hesapla.
    private val fm = textPaint.fontMetrics
    private val lineH = (fm.descent - fm.ascent) + 12f

    override fun onEnable() {
        super.onEnable()
        activationTimestamps.clear()
        slideProgress.clear()
        lastFrameTimeNs = 0L
        cachedVersion = -1
        huePhase = 0f
    }

    private fun trackActivations() {
        val now = System.currentTimeMillis()
        for (m in ModuleManager.modules) {
            if (m === this) continue
            if (m.name !in categoryCache) categoryCache[m.name] = m.category
            if (m.isEnabled) {
                activationTimestamps.putIfAbsent(m.name, now)
            } else {
                activationTimestamps.remove(m.name)
            }
        }
    }

    private fun textWidth(name: String): Float =
        textWidthCache.getOrPut(name) { textPaint.measureText(name) }

    /** GC-free hue-shift renk hesaplama: tek reusable float[3] buffer. */
    private fun chaseAccentFor(category: ModuleCategory, index: Int): Int {
        hsvBuffer[0] = (baseHueFor(category) + huePhase + index * CHASE_SPREAD_DEG).mod(360f)
        hsvBuffer[1] = 0.62f
        hsvBuffer[2] = 1f
        return Color.HSVToColor(hsvBuffer)
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        trackActivations()

        val version = ModuleManager.version.value
        if (version != cachedVersion) {
            cachedVersion = version
            val active = ModuleManager.modules.filter { it.isEnabled && it !== this }
            cachedSorted = active.sortedByDescending { activationTimestamps[it.name] ?: 0L }
            cachedActiveNames = cachedSorted.mapTo(HashSet()) { it.name }
        }
        val sorted = cachedSorted
        val activeNames = cachedActiveNames

        val nowNs = System.nanoTime()
        val dt = if (lastFrameTimeNs == 0L) 0.016f else ((nowNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNs = nowNs
        val step = (0.8f * dt * 60f).coerceIn(0f, 1f)

        huePhase = (huePhase + dt * HUE_SPEED_DEG_PER_SEC).mod(360f)

        for (m in sorted) {
            val cur = slideProgress[m.name] ?: 0f
            slideProgress[m.name] = min(1f, cur + step)
        }

        reusableToRemove.clear()
        for (key in slideProgress.keys) {
            if (key !in activeNames) {
                val cur = slideProgress[key] ?: 0f
                val next = (cur - step).coerceAtLeast(0f)
                if (next <= 0f) reusableToRemove.add(key) else slideProgress[key] = next
            }
        }
        for (i in reusableToRemove.indices) slideProgress.remove(reusableToRemove[i])
        if (slideProgress.isEmpty()) return

        reusableFadingOut.clear()
        for (key in slideProgress.keys) {
            if (key !in activeNames) reusableFadingOut.add(key)
        }

        reusableDrawNames.clear()
        reusableDrawCategories.clear()
        for (m in sorted) {
            reusableDrawNames.add(m.name)
            reusableDrawCategories.add(m.category)
        }
        for (i in reusableFadingOut.indices) {
            val n = reusableFadingOut[i]
            val category = categoryCache[n] ?: continue
            reusableDrawNames.add(n)
            reusableDrawCategories.add(category)
        }

        val rightX = screenW - 16f
        bgPaint.color = Color.rgb(0x0D, 0x0D, 0x10) // sabit — sadece alpha item başına değişir

        var y = 16f
        for (i in reusableDrawNames.indices) {
            val name = reusableDrawNames[i]
            val category = reusableDrawCategories[i]
            val progress = slideProgress[name] ?: continue

            val textW = textWidth(name)
            val accentW = 3.5f
            val boxW = textW + 20f + accentW

            val slideOffset = (1f - progress) * (boxW + 24f)
            val boxRight = rightX + slideOffset
            val boxLeft = boxRight - boxW
            val boxTop = y
            val boxBottom = y + lineH - 6f

            val alpha = (255 * progress).toInt().coerceIn(0, 255)
            val accentColor = chaseAccentFor(category, i)

            // Arka plan: koyu cam efekti.
            reusableRect.set(boxLeft, boxTop, boxRight, boxBottom)
            bgPaint.alpha = (150 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(reusableRect, 7f, 7f, bgPaint)

            // İnce neon kenarlık — kutunun tamamını rainbow renkle çerçeveliyor.
            borderPaint.color = accentColor
            borderPaint.alpha = (110 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(reusableRect, 7f, 7f, borderPaint)

            // Sol tarafta kalın, parlak accent şerit (rainbow chase burada).
            reusableRect.set(boxLeft, boxTop, boxLeft + accentW, boxBottom)
            accentPaint.color = accentColor
            accentPaint.alpha = alpha
            canvas.drawRoundRect(reusableRect, 1.8f, 1.8f, accentPaint)

            textPaint.alpha = alpha
            val textY = boxTop + 5f - fm.ascent
            canvas.drawText(name, boxRight - 11f, textY, textPaint)

            y += lineH
        }
    }
}
