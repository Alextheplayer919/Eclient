package com.rubidiumclient.core.schem

import org.cloudburstmc.nbt.NbtMap

/*
 * BuildBlocks.kt — tells the auto-builder exactly WHICH block goes WHERE.
 *
 * Java→Bedrock id conversion lives in BlockIdMap.kt; this file holds the
 * placement *planning* knowledge: block classes, face/click-point selection,
 * ordering, per-cell tracking and server-feedback matching.
 *
 * Status of facts inside:
 *  - Proven in-source: face ids, edition-renames table (BlockIdMap), package flow.
 *  - Marked VERIFY: bedrock state-key names ("pillar_axis", "minecraft:vertical_half",
 *    "upside_down_bit"...) — confirmed from Phase-0 packet captures before
 *    enabling orientation placement (docs/AUTOBUILD.md §4).
 */

/** One full Java block state exactly as stored in a .litematic/.schem palette. */
data class JavaState(val name: String, val props: Map<String, String> = emptyMap()) {

    val isAir: Boolean get() = name.endsWith(":air")
    val id: String get() = name.removePrefix("minecraft:")

    /** "top"/"bottom" for stairs, trapdoors and slabs; null where not applicable. */
    fun half(): String? = props["half"] ?: props["type"]?.takeIf { it == "top" || it == "bottom" }

