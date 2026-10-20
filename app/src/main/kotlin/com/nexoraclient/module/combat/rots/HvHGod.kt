package com.rubidiumclient.module.combat.rots

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * HvHGod: elige automaticamente el handler de [KillauraRots] segun el ESTILO DE ROTACION que
 * usa el enemigo.
 *
 * Por cada candidato se guarda una ventana corta de muestras (yaw, posicion) una por tick y se
 * calculan estas metricas:
 *  - meanAbs / maxAbs del cambio de yaw por tick,
 *  - cambios de signo del giro (vaiven = jitter) y consistencia del giro (spinner),
 *  - error de mira: diferencia entre hacia donde mira y la direccion hacia nosotros,
 *  - velocidad lateral respecto a nosotros (strafe) y velocidad vertical (aire).
 *
 * Con eso lo clasifica en un [Style] y devuelve el handler asociado en [PROFILE]. El cambio tiene
 * histeresis (el estilo nuevo debe repetirse [CONFIRM] veces y el handler actual se mantiene al
 * menos [MIN_HOLD] ticks) para no oscilar entre handlers.
 *
 * Es determinista (sin aleatoriedad) y solo lee datos que el servidor ya envia sobre el enemigo.
 * La tabla PROFILE es heuristica: ajustala segun lo que mejor te funcione contra cada estilo.
 */
class HvHGod {

    enum class Style { SPINNER, JITTER, SNAP, AIR, STRAFER, LOCKED, IDLE, UNKNOWN }

    private class Sample(val yaw: Float, val x: Float, val y: Float, val z: Float)

    private class Track {
        val samples = ArrayDeque<Sample>()
        var lastTick = Long.MIN_VALUE
        var style = Style.UNKNOWN
        var pending = Style.UNKNOWN
        var pendingCount = 0
        var heldSince = Long.MIN_VALUE
    }

    private val tracks = HashMap<Long, Track>()

    /** Estilo detectado del ultimo objetivo consultado (util para depurar). */
    var lastStyle = Style.UNKNOWN
        private set

    fun reset() {
        tracks.clear()
        lastStyle = Style.UNKNOWN
    }

    fun retain(ids: Collection<Long>) {
        if (tracks.isNotEmpty()) tracks.keys.retainAll(ids.toSet())
    }

    /** Alimenta el analizador (una vez por tick y entidad; idempotente dentro del mismo tick). */
    fun observe(id: Long, yaw: Float, x: Float, y: Float, z: Float, tick: Long) {
        val t = tracks.getOrPut(id) { Track() }
        if (tick < t.lastTick) { t.samples.clear(); t.lastTick = Long.MIN_VALUE } // mundo nuevo
        if (tick == t.lastTick) return
        t.lastTick = tick
        t.samples.addLast(Sample(yaw, x, y, z))
        while (t.samples.size > WINDOW) t.samples.removeFirst()
    }

    /**
     * Devuelve el nombre del handler de KillauraRots a usar contra [id].
     * [meX]/[meZ] = posicion del jugador local (para saber si el enemigo nos mira).
     */
    fun pick(id: Long, meX: Float, meZ: Float, tick: Long, fallback: String = DEFAULT): String {
        val t = tracks[id] ?: return fallback
        val detected = classify(t, meX, meZ)

        if (detected == t.style) {
            t.pending = detected; t.pendingCount = 0
        } else {
            if (detected == t.pending) t.pendingCount++ else { t.pending = detected; t.pendingCount = 1 }
            val heldLongEnough = t.heldSince == Long.MIN_VALUE || tick - t.heldSince >= MIN_HOLD
            if (t.pendingCount >= CONFIRM && heldLongEnough) {
                t.style = detected
                t.heldSince = tick
                t.pendingCount = 0
            }
        }
        lastStyle = t.style
        return PROFILE[t.style] ?: fallback
    }

