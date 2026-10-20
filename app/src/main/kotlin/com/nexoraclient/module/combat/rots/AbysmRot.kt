package com.rubidiumclient.module.combat.rots

import com.rubidiumclient.module.combat.rots.HvHGod.Style
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * AbysmRot: rotacion unificada de Abysm. Combina lo mejor de los modos anteriores:
 *  - Prediccion del objetivo (como Predictive / AirHvH Pro): apunta adonde va a estar, no donde esta.
 *  - Giro suave con aceleracion (como Lynx Smooth / Astral): al principio rapido, al final frena,
 *    sin pasarse del objetivo, con tope de velocidad por tick.
 *  - Ajuste por estilo de rotacion del enemigo (HvHGod): gira mas rapido contra spinners y
 *    snappers, mas suave contra quienes ya te miran fijo, y adelanta mas la prediccion si
 *    strafeas o saltas.
 *
 * Usa solo datos que el servidor ya manda. Es deterministico salvo el ruido opcional [humanize].
 */
object AbysmRot {

    /** Grados maximos por tick antes de multiplicar por el estilo. */
    @Volatile var maxSpeed = 35f

    /** Ruido leve en grados para que no sea perfecto. 0 = desactivado. */
    @Volatile var humanize = 0f

    private var yaw = Float.NaN
    private var pitch = 0f
    private val rnd = java.util.Random()

    /** Llamar cuando no hay objetivo, para que al volver a agarrar uno no arrastre el giro viejo. */
    fun reset() {
        yaw = Float.NaN
        pitch = 0f
    }

    /**
     * Devuelve (pitch, yaw) para este tick.
     * (tx,ty,tz) = punto a apuntar del enemigo (ya con altura de pecho);
     * (tvx,tvy,tvz) = su velocidad por tick; (ex,ey,ez) = tus ojos.
     */
    fun aim(
        tx: Float, ty: Float, tz: Float,
        tvx: Float, tvy: Float, tvz: Float,
        ex: Float, ey: Float, ez: Float,
        style: Style
    ): Pair<Float, Float> {
        val lead = when (style) {
            Style.STRAFER, Style.AIR -> 3f
            Style.SPINNER -> 1f
            else -> 2f
        }
        val px = tx + tvx * lead
        val py = if (style == Style.AIR) ty + tvy * lead else ty
        val pz = tz + tvz * lead

        val dx = px - ex
        val dy = py - ey
        val dz = pz - ez
        val wantYaw = (-atan2(dx.toDouble(), dz.toDouble()) * RAD2DEG).toFloat()
        val wantPitch = (-atan2(dy.toDouble(), hypot(dx.toDouble(), dz.toDouble())) * RAD2DEG)
            .toFloat().coerceIn(-90f, 90f)

        if (yaw.isNaN()) {
            yaw = wantYaw
            pitch = wantPitch
        }

        val cap = maxSpeed * speedMultiplier(style)
        yaw = wrap180(yaw + step(wrap180(wantYaw - yaw), cap))
        pitch = (pitch + step(wantPitch - pitch, cap)).coerceIn(-90f, 90f)

        if (humanize > 0f) {
            yaw = wrap180(yaw + (rnd.nextFloat() - 0.5f) * humanize)
            pitch = (pitch + (rnd.nextFloat() - 0.5f) * humanize * 0.5f).coerceIn(-90f, 90f)
        }
        return pitch to yaw
    }

    /**
     * Paso hacia el objetivo: 45% del error (acelera al inicio y frena al llegar), al menos 1 grado
     * mientras este lejos, sin pasarse nunca del error y sin superar [cap].
     */
    private fun step(d: Float, cap: Float): Float {
        val a = abs(d)
        if (a < 0.05f) return d
        val s = max(min(a * 0.45f, cap), min(a, 1f))
        return sign(d) * min(s, a)
    }

    private fun speedMultiplier(style: Style): Float = when (style) {
        Style.SPINNER -> 1.6f
        Style.SNAP -> 1.8f
        Style.JITTER -> 1.3f
        Style.AIR -> 1.2f
        Style.STRAFER -> 1.1f
        Style.LOCKED -> 0.8f
        Style.IDLE -> 0.6f
        Style.UNKNOWN -> 1f
    }

    private fun wrap180(a: Float): Float {
        if (!a.isFinite()) return 0f
        var r = (a + 180f) % 360f
        if (r < 0f) r += 360f
        return r - 180f
    }

    private const val RAD2DEG = 57.29577951308232
}
