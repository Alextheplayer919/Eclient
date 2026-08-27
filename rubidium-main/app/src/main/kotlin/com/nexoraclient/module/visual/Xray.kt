package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.GameFov
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.OreTracker
import com.rubidiumclient.utils.OreTracker.OreType
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class Xray : BaseModule(
    name        = "Xray",
    category    = ModuleCategory.VISUAL,
    description = "Cevherleri (normal + deepslate) tracer/box ile ekranda gösterir"
) {
    enum class RenderMode { Tracer, Box, Both }
    enum class SortMode   { Distance, Tier }
    enum class TierFilter { All, ValuableOnly }

    private val renderMode     = enum ("Render Mode",   RenderMode.Both)
    private val sortMode       = enum ("Sort Mode",     SortMode.Distance)
    private val tierFilter     = enum ("Tier Filter",   TierFilter.All)
    private val scanRange      = int  ("Scan Range",    48,   0,  256)
    private val scanIntervalMs = int  ("Scan Interval (ms)", 1200, 400, 5000)
    private val autoFovSync    = bool ("Auto FOV Sync", true)
    private val fov            = float("Manual FOV",    110f, 30f, 130f)
    private val maxDisplay     = int  ("Max Display",   200,  10,  1000)
    private val tracerWidth    = float("Tracer Width",  2f,   0.5f, 8f)
    private val boxAlpha       = int  ("Box Alpha",     55,   10,  200)
    private val showLabels     = bool ("Show Labels",   true)
    private val showDistance   = bool ("Show Distance", true)
    private val showCoal       = bool ("Coal",          false)
    private val showIron       = bool ("Iron",          true)
    private val showCopper     = bool ("Copper",        false)
    private val showGold       = bool ("Gold",          true)
    private val showRedstone   = bool ("Redstone",      true)
    private val showLapis      = bool ("Lapis",         true)
    private val showDiamond    = bool ("Diamond",       true)
    private val showEmerald    = bool ("Emerald",       true)
    private val showAncientDebris = bool ("Ancient Debris", true)
    private val showQuartz     = bool ("Nether Quartz", false)
    private val showNetherGold = bool ("Nether Gold",   false)
    private val distinguishDeepslate = bool ("Distinguish Deepslate", true)
    private val fadeByDistance = bool ("Distance Fade", true)
    private val minAlpha       = int  ("Min Alpha",     40,   0,   255)
    private val showRadar      = bool ("Off-Screen Radar", true)
    private val radarMargin    = float("Radar Margin",  36f,  10f, 100f)
    private val showSummary    = bool ("Summary Panel", true)
    private val showScanStats  = bool ("Scan Stats",    false)
    private val smoothTracer   = bool ("Smooth Tracer", true)
    private val smoothFactor   = float("Smooth Factor", 0.35f, 0.05f, 1f)
    private val nearestGlow    = bool ("Highlight Nearest", true)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var scanJob: Job? = null
    private var rebuildJob: Job? = null

    private val smoothedScreenPos = ConcurrentHashMap<Long, FloatArray>()

    data class RenderEntry(
        val x: Float, val y: Float, val z: Float,
        val type: OreType,
        val isDeepslate: Boolean,
        val distance: Float,
        val key: Long
    )

    companion object {
        private const val REBUILD_RATE_MS = 150L
    }

    override fun onEnable() {
        super.onEnable()
        smoothedScreenPos.clear()
        OreTracker.clear()
        scanJob = scope.launch { scanLoop() }
        rebuildJob = scope.launch { rebuildLoop() }
    }

    override fun onDisable() {
        scanJob?.cancel(); scanJob = null
        rebuildJob?.cancel(); rebuildJob = null
        super.onDisable()
        renderList.clear()
        smoothedScreenPos.clear()
        OreTracker.clear()
    }

    private suspend fun scanLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled && !OreTracker.isScanning()) {
                val cx = floor(EntityTracker.selfX).toInt()
                val cy = floor(EntityTracker.selfY).toInt()
                val cz = floor(EntityTracker.selfZ).toInt()
                OreTracker.scan(cx, cy, cz, scanRange.value, enabledTypes())
            }
            delay(scanIntervalMs.value.toLong())
        }
    }

    private suspend fun rebuildLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) rebuildRenderList()
            delay(REBUILD_RATE_MS)
        }
    }

    private fun enabledTypes(): Set<OreType> {
        val set = HashSet<OreType>(11)
        if (showCoal.value) set.add(OreType.COAL)
        if (showIron.value) set.add(OreType.IRON)
        if (showCopper.value) set.add(OreType.COPPER)
        if (showGold.value) set.add(OreType.GOLD)
        if (showRedstone.value) set.add(OreType.REDSTONE)
        if (showLapis.value) set.add(OreType.LAPIS)
        if (showDiamond.value) set.add(OreType.DIAMOND)
        if (showEmerald.value) set.add(OreType.EMERALD)
        if (showAncientDebris.value) set.add(OreType.ANCIENT_DEBRIS)
        if (showQuartz.value) set.add(OreType.NETHER_QUARTZ)
        if (showNetherGold.value) set.add(OreType.NETHER_GOLD)
        return set
    }

    private fun rebuildRenderList() {
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val range = scanRange.value.toFloat()
        val minTier = if (tierFilter.value == TierFilter.ValuableOnly) 3 else 1

        val entries = OreTracker.getAllInRange(cx, cy, cz, range)
            .filter { it.type.tier >= minTier }
            .map { o ->
                RenderEntry(
                    o.pos.x + 0.5f,
                    o.pos.y + 0.5f,
                    o.pos.z + 0.5f,
                    o.type,
                    o.isDeepslate,
                    MathUtil.dist3(o.pos.x + 0.5f, o.pos.y + 0.5f, o.pos.z + 0.5f, cx, cy, cz),
                    OreTracker.packKey(o.pos.x, o.pos.y, o.pos.z)
                )
            }

        val sorted = when (sortMode.value) {
            SortMode.Distance -> entries.sortedBy { it.distance }
            SortMode.Tier     -> entries.sortedByDescending { it.type.tier }
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
            strokeWidth = 1.5f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = 28f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val centerX = screenW / 2f
        val centerY = screenH / 2f
        val maxRange = scanRange.value.toFloat()
        val nearest = if (nearestGlow.value) renderList.minByOrNull { it.distance } else null
        val effectiveFov = if (autoFovSync.value) GameFov.current else fov.value

        for (entry in renderList) {
            val color = colorFor(entry)
            val rawPos = MathUtil.worldToScreen(
                entry.x, entry.y, entry.z,
                cx, cy, cz,
                yaw, pitch,
                screenW, screenH,
                effectiveFov
            )

            val alphaScale = if (fadeByDistance.value) {
                val t = (1f - (entry.distance / maxRange)).coerceIn(0f, 1f)
                (minAlpha.value + (255 - minAlpha.value) * t) / 255f
            } else 1f

            val screenPos = if (rawPos != null && smoothTracer.value) {
                smooth(entry.key, rawPos.first, rawPos.second)
            } else rawPos

            val isNearest = nearestGlow.value && entry === nearest

            if (screenPos != null) {
                val deltaX = screenPos.first  - rawPos!!.first
                val deltaY = screenPos.second - rawPos.second

                when (renderMode.value) {
                    RenderMode.Tracer, RenderMode.Both -> {
                        tracerPaint.color = color
                        tracerPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                        tracerPaint.strokeWidth = if (isNearest) tracerWidth.value + 1.5f else tracerWidth.value
                        canvas.drawLine(centerX, centerY, screenPos.first, screenPos.second, tracerPaint)
                    }
                    else -> {}
                }

                when (renderMode.value) {
                    RenderMode.Box, RenderMode.Both -> {
                        drawBox3D(
                            canvas,
                            entry.x, entry.y, entry.z,
                            cx, cy, cz,
                            yaw, pitch,
                            screenW, screenH,
                            deltaX, deltaY,
                            color, boxPaint, fillPaint, alphaScale, isNearest,
                            effectiveFov
                        )
                    }
                    else -> {}
                }

                if (showLabels.value) {
                    var label = labelFor(entry)
                    if (isNearest) label = "» $label «"
                    if (showDistance.value) label += " ${entry.distance.toInt()}m"
                    textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                    val labelY = screenPos.second - 28f
                    canvas.drawText(label, screenPos.first, labelY, textPaint)
                }
            } else if (showRadar.value) {
                drawRadarArrow(canvas, entry, centerX, centerY, screenW, screenH, yaw, color, radarPaint)
            }
        }

        if (showSummary.value) drawSummaryPanel(canvas, screenW)
        if (showScanStats.value) drawScanStats(canvas, screenW, screenH)
    }

    private fun colorFor(entry: RenderEntry): Int {
        if (!distinguishDeepslate.value || !entry.isDeepslate) return entry.type.colorArgb
        // Deepslate varyantını ayırt etmek için temel rengi biraz koyulaştırıyoruz
        val base = entry.type.colorArgb
        val r = ((base shr 16) and 0xFF)
        val g = ((base shr 8) and 0xFF)
        val b = (base and 0xFF)
        val factor = 0.62f
        val dr = (r * factor).toInt().coerceIn(0, 255)
        val dg = (g * factor).toInt().coerceIn(0, 255)
        val db = (b * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db
    }

    private fun labelFor(entry: RenderEntry): String {
        val base = entry.type.displayName
        return if (distinguishDeepslate.value && entry.isDeepslate) "Deepslate $base" else base
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

    private fun drawRadarArrow(
        canvas: Canvas, entry: RenderEntry,
        centerX: Float, centerY: Float,
        screenW: Int, screenH: Int,
        selfYaw: Float, color: Int, paint: Paint
    ) {
        val angleToTarget = Math.toDegrees(
            atan2(
                (EntityTracker.selfX - entry.x).toDouble(),
                (entry.z - EntityTracker.selfZ).toDouble()
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

    private fun drawSummaryPanel(canvas: Canvas, screenW: Int) {
        val counts = renderList.groupingBy { it.type }.eachCount()
        if (counts.isEmpty()) return

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            alpha = 130
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = 24f
            textAlign = Paint.Align.LEFT
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val entries = counts.entries.sortedByDescending { it.value }
        val lineHeight = 30f
        val paddingX = 14f
        val paddingY = 10f
        val panelW = 190f
        val panelH = paddingY * 2 + lineHeight * entries.size
        val startX = screenW - panelW - 16f
        val startY = 16f

        canvas.drawRoundRect(startX, startY, startX + panelW, startY + panelH, 10f, 10f, panelPaint)

        entries.forEachIndexed { i, (type, count) ->
            linePaint.color = type.colorArgb
            canvas.drawText(
                "${type.displayName}: $count",
                startX + paddingX,
                startY + paddingY + lineHeight * (i + 1) - 8f,
                linePaint
            )
        }
    }

    private fun drawScanStats(canvas: Canvas, screenW: Int, screenH: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = 22f
            textAlign = Paint.Align.LEFT
            color     = Color.LTGRAY
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        val text = "scan: ${OreTracker.lastScanDurationMs}ms  blocks: ${OreTracker.lastScanBlockCount}  shown: ${renderList.size}"
        canvas.drawText(text, 16f, screenH - 16f, paint)
    }

    private fun drawBox3D(
        canvas: Canvas,
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        deltaX: Float, deltaY: Float,
        colorArgb: Int,
        strokePaint: Paint,
        fillPaint: Paint,
        alphaScale: Float = 1f,
        isNearest: Boolean = false,
        fov: Float = GameFov.VANILLA_DEFAULT
    ) {
        val half = 0.5f
        val worldCorners = arrayOf(
            floatArrayOf(wx - half, wy - half, wz - half),
            floatArrayOf(wx + half, wy - half, wz - half),
            floatArrayOf(wx + half, wy - half, wz + half),
            floatArrayOf(wx - half, wy - half, wz + half),
            floatArrayOf(wx - half, wy + half, wz - half),
            floatArrayOf(wx + half, wy + half, wz - half),
            floatArrayOf(wx + half, wy + half, wz + half),
            floatArrayOf(wx - half, wy + half, wz + half)
        )

        val screenCorners = arrayOfNulls<FloatArray>(8)
        var visibleCount = 0
        for (i in worldCorners.indices) {
            val c = worldCorners[i]
            val p = MathUtil.worldToScreen(
                c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fov
            )
            if (p != null) {
                screenCorners[i] = floatArrayOf(p.first + deltaX, p.second + deltaY)
                visibleCount++
            }
        }
        if (visibleCount == 0) return

        strokePaint.color = colorArgb
        strokePaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        strokePaint.strokeWidth = if (isNearest) tracerWidth.value + 1.5f else tracerWidth.value

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

    fun getRenderList(): List<RenderEntry> = renderList
    fun getOreCount(): Int = OreTracker.size()
}
