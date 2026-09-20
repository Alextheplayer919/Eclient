package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.GameFov
import com.rubidiumclient.utils.MathUtil
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.floor

class ChunkFinder : BaseModule(
    name        = "ChunkFinder",
    category    = ModuleCategory.VISUAL,
    description = "Chunk sınırlarını (16x16 grid) 3D dünyada çizer"
) {

    private val renderDistance  = int  ("Render Distance (chunks)", 3,    1,   6)
    private val poleHeight      = float("Pole Height",              24f,  4f,  64f)
    private val showGrid        = bool ("Show Grid",                true)
    private val showPoles       = bool ("Show Poles",                true)
    private val highlightCurrent= bool ("Highlight Current Chunk",  true)
    private val showLabel       = bool ("Show Chunk Coords",        true)
    private val lineWidth       = float("Line Width",               1.5f, 0.5f, 6f)
    private val alpha           = int  ("Alpha",                    140,  10,  255)
    private val fadeByDistance  = bool ("Distance Fade",            true)
    private val autoFovSync     = bool ("Auto FOV Sync",            true)
    private val fov             = float("Manual FOV",               110f, 30f, 130f)
    private val updateRateMs    = int  ("Update Rate (ms)",         200,  50,  1000)
    private val shortcut        = bool ("Shortcut",                 false)

    private val gridColor      = Color.rgb(0, 210, 255)
    private val currentColor   = Color.rgb(255, 60, 140)

    @Volatile private var chunks: List<IntArray> = emptyList()
    private var updateJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        updateJob = launchTickLoop(updateRateMs.value.toLong()) { rebuildChunkList() }
    }

    override fun onDisable() {
        updateJob?.cancel()
        updateJob = null
        chunks = emptyList()
        super.onDisable()
    }

    private fun rebuildChunkList() {
        val pcx = floor(EntityTracker.selfX).toInt() shr 4
        val pcz = floor(EntityTracker.selfZ).toInt() shr 4
        val range = renderDistance.value

        val out = ArrayList<IntArray>((2 * range + 1) * (2 * range + 1))
        for (dx in -range..range) {
            for (dz in -range..range) {
                out.add(intArrayOf(pcx + dx, pcz + dz, maxOf(abs(dx), abs(dz))))
            }
        }
        chunks = out
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        val list = chunks
        if (list.isEmpty()) return

        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch
        val effectiveFov = if (autoFovSync.value) GameFov.current else fov.value
        val range = renderDistance.value.coerceAtLeast(1)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = lineWidth.value
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = 26f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val baseY = floor(cy)
        val poleH = poleHeight.value

        for (entry in list) {
            val chunkX = entry[0]
            val chunkZ = entry[1]
            val chunkDist = entry[2]
            val isCurrent = chunkDist == 0

            val alphaScale = if (fadeByDistance.value) {
                (1f - chunkDist.toFloat() / (range + 1)).coerceIn(0.15f, 1f)
            } else 1f

            val color = if (isCurrent && highlightCurrent.value) currentColor else gridColor
            gridPaint.color = color
            gridPaint.alpha = ((if (isCurrent && highlightCurrent.value) alpha.value + 40 else alpha.value) * alphaScale)
                .toInt().coerceIn(0, 255)
            gridPaint.strokeWidth = if (isCurrent && highlightCurrent.value) lineWidth.value + 1f else lineWidth.value

            val wx0 = (chunkX shl 4).toFloat()
            val wz0 = (chunkZ shl 4).toFloat()
            val wx1 = wx0 + 16f
            val wz1 = wz0 + 16f

            if (showGrid.value) {
                drawGridSquare(canvas, wx0, baseY, wz0, wx1, wz1, cx, cy, cz, yaw, pitch, screenW, screenH, effectiveFov, gridPaint)
            }
            if (showPoles.value) {
                drawCornerPoles(canvas, wx0, wz0, wx1, wz1, baseY - poleH, baseY + poleH, cx, cy, cz, yaw, pitch, screenW, screenH, effectiveFov, gridPaint)
            }
            if (isCurrent && showLabel.value) {
                val topCenter = MathUtil.worldToScreen(
                    (wx0 + wx1) / 2f, baseY + poleH, (wz0 + wz1) / 2f,
                    cx, cy, cz, yaw, pitch, screenW, screenH, effectiveFov
                )
                if (topCenter != null) {
                    canvas.drawText("Chunk $chunkX, $chunkZ", topCenter.first, topCenter.second, labelPaint)
                }
            }
        }
    }

    private fun drawGridSquare(
        canvas: Canvas,
        wx0: Float, y: Float, wz0: Float,
        wx1: Float, wz1: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fovValue: Float,
        paint: Paint
    ) {
        val corners = arrayOf(
            floatArrayOf(wx0, y, wz0),
            floatArrayOf(wx1, y, wz0),
            floatArrayOf(wx1, y, wz1),
            floatArrayOf(wx0, y, wz1)
        )
        val projected = arrayOfNulls<FloatArray>(4)
        for (i in corners.indices) {
            val c = corners[i]
            val p = MathUtil.worldToScreen(c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue)
            if (p != null) projected[i] = floatArrayOf(p.first, p.second)
        }
        val edges = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0)
        var i = 0
        while (i < edges.size) {
            val a = projected[edges[i]]
            val b = projected[edges[i + 1]]
            if (a != null && b != null) canvas.drawLine(a[0], a[1], b[0], b[1], paint)
            i += 2
        }
    }

    private fun drawCornerPoles(
        canvas: Canvas,
        wx0: Float, wz0: Float, wx1: Float, wz1: Float,
        yLow: Float, yHigh: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fovValue: Float,
        paint: Paint
    ) {
        val cornersXZ = arrayOf(
            floatArrayOf(wx0, wz0),
            floatArrayOf(wx1, wz0),
            floatArrayOf(wx1, wz1),
            floatArrayOf(wx0, wz1)
        )
        for (c in cornersXZ) {
            val bottom = MathUtil.worldToScreen(c[0], yLow, c[1], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue)
            val top    = MathUtil.worldToScreen(c[0], yHigh, c[1], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue)
            if (bottom != null && top != null) {
                canvas.drawLine(bottom.first, bottom.second, top.first, top.second, paint)
            }
        }
    }
}
