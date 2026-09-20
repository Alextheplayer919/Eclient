package com.rubidiumclient.core.schem

import org.cloudburstmc.nbt.NbtMap

/*
 * BuildBlocks.kt — FIXED VERSION. Tells the auto-builder exactly WHICH block
 * goes WHERE and whether it is safe to place / click.
 *
 * NOT COMPILED OR TESTED (no Kotlin compiler was available when writing this).
 * Every change is tagged BB-<n>; the full explanation of each is in
 * AUTOBUILD_FIXES.md.
 *
 * Java→Bedrock id conversion lives in BlockIdMap.kt (unchanged, not reviewed).
 *
 * Bedrock state keys marked VERIFY ("pillar_axis", "minecraft:vertical_half",
 * "upside_down_bit"...) must be confirmed from packet captures before
 * orientation placement is trusted (docs/AUTOBUILD.md).
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
    SIMPLE,        // one click, no meaningful state (concrete, glass, iron block...)
    AXIS,          // orientation comes from the clicked face (pillars, logs, froglights)
    AUTO_CONNECT,  // bedrock computes connections itself (walls, fences, panes, iron bars)
    ORIENTED,      // needs player yaw and/or click height (stairs, slabs, trapdoors)
    TORCH,         // standing torch: needs a support below
    PLANT_ON_TOP,  // needs solid ground below (short grass, carpets)
    TALL_PLANT,    // two blocks; place the lower half only
    SPECIAL        // by hand: interactive, exotic, wall-mounted or unmapped
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

    /**
     * BB-7: when true (default) only full-cube blocks count as clickable supports.
     * Clicking slabs/stairs/bars/trapdoors risks (a) the click point missing the
     * block's real shape and being rejected, and (b) slab-on-slab clicks merging
     * into a double slab at the SUPPORT position instead of placing at the target.
     * Turning it off places more blocks in odd builds but is less reliable.
     */
    @Volatile var strictSupport: Boolean = true

    /** Bedrock block id the server ends up with.
     * Canonical source: [BlockStateMap] (generated from GeyserMC mapping data).
     * [BlockIdMap] stays authoritative for legacy-schematic aliases, the
     * UNSUPPORTED list, and air variants. */
    fun bedrockName(s: JavaState): String? {
        if (BlockIdMap.isAirId(s.name)) return "minecraft:air"
        val legacy = BlockIdMap.toBedrockBlockId(s.name) ?: return null     // UNSUPPORTED → by-hand
        val canonical = BlockStateMap.bedrockIdOf(s.name, s.props)
        return if (canonical != s.name) canonical else legacy
    }

    /** Whether an automatic placement exists at all (unsupported → by-hand). */
    fun isPlacable(s: JavaState): Boolean = s.isAir || bedrockName(s) != null

    /** Bedrock item id the player must hold to place `s` (VERIFY per item capture). */
    fun handItem(s: JavaState): String? = BlockIdMap.toBedrockItemId(s.name)

    // ── classification ──────────────────────────────────────────────────────

    private val SPECIAL_IDS = setOf(
        "beacon", "sculk_shrieker", "flower_pot", "decorated_pot", "end_portal_frame",
        "spawner", "trial_spawner", "vault", "conduit"
    )

    /** BB-1: SIMPLE is default-DENY. A block may only be SIMPLE if every property is on this list. */
    private val BENIGN_KEYS = setOf("waterlogged", "snowy", "lit", "persistent", "distance")

    fun classify(s: JavaState): PlaceClass {
        val n = s.id
        return when {
            bedrockName(s) == null -> PlaceClass.SPECIAL
            n in SPECIAL_IDS || n.startsWith("potted_") -> PlaceClass.SPECIAL
            // BB-1: doors/beds have half=upper|lower and facing; they must never reach SIMPLE or the tall-plant rule
            n.endsWith("_door") || n.endsWith("_bed") -> PlaceClass.SPECIAL
            // BB-2: wall torches are wall-mounted; they used to be classed TORCH and placed as standing torches
            n.endsWith("wall_torch") -> PlaceClass.SPECIAL
            n.endsWith("torch") -> PlaceClass.TORCH
            // BB-6: any two-part block (half=lower|upper) that is not a door/bed
            s.props["half"] == "upper" || s.props["half"] == "lower" -> PlaceClass.TALL_PLANT
            n == "grass" || n == "short_grass" || n == "fern" || n.endsWith("_carpet") -> PlaceClass.PLANT_ON_TOP
            n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_trapdoor") -> PlaceClass.ORIENTED
            n.endsWith("_wall") || n.endsWith("_fence") || n.endsWith("_pane") || n == "iron_bars" -> PlaceClass.AUTO_CONNECT
            s.props.containsKey("axis") -> PlaceClass.AXIS
            s.props.keys.all { it in BENIGN_KEYS } -> PlaceClass.SIMPLE
            else -> PlaceClass.SPECIAL   // BB-1: unknown properties → by hand, never guess
        }
    }

    /** Facing → player yaw in degrees (0 = south/+Z, 90 = west, 180 = north, -90 = east). */
    fun yawFor(facing: String): Float = when (facing) {
        "south" -> 0f; "west" -> 90f; "north" -> 180f; "east" -> -90f; else -> 0f
    }

    /**
     * Did the server end up with what the schematic wants?
     * Block name is always checked. State keys now come from
     * [BlockStateMap.toBedrockStates] — generated Geyser data, so checking
     * them is a data-proofed equivalence (each emitted key MUST match;
     * keys the mapper doesn't emit are unconstrained). When the map has
     * nothing to say for a state the old hand rules below act as fallback.
     */
    private fun sameValue(a: Any?, b: Any?): Boolean =
        a == b || a?.toString().equals(b?.toString(), ignoreCase = true)

    fun matches(expected: JavaState, actual: BedrockBlock?): Boolean {
        if (expected.isAir) return actual == null || actual.name.endsWith(":air") || actual.name == "minecraft:air"
        if (actual == null || actual.name != bedrockName(expected)) return false
        val planned = BlockStateMap.toBedrockStates(expected.name, expected.props)
        if (planned.isNotEmpty()) {
            for ((k, v) in planned) {
                val a = actual.states[k] ?: return false
                if (!sameValue(a, v)) return false
            }
            return true
        }
        return when (classify(expected)) {
            PlaceClass.AXIS -> {
                val a = actual.states["pillar_axis"]?.toString()              // VERIFY
                a == null || a.equals(expected.props["axis"], ignoreCase = true)
            }
            PlaceClass.ORIENTED -> {
                val wantTop = expected.half() == "top"
                val slab = actual.states["minecraft:vertical_half"]?.toString()      // VERIFY (slabs — modern key)
                val legacySlab = actual.states["top_slot_bit"]                        // VERIFY (legacy slab key)
                val upside = actual.states["upside_down_bit"]                        // VERIFY (stairs, trapdoors)
                when {
                    slab != null -> (slab == "top") == wantTop
                    legacySlab != null ->
                        (legacySlab == true || legacySlab == 1 || legacySlab == "1" || legacySlab == "true") == wantTop
                    upside != null -> (upside == true || upside == 1 || upside == "true") == wantTop
                    else -> true
                }
            }
            else -> true
        }
    }

    // ── support safety (BB-7) ───────────────────────────────────────────────
    // WorldBlockTracker returns BEDROCK ids, so these sets list Bedrock spellings
    // (and the Java ones where they differ, to be safe).

    private fun shortId(id: String) = id.removePrefix("minecraft:")

    private val REPLACEABLE = setOf(
        "air", "cave_air", "void_air", "water", "flowing_water", "lava", "flowing_lava",
        "fire", "soul_fire", "snow_layer", "short_grass", "tallgrass", "tall_grass", "grass",
        "fern", "large_fern", "deadbush", "dead_bush", "vine", "glow_lichen", "seagrass"
    )

    /** Blocks a new block can simply replace (air, liquids, small plants). */
    fun isReplaceableId(id: String?): Boolean = id != null && shortId(id) in REPLACEABLE

    private val INTERACTIVE_EXACT = setOf(
        "chest", "trapped_chest", "ender_chest", "barrel", "furnace", "lit_furnace", "blast_furnace",
        "lit_blast_furnace", "smoker", "lit_smoker", "crafting_table", "anvil", "enchanting_table",
        "beacon", "lectern", "loom", "cartography_table", "smithing_table", "fletching_table",
        "grindstone", "stonecutter_block", "stonecutter", "brewing_stand", "hopper", "dropper",
        "dispenser", "crafter", "noteblock", "note_block", "jukebox", "bell", "cauldron", "composter",
        "respawn_anchor", "lever", "cake", "flower_pot", "decorated_pot", "chiseled_bookshelf",
        "unpowered_repeater", "powered_repeater", "repeater", "unpowered_comparator",
        "powered_comparator", "comparator", "daylight_detector", "daylight_detector_inverted",
        "command_block", "repeating_command_block", "chain_command_block", "structure_block",
        "jigsaw", "campfire", "soul_campfire", "end_portal_frame", "bed", "wooden_door",
        "trapdoor", "fence_gate", "wooden_button", "standing_sign", "wall_sign", "undyed_shulker_box"
    )
    private val INTERACTIVE_SUFFIX = listOf(
        "_button", "_door", "_trapdoor", "_fence_gate", "_shulker_box", "_bed", "_sign",
        "_hanging_sign", "_command_block", "_anvil"
    )

    /** Right-clicking these opens a screen or toggles them instead of placing against them. */
    fun isInteractiveId(id: String): Boolean {
        val n = shortId(id)
        if (n == "iron_door" || n == "iron_trapdoor") return false   // cannot be opened by hand
        return n in INTERACTIVE_EXACT || INTERACTIVE_SUFFIX.any { n.endsWith(it) }
    }

    private val PARTIAL_EXACT = setOf(
        "iron_bars", "chain", "ladder", "vine", "snow_layer", "lantern", "soul_lantern", "end_rod",
        "lightning_rod", "cactus", "cake", "brewing_stand", "enchanting_table", "hopper", "cauldron",
        "composter", "flower_pot", "bell", "campfire", "soul_campfire", "anvil", "conduit",
        "dragon_egg", "end_portal_frame", "glow_lichen", "sculk_vein", "scaffolding", "sea_pickle",
        "turtle_egg", "frog_spawn", "pointed_dripstone", "amethyst_cluster", "sculk_shrieker",
        "sculk_sensor", "grass_path", "dirt_path", "farmland", "candle", "trapdoor", "fence_gate"
    )
    private val PARTIAL_SUFFIX = listOf(
        "_slab", "_stairs", "_wall", "_fence", "_fence_gate", "_pane", "_carpet", "_torch", "_head",
        "_skull", "_banner", "_pressure_plate", "_rail", "_candle", "_lantern", "_sign", "_door",
        "_trapdoor", "_bed", "_button", "_anvil"
    )

    /** Not a full cube: clicking its faces can miss the real shape or merge slabs. */
    fun isPartialId(id: String): Boolean {
        val n = shortId(id)
        return n in PARTIAL_EXACT || PARTIAL_SUFFIX.any { n.endsWith(it) }
    }

    /**
     * True only for blocks we may right-click to place against.
     * `id` is what WorldBlockTracker reports (null = unknown).
     */
    fun isSafeSupport(id: String?): Boolean {
        if (id == null) return false
        if (isReplaceableId(id)) return false      // air, liquids, small plants: nothing to click
        if (isInteractiveId(id)) return false      // would open a GUI / toggle instead of placing
        if (strictSupport && isPartialId(id)) return false
        return true
    }

    // ── planning ────────────────────────────────────────────────────────────

    /** BB-4: the second half of a two-part block arrives with the first click. */
    fun isSecondHalf(s: JavaState): Boolean =
        (s.props["half"] == "upper" && (s.id.endsWith("_door") || classify(s) == PlaceClass.TALL_PLANT)) ||
            (s.id.endsWith("_bed") && s.props["part"] == "head")

    /** BB-4: a double slab needs two items. */
    fun itemCount(s: JavaState): Int = if (s.id.endsWith("_slab") && s.props["type"] == "double") 2 else 1

    /** Materials list: bedrock item id → count. */
    fun materials(states: List<JavaState>, cells: IntArray): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (c in cells) {
            if (c == SchematicModel.EMPTY) continue
            val s = states.getOrNull(c) ?: continue
            if (BlockIdMap.isAirId(s.name)) continue
            if (isSecondHalf(s)) continue
            val item = handItem(s) ?: ("UNSUPPORTED:" + s.id)
            out.merge(item, itemCount(s), Int::plus)
        }
        return out.toList().sortedByDescending { it.second }.toMap()
    }

    /** Bottom-up, each layer in a serpentine sweep so supports tend to exist first. */
    fun buildOrder(model: SchematicModel, states: List<JavaState>): IntArray {
        val out = ArrayList<Int>()
        for (y in 0 until model.height) for (z in 0 until model.length) {
            val xs = if (z % 2 == 1) (model.width - 1) downTo 0 else 0 until model.width
            for (x in xs) {
                val i = model.index(x, y, z)
                val c = model.cells[i]
                if (c == SchematicModel.EMPTY) continue
                val s = states.getOrNull(c) ?: continue
                if (BlockIdMap.isAirId(s.name)) continue
                if (isSecondHalf(s)) continue
                out.add(i)
            }
        }
        return out.toIntArray()
    }

    /**
     * Pick a face to click for the block at (x,y,z), or null if no safe support touches it yet.
     * `isSupport` must be true only for blocks that exist, are solid, and are safe to
     * right-click — use [isSafeSupport].
     */
    fun planPlacement(
        s: JavaState, x: Int, y: Int, z: Int,
        isSupport: (Int, Int, Int) -> Boolean
    ): PlaceRequest? {
        val wantTop = s.half() == "top"
        val faces: List<Face> = when (classify(s)) {
            // BB-3: axis is decided by the clicked face; x and z used to return no faces at all
            PlaceClass.AXIS -> when (s.props["axis"]) {
                "y" -> listOf(Face.UP, Face.DOWN)
                "x" -> listOf(Face.WEST, Face.EAST)
                "z" -> listOf(Face.NORTH, Face.SOUTH)
                else -> emptyList()
            }
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
 *
 * BB-5 changes:
 *  - keeps a set of in-flight (SENT) cells so per-tick work is O(in-flight),
 *    not O(every cell in the model);
 *  - [markRetry] sends a cell back for another try WITHOUT counting a second
 *    attempt (the old "backdate the tick" trick double-counted attempts, so
 *    cells got fewer retries than MAX_ATTEMPTS says);
 *  - [expire] returns how many sends timed out so the caller can count them
 *    as failures.
 *
 * Not thread-safe: AutoBuilder serialises access.
 */
class PlacementTracker(size: Int) {
    val status = Array(size) { CellStatus.PENDING }
    private val attempts = ByteArray(size)
    private val sentTick = IntArray(size)
    private val sent = LinkedHashSet<Int>()

    /** Number of sends still waiting for server confirmation. */
    val inFlight: Int get() = sent.size

    fun sentCells(): IntArray = sent.toIntArray()

    fun markSent(i: Int, tick: Int) {
        status[i] = CellStatus.SENT
        sentTick[i] = tick
        attempts[i] = (attempts[i] + 1).toByte()
        sent.add(i)
    }

    fun markDone(i: Int) { status[i] = CellStatus.DONE; sent.remove(i) }

    /** Terminal failure NOW (e.g. obstructed, or state mismatch — do NOT auto-retry). */
    fun markFailed(i: Int) { status[i] = CellStatus.FAILED; sent.remove(i) }

    /** Back to PENDING for another try, or FAILED when attempts are used up. */
    fun markRetry(i: Int) {
        sent.remove(i)
        status[i] = if (attempts[i] >= MAX_ATTEMPTS) CellStatus.FAILED else CellStatus.PENDING
    }

    /** Call every tick. Returns how many unconfirmed sends timed out (each is a failure). */
    fun expire(tick: Int, timeoutTicks: Int = 20): Int {
        var n = 0
        for (i in sent.toIntArray()) {
            if (tick - sentTick[i] > timeoutTicks) { markRetry(i); n++ }
        }
        return n
    }

    fun count(s: CellStatus) = status.count { it == s }

    companion object { const val MAX_ATTEMPTS = 3 }
}