    private fun classify(t: Track, meX: Float, meZ: Float): Style {
        val s = t.samples
        if (s.size < MIN_SAMPLES) return Style.UNKNOWN

        // ---- giro de yaw por tick ----
        var sumAbs = 0f; var maxAbs = 0f; var signChanges = 0; var net = 0f
        var prevSign = 0
        for (i in 1 until s.size) {
            val d = wrap180(s[i].yaw - s[i - 1].yaw)
            val a = abs(d)
            sumAbs += a; net += d
            if (a > maxAbs) maxAbs = a
            if (a > 2f) {
                val sg = if (d > 0f) 1 else -1
                if (prevSign != 0 && sg != prevSign) signChanges++
                prevSign = sg
            }
        }
        val n = s.size - 1
        val meanAbs = sumAbs / n

        // ---- error de mira: hacia donde mira vs direccion hacia nosotros ----
        var aimErrSum = 0f
        for (smp in s) {
            val bearing = Math.toDegrees((-atan2(meX - smp.x, meZ - smp.z)).toDouble()).toFloat()
            aimErrSum += abs(wrap180(smp.yaw - bearing))
        }
        val meanAimErr = aimErrSum / s.size

        // ---- movimiento ----
        val first = s.first(); val last = s.last()
        val vx = (last.x - first.x) / n
        val vy = (last.y - first.y) / n
        val vz = (last.z - first.z) / n
        val toMeX = meX - last.x; val toMeZ = meZ - last.z
        val dist = hypot(toMeX, toMeZ).coerceAtLeast(0.001f)
        val lateral = abs(vx * (-toMeZ / dist) + vz * (toMeX / dist))   // componente perpendicular

        return when {
            meanAbs >= SPIN_MEAN && signChanges <= 1 && abs(net) >= meanAbs * n * 0.7f -> Style.SPINNER
            meanAbs >= JITTER_MEAN && signChanges >= 4 -> Style.JITTER
            maxAbs >= SNAP_MAX && meanAbs < SNAP_MEAN_CAP -> Style.SNAP
            vy > AIR_UP || vy < -AIR_DOWN -> Style.AIR
            lateral >= STRAFE_LATERAL && meanAimErr < 45f -> Style.STRAFER
            meanAimErr < LOCKED_ERR && meanAbs < 8f -> Style.LOCKED
            else -> Style.IDLE
        }
    }

    private fun wrap180(a: Float): Float {
        if (!a.isFinite()) return 0f
        var r = (a + 180f) % 360f
        if (r < 0f) r += 360f
        return r - 180f
    }

    companion object {
        const val DEFAULT = "FrontsX"

        /** Estilo del enemigo -> handler de KillauraRots que usamos contra el. */
        val PROFILE: Map<Style, String> = mapOf(
            Style.SPINNER to "Atomic",       // gira sin parar: apuntar al punto del hitbox mas cercano
            Style.JITTER to "Cortex",        // vaiven: prediccion con suavizado adaptativo
            Style.SNAP to "Nemesis",         // saltos de mira bruscos: seguimiento con reaccion rapida
            Style.AIR to "AirHvH Pro",       // salta / cae: prediccion con aceleracion
            Style.STRAFER to "FrontStrafe",  // se mueve de lado: orbita/persecucion
            Style.LOCKED to "Astral",        // te sigue suave: seguimiento exponencial
            Style.IDLE to "Lynx Smooth",     // no te mira: seguimiento tranquilo
            Style.UNKNOWN to DEFAULT         // pocas muestras
        )

        private const val WINDOW = 10
        private const val MIN_SAMPLES = 4
        private const val CONFIRM = 3
        private const val MIN_HOLD = 8L

        private const val SPIN_MEAN = 25f
        private const val JITTER_MEAN = 8f
        private const val SNAP_MAX = 40f
        private const val SNAP_MEAN_CAP = 12f
        private const val AIR_UP = 0.15f
        private const val AIR_DOWN = 0.35f
        private const val STRAFE_LATERAL = 0.12f
        private const val LOCKED_ERR = 20f
    }
}
