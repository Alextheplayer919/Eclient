package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import kotlin.math.*

object MathUtil {

    fun dist2(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x1 - x2; val dz = z1 - z2
        return sqrt(dx * dx + dz * dz)
    }

    fun dist3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun dist3sq(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return dx * dx + dy * dy + dz * dz
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun clamp(v: Float, min: Float, max: Float): Float = v.coerceIn(min, max)

    fun randomRange(lo: Float, hi: Float): Float =
        lo + (Math.random() * (hi - lo)).toFloat()

    fun randomInt(lo: Int, hi: Int): Int =
        lo + (Math.random() * (hi - lo + 1)).toInt().coerceAtMost(hi - lo)

    fun cpsToDelayMs(cpsLo: Int, cpsHi: Int): Long {
        val lo  = cpsLo.coerceIn(1, 50)
        val hi  = cpsHi.coerceIn(lo, 50)
        val cps = lo + (Math.random() * (hi - lo + 1)).toInt().coerceAtMost(hi - lo)
        return 1000L / cps.toLong()
    }

    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * World → screen projection. Signature unchanged; the work is done by [RenderCamera], which
     * prefers the in-game sensor (hybrid-lite) when one is attached and otherwise falls back to
     * the original packet-based pinhole estimate ([Projector.pinhole]).
     */
    fun worldToScreen(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float = 110f
    ): Pair<Float, Float>? = RenderCamera.project(
        wx, wy, wz, selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fov,
        selfYIsEye = EntityTracker.selfYFrameIsEye,
    )

    // FIX (KillAura/KillAuraPro "gereksiz lag" optimizasyonu): Multi-target
    // saldırıda aradaki duvarlardan bağımsız her hedefe paket gönderiliyordu.
    // Bu, WorldBlockTracker verisini kullanarak basit bir ray-cast ile
    // aradan solid blok geçip geçmediğini kontrol ediyor — CrystalAura'daki
    // exposure ray-cast'iyle aynı mantık, combat modüllerinin de
    // kullanabilmesi için buraya (paylaşılan util) taşındı.
    private val NON_SOLID_LOS = setOf(
        "minecraft:air", "minecraft:water", "minecraft:flowing_water",
        "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:void_air", "minecraft:cave_air"
    )

    fun hasLineOfSight(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        if (!WorldBlockTracker.hasAnyTerrainData()) return true
        val dist = dist3(x0, y0, z0, x1, y1, z1)
        if (dist < 0.01f) return true
        val steps = (dist * 2f).toInt().coerceIn(1, 40)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val bx = floor(x0 + (x1 - x0) * t).toInt()
            val by = floor(y0 + (y1 - y0) * t).toInt()
            val bz = floor(z0 + (z1 - z0) * t).toInt()
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id !in NON_SOLID_LOS) return false
        }
        return true
    }
}