    companion object {
        val AIR = JavaState("minecraft:air")

        fun fromNbt(entry: NbtMap?): JavaState {
            val name = entry?.getString("Name") ?: return AIR
            val out = LinkedHashMap<String, String>()
            val p = entry.getCompound("Properties")
            if (p != null) for (k in p.keys) out[k] = p.getString(k) ?: continue
            return JavaState(name, out)
        }

        /** Parses schem-style palette keys: "minecraft:oak_stairs[facing=east,half=top]". */
        fun parseKeyed(key: String): JavaState {
            val i = key.indexOf('[')
            if (i < 0 || !key.endsWith("]")) return JavaState(key)
            val out = LinkedHashMap<String, String>()
            for (pair in key.substring(i + 1, key.length - 1).split(',')) {
                val eq = pair.indexOf('=')
                if (eq > 0) out[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            return JavaState(key.substring(0, i), out)
        }
    }
}

/** Bedrock face ids (the same numbering everywhere bedrock encodes block faces). */
enum class Face(val id: Int, val dx: Int, val dy: Int, val dz: Int) {
    DOWN(0, 0, -1, 0), UP(1, 0, 1, 0), NORTH(2, 0, 0, -1),
    SOUTH(3, 0, 0, 1), WEST(4, -1, 0, 0), EAST(5, 1, 0, 0)
}

/** How hard a block is to place correctly. Drives the autobuild phase plan. */
enum class PlaceClass {
    SIMPLE,        // one click, no state (concrete, glass, iron block...)
    AXIS,          // orientation comes from the clicked face (pillars, froglights)
    AUTO_CONNECT,  // bedrock computes connections itself (walls, iron bars)
    ORIENTED,      // needs player yaw and/or click height (stairs, slabs, trapdoors)
    TORCH,         // needs a support face
    PLANT_ON_TOP,  // needs solid ground below (short grass, moss carpet)
    TALL_PLANT,    // two blocks; place the lower half only
    SPECIAL        // interactive or exotic (beacon, sculk shrieker, flower pots, unsupported)
}

/** What the server reports back for a block (filled from WorldBlockTracker). */
data class BedrockBlock(val name: String, val states: Map<String, Any?> = emptyMap())

fun interface WorldReader { fun blockAt(x: Int, y: Int, z: Int): BedrockBlock? }

/** One planned placement: click `face` of the block at `support`; the new block appears at `target`. */
data class PlaceRequest(
    val target: Triple<Int, Int, Int>,
    val support: Triple<Int, Int, Int>,
    val face: Face,
    /** Offset inside the clicked block, 0..1 per axis (bedrock click position is block-relative). */
    val click: Triple<Float, Float, Float>
)

object BlockMapper {

    /** Bedrock block id the server ends up with (BlockIdMap), null for unsupported. */
    fun bedrockName(s: JavaState): String? = BlockIdMap.toBedrockBlockId(s.name)

    /** Whether an automatic placement exists at all (unsupported → by-hand). */
    fun isPlacable(s: JavaState): Boolean = s.isAir || bedrockName(s) != null

    /** Bedrock item id the player must hold to place `s` (VERIFY per item capture). */
    fun handItem(s: JavaState): String? = BlockIdMap.toBedrockItemId(s.name)

    fun classify(s: JavaState): PlaceClass {
        val n = s.id
        return when {
            BlockIdMap.toBedrockBlockId(s.name) == null -> PlaceClass.SPECIAL
            n == "beacon" || n == "sculk_shrieker" -> PlaceClass.SPECIAL
            n == "flower_pot" || n.startsWith("potted_") -> PlaceClass.SPECIAL
            s.props["half"] == "upper" && s.props.containsKey("half") && n != "dragon_egg" -> PlaceClass.TALL_PLANT
            n == "tall_grass" || n == "large_fern" || n in TALL_FLOWERS -> PlaceClass.TALL_PLANT
            n == "grass" || n == "short_grass" || n == "moss_carpet" -> PlaceClass.PLANT_ON_TOP
            n.endsWith("torch") -> PlaceClass.TORCH
            n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_trapdoor") -> PlaceClass.ORIENTED
            n.endsWith("_wall") || n == "iron_bars" -> PlaceClass.AUTO_CONNECT
            s.props.containsKey("axis") -> PlaceClass.AXIS
            else -> PlaceClass.SIMPLE
        }
    }

    private val TALL_FLOWERS = setOf("sunflower", "lilac", "rose_bush", "peony")

    /** Facing → player yaw in degrees (0 = south/+Z, 90 = west, 180 = north, -90 = east). */
    fun yawFor(facing: String): Float = when (facing) {
        "south" -> 0f; "west" -> 90f; "north" -> 180f; "east" -> -90f; else -> 0f
    }

    /**
     * Did the server end up with what the schematic wants?
     * Block name is always checked (via BlockIdMap), state keys only where
     * they're VERIFY-flagged — a missing key counts as "can't tell", not a mismatch.
     */
    fun matches(expected: JavaState, actual: BedrockBlock?): Boolean {
        if (expected.isAir) return actual == null || actual.name.endsWith(":air") || actual.name == "minecraft:air"
        if (actual == null || actual.name != bedrockName(expected)) return false
        return when (classify(expected)) {
            PlaceClass.AXIS -> {
                val a = actual.states["pillar_axis"]?.toString()              // VERIFY
                a == null || a.equals(expected.props["axis"], ignoreCase = true)
            }
            PlaceClass.ORIENTED -> {
                val wantTop = expected.half() == "top"
                val slab = actual.states["minecraft:vertical_half"]?.toString()      // VERIFY (slabs)
                val upside = actual.states["upside_down_bit"]                        // VERIFY (stairs, trapdoors)
                when {
                    slab != null -> (slab == "top") == wantTop
                    upside != null -> (upside == true || upside == 1 || upside == "true") == wantTop
                    else -> true
                }
            }
            else -> true
        }
    }

    // ── planning ────────────────────────────────────────────────────────────

    /** Materials list: bedrock item id → count. Tall plants counted once (lower half). */
    fun materials(states: List<JavaState>, cells: IntArray): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (c in cells) {
            if (c == SchematicModel.EMPTY) continue
            val s = states.getOrNull(c) ?: continue
            if (BlockIdMap.isAirId(s.name)) continue
            if (classify(s) == PlaceClass.TALL_PLANT && secHalfOnly(s)) continue   // count lower half only
            val item = handItem(s) ?: "UNSUPPORTED:" + s.id
            out.merge(item, 1, Int::plus)
        }
        return out.toList().sortedByDescending { it.second }.toMap()
    }

    /** Bottom-up, each layer in a serpentine sweep so supports tend to exist first. */
    fun buildOrder(model: SchematicModel, states: List<JavaState>): IntArray {
        val out = ArrayList<Int>()
        for (y in 0 until model.height) for (zi in 0 until model.length) {
            val z = zi
            val xs = when { z % 2 == 1 -> (model.width - 1) downTo 0; else -> 0 until model.width }
            for (x in xs) {
                val i = model.index(x, y, z)
                val c = model.cells[i]
                if (c == SchematicModel.EMPTY) continue
                val s = states.getOrNull(c) ?: continue
                if (BlockIdMap.isAirId(s.name)) continue
                if (classify(s) == PlaceClass.TALL_PLANT && secHalfOnly(s)) continue
                out.add(i)
            }
        }
        return out.toIntArray()
    }

    /** skip the upper half of tall blocks (they arrive with the lower click) */
    private fun secHalfOnly(s: JavaState) =
        s.props["half"] == "upper" && classify(s) != PlaceClass.SPECIAL

    /**
     * Pick a face to click for the block at (x,y,z), or null if no safe support touches it yet.
     * `isSupport` must be true only for blocks that exist, are solid, and are safe to
     * right-click (NOT air, liquid, plants, or anything that opens a screen).
     */
    fun planPlacement(
        s: JavaState, x: Int, y: Int, z: Int,
        isSupport: (Int, Int, Int) -> Boolean
    ): PlaceRequest? {
        val wantTop = s.half() == "top"
        val faces: List<Face> = when (classify(s)) {
            PlaceClass.AXIS -> if (s.props["axis"] == "y") listOf(Face.UP, Face.DOWN) else emptyList()
            PlaceClass.TORCH, PlaceClass.PLANT_ON_TOP, PlaceClass.TALL_PLANT -> listOf(Face.UP)
            PlaceClass.ORIENTED ->
                if (wantTop) listOf(Face.DOWN, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)
                else listOf(Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)
            PlaceClass.SPECIAL -> emptyList()
            else -> listOf(Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST, Face.DOWN)
        }
        if (faces.isEmpty()) return null
        for (f in faces) {
            val sx = x - f.dx; val sy = y - f.dy; val sz = z - f.dz
            if (!isSupport(sx, sy, sz)) continue
            return PlaceRequest(Triple(x, y, z), Triple(sx, sy, sz), f, clickPoint(f, wantTop))
        }
        return null
    }

    private fun clickPoint(f: Face, top: Boolean): Triple<Float, Float, Float> {
        val h = if (top) 0.75f else 0.25f     // click the upper/lower half of side faces
        return when (f) {
            Face.UP -> Triple(0.5f, 1f, 0.5f)
            Face.DOWN -> Triple(0.5f, 0f, 0.5f)
            Face.NORTH -> Triple(0.5f, h, 0f)
            Face.SOUTH -> Triple(0.5f, h, 1f)
            Face.WEST -> Triple(0f, h, 0.5f)
            Face.EAST -> Triple(1f, h, 0.5f)
        }
    }
}

enum class CellStatus { PENDING, SENT, DONE, FAILED }

/**
 * Per-cell bookkeeping so the builder never double-places or loops forever.
 * PENDING → SENT (on send) → DONE (server confirmed) or back to PENDING (retry)
 * → FAILED after MAX_ATTEMPTS. Index = the same flat cell index as the model.
 */
class PlacementTracker(size: Int) {
    val status = Array(size) { CellStatus.PENDING }
    private val attempts = ByteArray(size)
    private val sentTick = IntArray(size)

    fun markSent(i: Int, tick: Int) { status[i] = CellStatus.SENT; sentTick[i] = tick; attempts[i]++ }
    fun markDone(i: Int) { status[i] = CellStatus.DONE }

    /** Call every tick: unconfirmed sends past the timeout go back to PENDING, or FAILED. */
    fun expire(tick: Int, timeoutTicks: Int = 20) {
        for (i in status.indices) {
            if (status[i] == CellStatus.SENT && tick - sentTick[i] > timeoutTicks) {
                status[i] = if (attempts[i] >= MAX_ATTEMPTS) CellStatus.FAILED else CellStatus.PENDING
            }
        }
    }

    fun count(s: CellStatus) = status.count { it == s }

    companion object { const val MAX_ATTEMPTS = 3 }
}
