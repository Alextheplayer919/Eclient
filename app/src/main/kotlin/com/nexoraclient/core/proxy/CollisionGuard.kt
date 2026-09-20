package com.rubidiumclient.core.proxy

import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.ceil
import kotlin.math.floor

/**
 * CollisionGuard — makes injected fly motion block-collision-consistent.
 *
 * Research basis (round-3, oomph reference validator + live report): when fly
 * speed carries the player into solid blocks (chorus plants, trees, walls),
 * the real client collides and stops while the injected velocity kept
 * claiming forward — the sim-vs-claim divergence spikes and corrections/flags
 * follow. Since the client's own collision resolves against the world anyway,
 * the clean fix upstream: pre-clamp the injected motion against the proxied
 * chunk data so our motion REQUESTS never demand a path the world forbids.
 *
 * Result: wall-slam becomes wall-slide, ceiling-slam becomes hover-stop —
 * same mechanical shape as vanilla collision response (axis-separated,
 * Y then X then Z), so no new motion signature is introduced.
 *
 * Fail-open everywhere: no terrain data → motion passes untouched; unknown
 * cells → treated as penetrable. The guard can only *reduce* motion, never
 * add or redirect it.
 *
 * Frame note (E5, resolved here): EntityTracker.selfY is feet-frame on
 * MovePlayer-flavor servers but eye-frame (+1.62) on V3/AuthInput servers.
 * selfYFrameIsEye tells us which one the current session feeds.
 */
object CollisionGuard {

    @Volatile var enabled = false   // driven by NoLagback's "Collision-Glide Guard" setting

    private const val HALF_W  = 0.29f   // player half-width (0.3) minus graze margin
    private const val HEIGHT  = 1.79f   // body height minus head graze margin
    private const val STEP    = 0.3f    // max probe distance per sample (no tunneling)
    private const val EYE     = 1.62f   // standing eye offset; sneak error (±0.35) absorbed by margins

    /** Blocks we may legally stand inside of / pass through. Deliberately
     *  EXACT identifiers — chorus_plant / chorus_flower are NOT here, they
     *  have real collision (the reported flag case). Conservative by design:
     *  anything not listed counts as solid. */
    private val PENETRABLE = setOf(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:water", "minecraft:flowing_water",
        "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:fire", "minecraft:soul_fire",
        "minecraft:short_grass", "minecraft:tall_grass", "minecraft:tallgrass",
        "minecraft:fern", "minecraft:large_fern", "minecraft:seagrass",
        "minecraft:cobweb", "minecraft:snow_layer",
        "minecraft:vine", "minecraft:ladder"
    )

    /** Current feet-frame Y regardless of which packet channel feeds the tracker. */
    fun feetY(): Float =
        if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY - EYE else EntityTracker.selfY

    fun available(): Boolean = enabled && WorldBlockTracker.hasAnyTerrainData()

    private fun cellFree(bx: Int, by: Int, bz: Int): Boolean {
        val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: return true // no data → fail-open
        return PENETRABLE.contains(id)
    }

    private fun boxFree(x: Float, fy: Float, z: Float): Boolean {
        val bx0 = floor((x - HALF_W).toDouble()).toInt(); val bx1 = floor((x + HALF_W).toDouble()).toInt()
        val by0 = floor(fy.toDouble()).toInt();           val by1 = floor((fy + HEIGHT).toDouble()).toInt()
        val bz0 = floor((z - HALF_W).toDouble()).toInt(); val bz1 = floor((z + HALF_W).toDouble()).toInt()
        for (bx in bx0..bx1) for (by in by0..by1) for (bz in bz0..bz1) {
            if (!cellFree(bx, by, bz)) return false
        }
        return true
    }

    /**
     * Clamp a requested per-tick motion (mx, my, mz) so the swept player box
     * never enters a solid block. Position (x, feetY, z) is the current feet
     * position. Returns the ALLOWED motion — same vanilla axis-separated
     * response shape: blocked axes shrink to the last free point, free axes
     * pass untouched (wall-slide / ceiling hover-stop / floor soft-land).
     */
    fun clampedMotion(x: Float, feetY: Float, z: Float, mx: Float, my: Float, mz: Float): Vector3f {
        if (!available()) return Vector3f.from(mx, my, mz)
        if (mx == 0f && my == 0f && mz == 0f) return Vector3f.from(0f, 0f, 0f)

        var px = x; var py = feetY; var pz = z

        // Vanilla collision order: Y, then X, then Z.
        py = sweep(py, my) { ny -> boxFree(px, ny, pz) }
        px = sweep(px, mx) { nx -> boxFree(nx, py, pz) }
        pz = sweep(pz, mz) { nz -> boxFree(px, py, nz) }

        return Vector3f.from(px - x, py - feetY, pz - z)
    }

    private inline fun sweep(pos: Float, delta: Float, free: (Float) -> Boolean): Float {
        if (delta == 0f) return pos
        val n = ceil((if (delta > 0) delta else -delta) / STEP).toInt().coerceIn(1, 32)
        val step = delta / n
        var last = pos
        for (i in 1..n) {
            val cand = pos + step * i
            if (free(cand)) last = cand else break
        }
        return last
    }
}
