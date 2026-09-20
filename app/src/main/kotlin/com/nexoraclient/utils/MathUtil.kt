package com.rubidiumclient.utils

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

    fun worldToScreen(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float = 110f
    ): Pair<Float, Float>? {
        val eyeY = selfY + 1.62f
        val dx = (wx - selfX).toDouble()
        val dy = (wy - eyeY).toDouble()
        val dz = (wz - selfZ).toDouble()

        val yawR   = Math.toRadians(-yaw.toDouble())
        val pitchR = Math.toRadians(-pitch.toDouble())
        val sinY = sin(yawR);  val cosY = cos(yawR)
        val sinP = sin(pitchR); val cosP = cos(pitchR)

        val rx0 = -dx * cosY + dz * sinY
        val rz0 =  dx * sinY + dz * cosY
        val rx  =  rx0
        val ry  =  dy * cosP - rz0 * sinP
        val rz  =  dy * sinP + rz0 * cosP

        // FIX (yakın blok/hedef kayboluyor): 0.1 blok'luk near-clip eşiği,
        // madencilik/yakın dövüş mesafesinde (oyuncu bloğa/hedefe neredeyse
        // bitişikken rz kolayca 0.1'in altına düşüyor) çok agresifti — Xray'de
        // "bloğa yaklaşınca ekrandan kayboluyor" şikayetinin sebebi buydu:
        // worldToScreen null dönüyor, render radar-only moduna düşüyor.
        // 0.02'ye düşürüldü; hâlâ kamera arkasını (rz<=0) ve bölme-sıfıra-
        // yakın dejenere açıları filtreliyor ama gerçekçi yakın mesafede
        // artık doğru render ediyor.
        if (rz <= 0.02) return null

        val aspect      = screenW.toDouble() / screenH.toDouble()
        val tanHalfFovY = tan(Math.toRadians(fov / 2.0))
        val tanHalfFovX = tanHalfFovY * aspect

        val sx = (( rx / (rz * tanHalfFovX)) * (screenW / 2.0) + screenW / 2.0).toFloat()
        val sy = ((-ry / (rz * tanHalfFovY)) * (screenH / 2.0) + screenH / 2.0).toFloat()

        return Pair(sx, sy)
    }

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
