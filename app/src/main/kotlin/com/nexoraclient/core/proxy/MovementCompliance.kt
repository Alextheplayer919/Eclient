package com.rubidiumclient.core.proxy

import android.content.Context
import android.content.SharedPreferences
import com.rubidiumclient.RubidiumClientApp
import java.util.ArrayDeque

/**
 * MovementCompliance — the proxy's model of the server's movement authority.
 *
 * Research basis (vanilla BDS server.properties):
 *   player-movement-distance-threshold = 0.3   (blocks of desync per anomaly)
 *   player-movement-score-threshold    = 20    (anomalies before action)
 *   player-movement-duration-threshold = 500ms (sustained-desync window)
 *
 * Meaning: the server only ACTS after sustained >0.3-block disagreement — it
 * runs a budget, not a per-tick court. So the strong anti-lagback is not
 * hiding corrections (client drifts, anomalies accumulate, flagged faster),
 * it is a GOVERNOR: read correction pressure, shrink speed before the next
 * anomaly window, and perform rare deliberate micro-resyncs that zero the
 * drift instead of letting it compound.
 *
 * This object holds:
 *   serverBelief  — where the server last told us we are (corrections &
 *                   server teleports). Stale after a timeout; != truth.
 *   pressure      — 0..1 multiplier raised by recent corrections (x0.8 each),
 *                   recovering over quiet time. MotionFly multiplies its
 *                   speed ceilings by it.
 *   correction log — timestamps for rate calculations.
 *
 * Fed by NoLagback (S2C packet listener), consumed by MotionFly (governor).
 */
object MovementCompliance {

    private const val BELIEF_TTL_MS  = 10_000L
    private const val CORR_WINDOW_MS = 1000L
    private const val PRESSURE_HIT   = 0.8f
    private const val PRESSURE_RECOVER_PER_MS = 0.00025f // full recovery ≈ 800ms quiet

    @Volatile var adaptive = false          // NoLagback ADAPTIVE mode drives the governor

    @Volatile var beliefX = 0f
    @Volatile var beliefY = 0f
    @Volatile var beliefZ = 0f
    @Volatile var beliefFresh = false
    @Volatile var beliefStampMs = 0L
    @Volatile var lastCorrectionMs = 0L

    @Volatile private var pressureRaw = 1f
    @Volatile private var pressureStampMs = 0L

    private val corrTimes = ArrayDeque<Long>()

    private fun nowMs() = System.currentTimeMillis()

    /** A correction (or hard reset) arrived: anchor belief + raise pressure. */
    fun noteCorrection(x: Float, y: Float, z: Float) {
        beliefX = x; beliefY = y; beliefZ = z
        beliefFresh = true
        beliefStampMs = nowMs()
        lastCorrectionMs = beliefStampMs
        synchronized(corrTimes) {
            corrTimes.addLast(lastCorrectionMs)
            trimOld()
        }
        applyPressure(PRESSURE_HIT)
    }

    /** Legit server teleport (MOVE TELEPORT of self): re-anchor, no pressure. */
    fun noteServerTeleport(x: Float, y: Float, z: Float) {
        beliefX = x; beliefY = y; beliefZ = z
        beliefFresh = true
        beliefStampMs = nowMs()
    }

    /** Called (by NoLagback) whenever the local side voluntarily resynced. */
    fun noteLocalResync() {
        beliefFresh = false
        synchronized(corrTimes) { corrTimes.clear() }
    }

    fun correctionsInLast(ms: Long): Int {
        synchronized(corrTimes) {
            trimOld()
            val cut = nowMs() - ms
            var n = 0
            for (t in corrTimes) if (t >= cut) n++
            return n
        }
    }

    /** Pressure multiplier with time-based recovery, <= 1f. */
    fun pressure(): Float {
        val t = nowMs()
        val dt = t - pressureStampMs
        if (dt > 0) {
            pressureRaw = (pressureRaw + dt * PRESSURE_RECOVER_PER_MS).coerceAtMost(1f)
            pressureStampMs = t
        }
        return pressureRaw
    }

    fun ageOfLastCorrectionMs(): Long = if (lastCorrectionMs == 0L) Long.MAX_VALUE else nowMs() - lastCorrectionMs

    /** Distance from tracked self position to the server's believed position;
     *  negative if we have no fresh anchor. */
    fun discrepancy(): Float {
        if (!beliefFresh) return -1f
        if (nowMs() - beliefStampMs > BELIEF_TTL_MS) { beliefFresh = false; return -1f }
        val dx = EntityTracker.selfX - beliefX
        val dy = EntityTracker.selfY - beliefY
        val dz = EntityTracker.selfZ - beliefZ
        return kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun applyPressure(hit: Float) {
        pressure() // apply recovery first so hits compose on a fresh base
        pressureRaw = (pressureRaw * hit).coerceAtLeast(0.05f)
        if (adaptive) learnPunish()
    }

    // ── v2.2 per-server calibration ────────────────────────────────────────
    // Learn the highest speed factor a given server tolerates and REMEMBER it:
    // quiet ticks creep the ceiling up, corrections slash and persist it.
    // Next join starts at the learned value instead of re-negotiating from 1.0.
    private const val CALIB_PREFS = "nolagback_calib"
    private const val CEIL_DEFAULT = 0.6f
    private const val CEIL_MIN = 0.20f
    private const val CEIL_CREEP = 0.0002f  // per governedSpeed call in a quiet zone

    @Volatile private var ceiling = CEIL_DEFAULT
    @Volatile private var serverKey = ""
    private var calibPrefs: SharedPreferences? = null

    fun onSessionStart(host: String) {
        serverKey = host
        calibPrefs = runCatching {
            RubidiumClientApp.instance.getSharedPreferences(CALIB_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        ceiling = calibPrefs?.getFloat("ceil_${host}", CEIL_DEFAULT) ?: CEIL_DEFAULT
        pressureRaw = ceiling          // don't re-negotiate the server from scratch
        pressureStampMs = nowMs()
    }

    fun onSessionEnd() {
        persistCeiling()
        serverKey = ""
        beliefFresh = false
    }

    private fun persistCeiling() {
        if (serverKey.isEmpty()) return
        runCatching { calibPrefs?.edit()?.putFloat("ceil_${serverKey}", ceiling)?.apply() }
    }

    private fun learnQuiet() {
        ceiling = (ceiling + CEIL_CREEP).coerceAtMost(1f)
    }

    private fun learnPunish() {
        ceiling = (ceiling * 0.85f).coerceAtLeast(CEIL_MIN)
        persistCeiling()
    }

    // ── Governor public API (shared by every fly module) ──────────────────

    /**
     * Applies the full compliance stack to a base speed: correction pressure,
     * live drift from the server's believed position, and the learned
     * per-server ceiling. Quiet zones creep the ceiling back up. Modules call
     * this on every speed value they're about to emit.
     */
    fun governedSpeed(base: Float): Float {
        if (!adaptive) return base
        var scale = pressure()
        val d = discrepancy()
        if (d >= 0f) scale *= 0.30f / (0.30f + d)
        if (correctionsInLast(2000) == 0 && (d < 0.15f || d < 0f)) learnQuiet()
        return base * minOf(scale, ceiling)
    }

    /** True during the post-correction settle window — suppress positive Y. */
    fun shouldSettleVertical(): Boolean =
        adaptive && ageOfLastCorrectionMs() < 600L

    private fun trimOld() {
        val cut = nowMs() - 10_000L
        while (true) {
            val h = corrTimes.peekFirst() ?: break
            if (h < cut) corrTimes.removeFirst() else break
        }
    }
}
