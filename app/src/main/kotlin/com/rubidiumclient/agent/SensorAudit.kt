package com.rubidiumclient.agent

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Ground-truth audit of the proxy's own self-state (docs/HYBRID_LITE.md, "Layer 0").
 *
 * The packet path INFERS where the player is. The in-game agent READS it. In hybrid-lite the
 * agent never feeds the tracker (that would be two writers on one map); instead it is used as
 * a referee: every state push is compared with what the tracker currently believes, and the
 * differences are turned into evidence — most importantly, which height frame the tracker's Y
 * is really in.
 *
 * Why that matters: the ESP projection used to add a fixed 1.62 to the tracker's Y, assuming it
 * was the feet height, while the tracker documents that PlayerAuthInput positions are already
 * the EYE height. If that is true the camera is 1.62 blocks too high. This class measures it:
 *
 *     tracker.Y − agent.Y   ≈ 1.62  while the tracker claims "eye frame"   → eye frame CONFIRMED
 *     tracker.Y − agent.Y   ≈ 0     while the tracker claims "eye frame"   → the flag LIES (tracker Y is feet)
 *
 * (the agent reports the entity position, i.e. the FEET; `groundFracMedian` is a cross-check of
 * that assumption — standing on a full block the feet height is a whole number.)
 *
 * Pure: no Android, no tracker, no clock unless injected. Single writer (the agent reader
 * thread); [summary] may be called from any thread.
 */
class SensorAudit(
    private val log: (String) -> Unit = {},
    private val logEveryMs: Long = 5_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** What the measured Y offset says about the tracker's height frame. */
    enum class FrameVerdict { UNKNOWN, EYE, FEET, OTHER }

    private class Ring(private val cap: Int) {
        private val a = DoubleArray(cap)
        var n = 0; private set
        private var i = 0
        fun add(v: Double) { a[i] = v; i = (i + 1) % cap; if (n < cap) n++ }
        fun sorted(): DoubleArray = a.copyOf(n).also { it.sort() }
        fun median(): Double? = sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        fun percentile(p: Double): Double? = sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * p).toInt()] }
    }

    private val dyEyeClaim = Ring(RING)
    private val dyFeetClaim = Ring(RING)
    private val dxz = Ring(RING)
    private val groundFrac = Ring(RING)
    private var samples = 0L
    private var lastLogAt = 0L

    /** Verdict for samples where the tracker said "my Y is the eye height". */
    @Volatile var eyeClaimVerdict: FrameVerdict = FrameVerdict.UNKNOWN; private set

    /** Verdict for samples where the tracker said "my Y is the feet height". */
    @Volatile var feetClaimVerdict: FrameVerdict = FrameVerdict.UNKNOWN; private set

    /** True once the tracker's eye-frame claim has been measured and is correct. */
    val eyeFrameConfirmed: Boolean get() = eyeClaimVerdict == FrameVerdict.EYE

    /** True when a frame flag has been measured to be wrong (worth shouting about). */
    val flagContradicted: Boolean
        get() = eyeClaimVerdict == FrameVerdict.FEET || feetClaimVerdict == FrameVerdict.EYE

    @Synchronized
    fun onSample(
        agentX: Double, agentY: Double, agentZ: Double, agentOnGround: Boolean,
        trackerX: Float, trackerY: Float, trackerZ: Float, trackerClaimsEyeFrame: Boolean,
    ) {
        // Before the first packet the tracker sits at (0,0,0): nothing to compare yet.
        if (trackerX == 0f && trackerY == 0f && trackerZ == 0f) return
        if (!agentX.isFinite() || !agentY.isFinite() || !agentZ.isFinite()) return

        samples++
        val dy = trackerY - agentY
        if (trackerClaimsEyeFrame) dyEyeClaim.add(dy) else dyFeetClaim.add(dy)
        dxz.add(hypot(trackerX - agentX, trackerZ - agentZ))
        if (agentOnGround) groundFrac.add(agentY - floor(agentY))

        eyeClaimVerdict = verdictOf(dyEyeClaim)
        feetClaimVerdict = verdictOf(dyFeetClaim)

        val now = clock()
        if (now - lastLogAt >= logEveryMs && samples >= MIN_SAMPLES) {
            lastLogAt = now
            log(summaryLocked())
            if (flagContradicted) log("WARNING: the tracker's eye/feet frame flag contradicts the agent — see dy above")
        }
    }

    @Synchronized
    fun summary(): String = summaryLocked()

    private fun verdictOf(ring: Ring): FrameVerdict {
        if (ring.n < MIN_SAMPLES) return FrameVerdict.UNKNOWN
        val m = ring.median() ?: return FrameVerdict.UNKNOWN
        return when {
            abs(m - EYE_HEIGHT) <= TOLERANCE -> FrameVerdict.EYE
            abs(m) <= TOLERANCE -> FrameVerdict.FEET
            else -> FrameVerdict.OTHER
        }
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.2f", v)

    private fun summaryLocked(): String {
        val eye = "eye-claim dy=${fmt(dyEyeClaim.median())} (n=${dyEyeClaim.n} → $eyeClaimVerdict)"
        val feet = "feet-claim dy=${fmt(dyFeetClaim.median())} (n=${dyFeetClaim.n} → $feetClaimVerdict)"
        val lag = "xz p50=${fmt(dxz.median())} p95=${fmt(dxz.percentile(0.95))}"
        val gf = "agentGroundFrac=${fmt(groundFrac.median())} (n=${groundFrac.n})"
        return "audit samples=$samples | $eye | $feet | $lag | $gf"
    }

    companion object {
        const val EYE_HEIGHT = 1.62
        const val TOLERANCE = 0.15
        const val MIN_SAMPLES = 30
        private const val RING = 64
    }
}
