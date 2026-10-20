package com.rubidiumclient.module.combat.rots

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Rotaciones geometricas: el punto de mira se mueve en una figura alrededor del pecho del enemigo
 * (la mira se va por el cuerpo en vez de quedarse fija). El servidor solo ve el giro, no la figura.
 *
 *  - Circle:  circulo alrededor del objetivo.
 *  - Square:  recorre los 4 lados de un cuadrado.
 *  - Zigzag:  va de lado a lado con un pequeno vaiven vertical.
 *  - Spiral:  circulo cuyo radio sube y baja (espiral).
 */
object GeoRot {

    val MODES = listOf("Circle", "Square", "Zigzag", "Spiral")

    /** Radio de la figura en bloques (un hitbox de jugador mide unos 0.6). */
    @Volatile var radius = 0.45f

    /** Velocidad: vueltas por segundo aprox. = speed * 20 / 2pi (1.0 = una vuelta por segundo). */
    @Volatile var speed = 1.0f

    /** Desplazamiento (dx, dy, dz) respecto al pecho del objetivo para este tick. */
    fun offset(mode: String, tick: Long): Triple<Float, Float, Float> {
        val phase = tick * speed * 0.05f * 2f * PI.toFloat() // radianes por tick
        val r = radius
        return when (mode) {
            "Square" -> {
                val t = ((phase / (2f * PI.toFloat())) * 4f) % 4f // 4 lados
                val side = floor(t).toInt()
                val f = t - side
                val (x, z) = when (side) {
                    0 -> r to (-r + 2 * r * f)
                    1 -> (r - 2 * r * f) to r
                    2 -> -r to (r - 2 * r * f)
                    else -> (-r + 2 * r * f) to -r
                }
                Triple(x, 0f, z)
            }
            "Zigzag" -> {
                // Onda triangular de -1 a 1: de un lado al otro del objetivo.
                val p = (phase / (2f * PI.toFloat())) % 1f
                val tri = 1f - 4f * abs(p - 0.5f)
                Triple(r * tri, 0.25f * r * sin(phase * 2f), 0f)
            }
            "Spiral" -> {
                val rr = r * (0.5f + 0.5f * sin(phase * 0.3f))
                Triple(rr * cos(phase), 0f, rr * sin(phase))
            }
            else -> Triple(r * cos(phase), 0f, r * sin(phase)) // Circle
        }
    }
}
