package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.GameFov
import com.rubidiumclient.utils.MathUtil
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class TargetESP : BaseModule(
    name        = "TargetESP",
    category    = ModuleCategory.VISUAL,
    description = "Rakip oyunculara tracer + saran kutu (box) ile ESP gösterir"
) {
    enum class RenderMode { Tracer, Box, Both }
    enum class BoxStyle   { Flat2D, Wireframe3D }
    enum class ColorMode  { Fixed, Health, Team }
    enum class SortMode   { Distance, Health }

    private val renderMode     = enum ("Render Mode",   RenderMode.Both)
    private val boxStyle       = enum ("Box Style",     BoxStyle.Flat2D)
    private val colorMode      = enum ("Color Mode",    ColorMode.Team)
    private val sortMode       = enum ("Sort Mode",     SortMode.Distance)
    private val range          = float("Range",         64f,  8f,  256f)
    private val autoFovSync    = bool ("Auto FOV Sync", true)
    private val fov            = float("Manual FOV",    110f, 30f, 130f)
    private val maxDisplay     = int  ("Max Display",   60,   1,   200)
    private val updateRateMs   = int  ("Update Rate (ms)", 100, 30, 500)
    private val tracerWidth    = float("Tracer Width",  2f,   0.5f, 8f)
    private val boxThickness   = float("Box Thickness", 2f,   0.5f, 6f)
    private val boxAlpha       = int  ("Box Fill Alpha", 45,  0,   200)
    private val friendSkip     = bool ("Friend Skip",    true)
    private val skipInvisible  = bool ("Skip Invisible", true)
    private val showLabels     = bool ("Show Names",    true)
    private val showDistance   = bool ("Show Distance", true)
    private val showHealth     = bool ("Show Health",   true)
    private val healthAsBar    = bool ("Health As Bar",  true)
    private val fadeByDistance = bool ("Distance Fade",  true)
    private val minAlpha       = int  ("Min Alpha",      50,  0,   255)
    private val showRadar      = bool ("Off-Screen Radar", true)
    private val radarMargin    = float("Radar Margin",  36f,  10f, 100f)
    private val smoothBox      = bool ("Smooth Box",     true)
    private val smoothFactor   = float("Smooth Factor",  0.35f, 0.05f, 1f)
    private val nearestGlow    = bool ("Highlight Nearest", true)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var updateJob: Job? = null

    private val smoothedScreenPos = ConcurrentHashMap<Long, FloatArray>()

    data class RenderEntry(
        val feetX: Float, val feetY: Float, val feetZ: Float,
        val centerX: Float, val centerY: Float, val centerZ: Float,
        val width: Float, val height: Float,
        val name: String,
        val health: Float, val maxHealth: Float,
        val isFriend: Boolean,
        val distance: Float,
        val key: Long
    )

    companion object {
        // FIX (isabetli hitbox): oyuncu genişliği Bedrock'ta ~0.6 blok, çömelme
        // (sneak) yüksekliği ~1.5, normal ~1.8 - ESP.kt/Xray.kt'deki sabit
        // half-cube (blok) mantığı burada işe yaramaz, oyuncu AABB'si feet
        // Y'den yukarı doğru asimetrik.
        private const val PLAYER_HALF_WIDTH  = 0.32f
        private const val PLAYER_HEIGHT      = 1.8f
        private const val PLAYER_SNEAK_HEIGHT = 1.5f
    }

    override fun onEnable() {
        super.onEnable()
        smoothedScreenPos.clear()
        updateJob = launchTickLoop(updateRateMs.value.toLong()) { updateTick() }
    }

    override fun onDisable() {
        updateJob?.cancel()
        super.onDisable()
        renderList.clear()
        smoothedScreenPos.clear()
    }

    private fun updateTick() {
        rebuildRenderList()
    }

    private fun rebuildRenderList() {
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val r  = range.value

        val entries = EntityTracker.getPlayers(r)
            .filter { !skipInvisible.value || !it.isInvisible }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .map { e ->
                val height = if (e.isSneaking) PLAYER_SNEAK_HEIGHT else PLAYER_HEIGHT
                val name = e.name.ifBlank { "Player" }
                RenderEntry(
                    feetX = e.x, feetY = e.y, feetZ = e.z,
                    centerX = e.x, centerY = e.y + height / 2f, centerZ = e.z,
                    width = PLAYER_HALF_WIDTH * 2f, height = height,
                    name = name,
                    health = e.health, maxHealth = if (e.maxHealth > 0f) e.maxHealth else 20f,
                    isFriend = e.isFriendEntity,
                    distance = MathUtil.dist3(e.x, e.y + height / 2f, e.z, cx, cy, cz),
                    key = e.runtimeId
                )
            }

        val sorted = when (sortMode.value) {
            SortMode.Distance -> entries.sortedBy { it.distance }
            SortMode.Health   -> entries.sortedBy { it.health }
        }

        val trimmed = sorted.take(maxDisplay.value)
        renderList.clear()
        renderList.addAll(trimmed)

        val activeKeys = trimmed.mapTo(HashSet()) { it.key }
        smoothedScreenPos.keys.retainAll(activeKeys)
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val yaw   = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch

        val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeCap   = Paint.Cap.ROUND
            strokeWidth = tracerWidth.value
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = 26f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val centerX = screenW / 2f
        val centerY = screenH / 2f
        val maxRange = range.value
        val nearest = if (nearestGlow.value) renderList.minByOrNull { it.distance } else null
        val effectiveFov = if (autoFovSync.value) GameFov.current else fov.value

        for (entry in renderList) {
            val color = colorFor(entry)

            val rawCenter = MathUtil.worldToScreen(
                entry.centerX, entry.centerY, entry.centerZ,
                cx, cy, cz, yaw, pitch, screenW, screenH, effectiveFov
            )

            val alphaScale = if (fadeByDistance.value) {
                val t = (1f - (entry.distance / maxRange)).coerceIn(0f, 1f)
                (minAlpha.value + (255 - minAlpha.value) * t) / 255f
            } else 1f

            val smoothedCenter = if (rawCenter != null && smoothBox.value) {
                smooth(entry.key, rawCenter.first, rawCenter.second)
            } else rawCenter

            val isNearest = nearestGlow.value && entry === nearest

            if (smoothedCenter != null && rawCenter != null) {
                val deltaX = smoothedCenter.first  - rawCenter.first
                val deltaY = smoothedCenter.second - rawCenter.second

                when (renderMode.value) {
                    RenderMode.Tracer, RenderMode.Both -> {
                        tracerPaint.color = color
                        tracerPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                        tracerPaint.strokeWidth = if (isNearest) tracerWidth.value + 1.5f else tracerWidth.value
                        canvas.drawLine(centerX, centerY, smoothedCenter.first, smoothedCenter.second, tracerPaint)
                    }
                    else -> {}
                }

                var drawnRect: FloatArray? = null
                when (renderMode.value) {
                    RenderMode.Box, RenderMode.Both -> {
                        drawnRect = when (boxStyle.value) {
                            BoxStyle.Flat2D -> drawFlatBox(
                                canvas, entry, cx, cy, cz, yaw, pitch, screenW, screenH,
                                deltaX, deltaY, color, boxPaint, fillPaint,
                                alphaScale, isNearest, effectiveFov
                            )
                            BoxStyle.Wireframe3D -> {
                                drawWireBox(
                                    canvas, entry, cx, cy, cz, yaw, pitch, screenW, screenH,
                                    deltaX, deltaY, color, boxPaint, fillPaint,
                                    alphaScale, isNearest, effectiveFov
                                )
                                null
                            }
                        }
                    }
                    else -> {}
                }

                if (showLabels.value || showDistance.value) {
                    val label = buildString {
                        if (showLabels.value) append(if (isNearest) "» ${entry.name} «" else entry.name)
                        if (showDistance.value) {
                            if (isNotEmpty()) append("  ")
                            append("${entry.distance.roundToInt()}m")
                        }
                    }
                    textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                    val labelY = drawnRect?.get(1)?.minus(10f) ?: (smoothedCenter.second - entry.height * 30f)
                    val labelX = drawnRect?.let { (it[0] + it[2]) / 2f } ?: smoothedCenter.first
                    canvas.drawText(label, labelX, labelY, textPaint)
                }
            } else if (showRadar.value) {
                drawRadarArrow(canvas, entry, centerX, centerY, screenW, screenH, yaw, color, radarPaint)
            }
        }
    }

    // FIX (asıl "düzgün kutu" isteği): Xray/ESP'deki 3D wireframe küp bloklar
    // için mantıklı ama oyuncu etrafında sürekli döner/çarpık durur. Buradaki
    // yaklaşım, oyuncunun 8 köşesini ekrana projekte edip GÖRÜNEN köşelerin
    // screen-space min/max'ini alarak düz, eksene hizalı, her açıdan temiz
    // duran klasik "ESP box" dikdörtgeni çiziyor - diğer popüler hilelerdeki
    // target box'ın aynısı. Sol kenara opsiyonel can barı ekleniyor.
    private fun drawFlatBox(
        canvas: Canvas, entry: RenderEntry,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        deltaX: Float, deltaY: Float,
        colorArgb: Int,
        strokePaint: Paint, fillPaint: Paint,
        alphaScale: Float, isNearest: Boolean, fov: Float
    ): FloatArray? {
        val halfW = entry.width / 2f
        val corners = arrayOf(
            floatArrayOf(entry.feetX - halfW, entry.feetY,              entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY,              entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY,              entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY,              entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY + entry.height, entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY + entry.height, entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY + entry.height, entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY + entry.height, entry.feetZ + halfW)
        )

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var visible = 0
        for (c in corners) {
            val p = MathUtil.worldToScreen(c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fov) ?: continue
            val sx = p.first + deltaX; val sy = p.second + deltaY
            if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
            if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
            visible++
        }
        if (visible == 0) return null

        val thickness = if (isNearest) boxThickness.value + 1.5f else boxThickness.value
        strokePaint.strokeWidth = thickness
        strokePaint.color = colorArgb
        strokePaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)

        fillPaint.color = colorArgb
        fillPaint.alpha = (boxAlpha.value * alphaScale).toInt().coerceIn(0, 255)

        canvas.drawRoundRect(minX, minY, maxX, maxY, 4f, 4f, fillPaint)
        canvas.drawRoundRect(minX, minY, maxX, maxY, 4f, 4f, strokePaint)

        if (isNearest) {
            val glowPaint = Paint(strokePaint)
            glowPaint.alpha = (70 * alphaScale).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = thickness + 3f
            canvas.drawRoundRect(minX - 2f, minY - 2f, maxX + 2f, maxY + 2f, 5f, 5f, glowPaint)
        }

        if (showHealth.value) drawHealthIndicator(canvas, entry, minX, minY, maxY, alphaScale)

        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun drawHealthIndicator(canvas: Canvas, entry: RenderEntry, boxLeft: Float, boxTop: Float, boxBottom: Float, alphaScale: Float) {
        val pct = (entry.health / entry.maxHealth).coerceIn(0f, 1f)
        val healthColor = healthColorFor(pct)

        if (healthAsBar.value) {
            val barW = 5f
            val barLeft = boxLeft - barW - 4f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL; color = Color.BLACK; alpha = (140 * alphaScale).toInt().coerceIn(0, 255)
            }
            canvas.drawRect(barLeft, boxTop, barLeft + barW, boxBottom, bgPaint)
            val filledTop = boxBottom - (boxBottom - boxTop) * pct
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL; color = healthColor; alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
            }
            canvas.drawRect(barLeft, filledTop, barLeft + barW, boxBottom, fgPaint)
        } else {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = healthColor
                textSize = 22f
                textAlign = Paint.Align.LEFT
                alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText("${entry.health.roundToInt()}❤", boxLeft, boxBottom + 22f, textPaint)
        }
    }

    // İsteğe bağlı 3D wireframe stili - Xray/ESP.kt'deki drawBox3D ile aynı
    // mantık, sadece oyuncu AABB'si (feet-tabanlı, asimetrik yükseklik) için
    // genelleştirildi.
    private fun drawWireBox(
        canvas: Canvas, entry: RenderEntry,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        deltaX: Float, deltaY: Float,
        colorArgb: Int,
        strokePaint: Paint, fillPaint: Paint,
        alphaScale: Float, isNearest: Boolean, fov: Float
    ) {
        val halfW = entry.width / 2f
        val worldCorners = arrayOf(
            floatArrayOf(entry.feetX - halfW, entry.feetY,              entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY,              entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY,              entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY,              entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY + entry.height, entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY + entry.height, entry.feetZ - halfW),
            floatArrayOf(entry.feetX + halfW, entry.feetY + entry.height, entry.feetZ + halfW),
            floatArrayOf(entry.feetX - halfW, entry.feetY + entry.height, entry.feetZ + halfW)
        )

        val screenCorners = arrayOfNulls<FloatArray>(8)
        var visibleCount = 0
        for (i in worldCorners.indices) {
            val c = worldCorners[i]
            val p = MathUtil.worldToScreen(c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fov)
            if (p != null) {
                screenCorners[i] = floatArrayOf(p.first + deltaX, p.second + deltaY)
                visibleCount++
            }
        }
        if (visibleCount == 0) return

        strokePaint.color = colorArgb
        strokePaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        strokePaint.strokeWidth = if (isNearest) boxThickness.value + 1.5f else boxThickness.value

        val edges = intArrayOf(
            0, 1,  1, 2,  2, 3,  3, 0,
            4, 5,  5, 6,  6, 7,  7, 4,
            0, 4,  1, 5,  2, 6,  3, 7
        )
        drawEdges(canvas, screenCorners, edges, strokePaint)

        fillPaint.color = colorArgb
        fillPaint.alpha = (boxAlpha.value * alphaScale).toInt().coerceIn(0, 255)
        val p4 = screenCorners[4]; val p5 = screenCorners[5]
        val p6 = screenCorners[6]; val p7 = screenCorners[7]
        if (p4 != null && p5 != null && p6 != null && p7 != null) {
            val topPath = Path().apply {
                moveTo(p4[0], p4[1]); lineTo(p5[0], p5[1])
                lineTo(p6[0], p6[1]); lineTo(p7[0], p7[1])
                close()
            }
            canvas.drawPath(topPath, fillPaint)
        }

        if (isNearest) {
            val glowPaint = Paint(strokePaint)
            glowPaint.color = colorArgb
            glowPaint.alpha = (65 * alphaScale).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = strokePaint.strokeWidth + 3f
            drawEdges(canvas, screenCorners, edges, glowPaint)
        }
    }

    private fun drawEdges(canvas: Canvas, corners: Array<FloatArray?>, edges: IntArray, paint: Paint) {
        var i = 0
        while (i < edges.size) {
            val a = corners[edges[i]]
            val b = corners[edges[i + 1]]
            if (a != null && b != null) canvas.drawLine(a[0], a[1], b[0], b[1], paint)
            i += 2
        }
    }

    private fun smooth(key: Long, x: Float, y: Float): Pair<Float, Float> {
        val f = smoothFactor.value
        val prev = smoothedScreenPos[key]
        return if (prev == null) {
            smoothedScreenPos[key] = floatArrayOf(x, y)
            Pair(x, y)
        } else {
            val nx = prev[0] + (x - prev[0]) * f
            val ny = prev[1] + (y - prev[1]) * f
            smoothedScreenPos[key] = floatArrayOf(nx, ny)
            Pair(nx, ny)
        }
    }

    private fun colorFor(entry: RenderEntry): Int = when (colorMode.value) {
        ColorMode.Fixed  -> 0xFFFF3B3B.toInt()
        ColorMode.Team   -> if (entry.isFriend) 0xFF3BD6FF.toInt() else 0xFFFF3B3B.toInt()
        ColorMode.Health -> healthColorFor((entry.health / entry.maxHealth).coerceIn(0f, 1f))
    }

    // Kırmızı (düşük can) -> Sarı -> Yeşil (dolu can) gradyanı.
    private fun healthColorFor(pct: Float): Int {
        val r: Int; val g: Int
        if (pct < 0.5f) {
            r = 255; g = (255 * (pct / 0.5f)).roundToInt().coerceIn(0, 255)
        } else {
            r = (255 * (1f - (pct - 0.5f) / 0.5f)).roundToInt().coerceIn(0, 255); g = 255
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8)
    }

    private fun drawRadarArrow(
        canvas: Canvas, entry: RenderEntry,
        centerX: Float, centerY: Float,
        screenW: Int, screenH: Int,
        selfYaw: Float, color: Int, paint: Paint
    ) {
        val angleToTarget = Math.toDegrees(
            atan2(
                (EntityTracker.selfX - entry.centerX).toDouble(),
                (entry.centerZ - EntityTracker.selfZ).toDouble()
            )
        ).toFloat()
        val relative = ((angleToTarget - selfYaw) % 360f + 540f) % 360f - 180f
        val rad = Math.toRadians(relative.toDouble())

        val margin = radarMargin.value
        val maxX = screenW / 2f - margin
        val maxY = screenH / 2f - margin
        val dirX = sin(rad).toFloat()
        val dirY = -cos(rad).toFloat()

        val scale = min(
            if (abs(dirX) > 0.0001f) maxX / abs(dirX) else Float.MAX_VALUE,
            if (abs(dirY) > 0.0001f) maxY / abs(dirY) else Float.MAX_VALUE
        )
        val px = centerX + dirX * scale
        val py = centerY + dirY * scale

        paint.color = color
        paint.alpha = 220

        val size = 14f
        val perpX = -dirY; val perpY = dirX
        val tipX = px + dirX * size; val tipY = py + dirY * size
        val leftX = px - dirX * size * 0.5f + perpX * size * 0.6f
        val leftY = py - dirY * size * 0.5f + perpY * size * 0.6f
        val rightX = px - dirX * size * 0.5f - perpX * size * 0.6f
        val rightY = py - dirY * size * 0.5f - perpY * size * 0.6f

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(path, paint)
    }

    fun getRenderList(): List<RenderEntry> = renderList
    fun getTargetCount(): Int = renderList.size
}
