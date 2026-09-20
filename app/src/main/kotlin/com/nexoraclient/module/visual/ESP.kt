package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.BlockTracker.TrackedBlockType
import com.rubidiumclient.utils.GameFov
import com.rubidiumclient.utils.MathUtil
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class ESP : BaseModule(
    name        = "BaseFinderESP",
    category    = ModuleCategory.VISUAL,
    description = "Etraftaki sandık/shulker/spawner ve depolama bloklarını tracer ile gösterir"
) {
    enum class RenderMode  { Tracer, Box, Both }
    enum class SortMode    { Distance, Type }

    private val renderMode    = enum ("Render Mode",   RenderMode.Both)
    private val sortMode      = enum ("Sort Mode",     SortMode.Distance)
    private val scanRange     = float("Scan Range",    64f,  8f, 512f)
    private val autoFovSync    = bool ("Auto FOV Sync",  true)
    private val fov            = float("Manual FOV",    110f,  30f, 130f)
    private val maxDisplay    = int  ("Max Display",   150,  10, 800)
    private val tracerWidth   = float("Tracer Width",  2f,   0.5f, 8f)
    private val boxAlpha      = int  ("Box Alpha",     55,   10,  200)
    private val showLabels    = bool ("Show Labels",   true)
    private val showDistance  = bool ("Show Distance", true)
    private val showChest     = bool ("Chest",         true)
    private val showShulker   = bool ("Shulker",       true)
    private val showEnder     = bool ("Ender Chest",   true)
    private val showSpawner   = bool ("Spawner",       true)
    private val showHopper    = bool ("Hopper",        true)
    private val showBarrel    = bool ("Barrel",        true)
    private val showFurnace   = bool ("Furnace",       true)
    private val fadeByDistance= bool ("Distance Fade", true)
    private val minAlpha      = int  ("Min Alpha",     40,   0,   255)
    private val showRadar     = bool ("Off-Screen Radar", true)
    private val radarMargin   = float("Radar Margin",  36f,  10f, 100f)
    private val showSummary   = bool ("Summary Panel", true)
    private val smoothTracer  = bool ("Smooth Tracer", true)
    private val smoothFactor  = float("Smooth Factor", 0.35f,0.05f, 1f)
    private val nearestGlow   = bool ("Highlight Nearest", true)
    private val updateRateMs  = int  ("Update Rate (ms)", 150, 50, 1000)
    private val shortcut      = bool ("Shortcut",      false)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var updateJob: Job? = null

    private val smoothedScreenPos = ConcurrentHashMap<Long, FloatArray>()

    data class RenderEntry(
        val x: Float, val y: Float, val z: Float,
        val type: TrackedBlockType,
        val distance: Float,
        val key: Long
    )

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
        val range = scanRange.value

        val entries = BlockTracker.getAllInRange(cx, cy, cz, range)
            .filter { isTypeEnabled(it.type) }
            .map { b ->
                RenderEntry(
                    b.pos.x + 0.5f,
                    b.pos.y + 0.5f,
                    b.pos.z + 0.5f,
                    b.type,
                    MathUtil.dist3(b.pos.x + 0.5f, b.pos.y + 0.5f, b.pos.z + 0.5f, cx, cy, cz),
                    BlockTracker.packKey(b.pos.x, b.pos.y, b.pos.z)
                )
            }

        val sorted = when (sortMode.value) {
            SortMode.Distance -> entries.sortedBy { it.distance }
            SortMode.Type     -> entries.sortedBy { it.type.ordinal }
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
        val maxRange = scanRange.value
        val nearest = if (nearestGlow.value) renderList.minByOrNull { it.distance } else null
        val effectiveFov = if (autoFovSync.value) GameFov.current else fov.value

        for (entry in renderList) {
            val color = entry.type.colorArgb
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
                    var label = entry.type.displayName
                    if (isNearest) label = "» $label «"
                    if (showDistance.value) label += " §${entry.distance.toInt()}m"
                    textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                    val labelY = screenPos.second - 28f
                    canvas.drawText(label, screenPos.first, labelY, textPaint)
                }
            } else if (showRadar.value) {
                drawRadarArrow(canvas, entry, centerX, centerY, screenW, screenH, yaw, color, radarPaint)
            }
        }

        if (showSummary.value) drawSummaryPanel(canvas, screenW)
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
        // Hiç köşe yoksa çizme
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

    private fun isTypeEnabled(type: TrackedBlockType): Boolean = when (type) {
        TrackedBlockType.CHEST,
        TrackedBlockType.TRAPPED_CHEST -> showChest.value
        TrackedBlockType.SHULKER_BOX   -> showShulker.value
        TrackedBlockType.ENDER_CHEST   -> showEnder.value
        TrackedBlockType.SPAWNER       -> showSpawner.value
        TrackedBlockType.HOPPER        -> showHopper.value
        TrackedBlockType.BARREL        -> showBarrel.value
        TrackedBlockType.FURNACE,
        TrackedBlockType.BLAST_FURNACE,
        TrackedBlockType.SMOKER        -> showFurnace.value
        TrackedBlockType.BREWING_STAND -> true
        TrackedBlockType.DISPENSER,
        TrackedBlockType.DROPPER       -> true
    }

    fun getRenderList(): List<RenderEntry> = renderList
    fun getBlockCount(): Int = BlockTracker.size()
}
