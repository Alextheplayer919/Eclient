package com.rubidiumclient.core.schem

/**
 * BlockIdMap — Java→Bedrock block-id converter, the foundation of both
 * Java-schematic support and the auto-builder.
 *
 * Every Java block id resolves to a Bedrock placement target by this rule:
 *
 *   1. AIR variants → "minecraft:air" (filtered before lookups anyway).
 *   2. RENAME        → explicit curated alias (table below).
 *   3. UNSUPPORTED   → null placement target; the auto-builder must treat the
 *                      cell as SPECIAL (place by hand), never substitute.
 *   4. everything else → IDENTITY (same namespaced id on both editions).
 *
 * Curation method (no guessing):
 *   - The Java id set is the full 1.16.70-free 26.1 registry (1,168 blocks,
 *     minecraft-data data/pc/26.1).
 *   - Renames come from GeyserMC's *live* mapping file (GeyserMC/mappings
 *     blocks.nbt: the identifiers their translator physically sends to
 *     Bedrock clients) — e.g. dirt_with_roots, quartz_ore, magma, snow_layer,
 *     wooden_door, fence_gate, golden_rail, waterlily, silver_glazed_terracotta,
 *     standing/wall banner + standing signs, skull, bed, reeds, trip_wire,
 *     grass_path, mob_spawner, frog_spawn, lit_pumpkin, normal_stone_stairs.
 *   - Everything marked // VERIFY is confirmed-or-corrected during the
 *     auto-builder's Phase-0 packet captures (see docs/AUTOBUILD.md §4).
 *
 * IMPORTANT — ghost rendering never touches this map. Ghosts use palette
 * names as-is. This converter exists for placement/materials only, so it is
 * free to be conservative: when in doubt, cells route to by-hand (SPECIAL)
 * instead of placing a guessed block.
 *
 * Structure/orientation state translation is a *separate* phase-2 problem
 * (double slabs, lit furnaces, repeater power state, wall attachments, axis);  Geyser's blocks.nbt holds the full
 * state-level mapping and is the certified source for that table.
 */
object BlockIdMap {

    // ── 2. Explicit renames (bare ids, no prefix) ───────────────────────────

    private val RENAME: Map<String, String> = mapOf(
        // name changes, verified in Geyser mapping extract
        "rooted_dirt"                  to "dirt_with_roots",
        "nether_quartz_ore"            to "quartz_ore",
        "magma_block"                  to "magma",
        "snow"                         to "snow_layer",
        "slime_block"                  to "slime",
        "stonecutter"                  to "stonecutter_block",
        "note_block"                   to "noteblock",
        "oak_trapdoor"                 to "trapdoor",
        "oak_door"                     to "wooden_door",
        "oak_button"                   to "wooden_button",
        "oak_pressure_plate"           to "wooden_pressure_plate",
        "oak_fence_gate"               to "fence_gate",
        "powered_rail"                 to "golden_rail",
        "light_gray_glazed_terracotta" to "silver_glazed_terracotta",
        "lily_pad"                     to "waterlily",
        "nether_bricks"                to "nether_brick",
        "red_nether_bricks"            to "red_nether_brick",
        "prismarine_brick_stairs"      to "prismarine_bricks_stairs",
        "jack_o_lantern"               to "lit_pumpkin",
        "sugar_cane"                   to "reeds",
        "beetroots"                    to "beetroot",
        "tripwire"                     to "trip_wire",
        "dirt_path"                    to "grass_path",
        "spawner"                      to "mob_spawner",
        "frogspawn"                    to "frog_spawn",
        "light"                        to "light_block",
        "piston_head"                  to "piston_arm_collision",
        "sunflower"                    to "double_plant",            // VERIFY (double_plant[type]+upper_bit) — tall flowers collapse on bedrock
        "lilac"                        to "double_plant",            // VERIFY
        "rose_bush"                    to "double_plant",            // VERIFY
        "peony"                        to "double_plant",            // VERIFY

        // wall-attachment blocks → base id (attachment is block state on bedrock)
        "wall_torch"                   to "torch",
        "redstone_wall_torch"          to "redstone_torch",
        "soul_wall_torch"              to "soul_torch",
        "attached_melon_stem"          to "melon_stem",
        "attached_pumpkin_stem"        to "pumpkin_stem",
        "cave_vines_plant"             to "cave_vines",
        "kelp_plant"                   to "kelp",
        "twisting_vines_plant"         to "twisting_vines",
        "weeping_vines_plant"          to "weeping_vines",
        "cave_vines"                   to "cave_vines_head_with_berries",   // VERIFY (tip w/ berries vs body chains)

        // foliage with distinct bedrock names
        "grass"                        to "short_grass",     // pre-1.20.3 java files; short_grass is canonical on BOTH now
        "flowering_azalea_leaves"      to "azalea_leaves_flowered",
        "waxed_copper_block"           to "waxed_copper",    // VERIFY (other waxed_* keep their java names; copper_base id got the short name — from Geyser extract)

        // bedrock coral fan placement ids (VERIFY exact hang order: tube/brain/bubble)
        "tube_coral_wall_fan"          to "coral_fan_hang",
        "brain_coral_wall_fan"         to "coral_fan_hang2",
        "bubble_coral_wall_fan"        to "coral_fan_hang3",

        // legacy .schematic flat/grouped ids (the loader emits these as palette names)
        "brick_block"                  to "bricks",
        "stonebrick"                   to "stone_bricks",
        "end_bricks"                   to "end_stone_bricks",
        "hardened_clay"                to "terracotta",
        "stained_hardened_clay"        to "terracotta",      // combined-id color is gone (legacy Data byte not parsed — doc gap)
        "stained_glass"                to "glass",           // same combined-id caveat; plain glass rather than guessing a color
        "stained_glass_pane"           to "glass",
        "stone_stairs"                 to "normal_stone_stairs",  // VERIFY vs canonical "stone_stairs" — capture decides
        "planks"                       to "oak_planks",
        "log"                          to "oak_log",
        "log2"                         to "oak_log",          // combined log2 (acacia/dark-oak subtypes) — collapses to oak (doc gap)
        "leaves"                       to "oak_leaves",
        "leaves2"                      to "oak_leaves",
        "sapling"                      to "oak_sapling",
        "fence"                        to "oak_fence",
        "wool"                         to "white_wool",       // combined-id color loss (doc gap; VERIFY: bedrock may still accept legacy "wool")
        "hay_block"                    to "hay_block"         // explicit "checked" identity marker — bedrock never renamed
    )

    // Color-coded families: beds and banners collapse to one bedrock id (+color state)
    private val COLORS = listOf(
        "white","orange","magenta","light_blue","yellow","lime","pink","gray",
        "light_gray","cyan","purple","blue","brown","green","red","black"
    )
    private fun buildColorTables(out: MutableMap<String, String>) {
        for (c in COLORS) {
            out["${c}_bed"] = "bed"
            out["${c}_banner"] = "standing_banner"
            out["${c}_wall_banner"] = "wall_banner"
        }
    }

    // Signs: standing uses "standing_sign", oak is the generic one, dark oak has no underscore.
    private val WOODS = listOf(
        "oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","bamboo","pale_oak","crimson","warped"
    )
    private fun buildSignTables(out: MutableMap<String, String>) {
        for (w in WOODS) {
            val be = if (w == "dark_oak") "darkoak" else w
            out["${w}_sign"]      = if (w == "oak") "standing_sign" else "${be}_standing_sign"
            out["${w}_wall_sign"] = if (w == "oak") "wall_sign"     else "${be}_wall_sign"
        }
        // hanging signs share ids on both editions — nothing to do here.
    }

    // Heads/skulls: one placement id, type lives in block state (VERIFY: "skull_type" values)
    private val HEADS = listOf(
        "skeleton_skull","wither_skeleton_skull","zombie_head","player_head","creeper_head","dragon_head","piglin_head",
        "skeleton_wall_skull","wither_skeleton_wall_skull","zombie_wall_head","player_wall_head","creeper_wall_head",
        "dragon_wall_head","piglin_wall_head"
    )

    private val DYNAMIC_RENAME: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        buildColorTables(out)
        buildSignTables(out)
        for (h in HEADS) out[h] = "skull"
        out
    }

    // Potted plants: java has full plant-in-pot ids; bedrock has one flower_pot (+plant state).
    private fun pottedTarget(id: String): String? =
        if (id.startsWith("potted_")) "flower_pot" else null

    // ── 3. Truly unsupported (no bedrock placement id) ──────────────────────

    private val UNSUPPORTED: Set<String> = setOf(
        "fire_coral_wall_fan",   // bedrock has only three coral_fan_hang variants
        "moving_piston",         // legacy "moving_block" is not a real placement
        "structure_void",        // exists on bedrock but hold-no-item; treat as void
        "void_air",              // bedrock collapses to plain air (handled upstream too)
        "cave_air",              // same
        "end_gateway",
        "end_portal",            // portal blocks aren't placable items (VERIFY per item-list capture)
        "nether_portal",         // java "nether_portal" → bedrock "portal" exists but placing portals is specialist; by-hand
        "test_block",
        "test_instance_block",
        "barrier"                // exists but never in a survival hotbar — SPECIAL is wrong-ish; keep explicit (VERIFY)
    )

    // ── resolution ─────────────────────────────────────────────────────────

    private fun bare(id: String): String = id.removePrefix("minecraft:")

    /** Whether the java id has an air variant. */
    fun isAirId(javaId: String): Boolean {
        val b = bare(javaId)
        return b == "air" || b == "cave_air" || b == "void_air" || b.endsWith(":air")
    }

    /**
     * Bedrock block id for placement, or null when UNSUPPORTED (no real
     * bedrock target — auto-builder must treat the cell as by-hand).
     */
    fun toBedrockBlockId(javaId: String): String? {
        if (isAirId(javaId)) return "minecraft:air"
        val b = bare(javaId)
        if (b in UNSUPPORTED) return null
        RENAME[b]?.let { return "minecraft:$it" }
        pottedTarget(b)?.let { return "minecraft:$it" }
        DYNAMIC_RENAME[b]?.let { return "minecraft:$it" }
        return "minecraft:$b"   // identity — editions share the vast majority of ids
    }

    /** explicit check — false means "route to by-hand" */
    fun isSupported(javaId: String): Boolean = toBedrockBlockId(javaId) != null

    /** Reason string for logging/chat when a java id can't be placed. */
    fun unsupportedNote(javaId: String): String =
        if (isSupported(javaId)) "" else "no bedrock placement id for '${bare(javaId)}'"

    // ── item ids (what you must HOLD) ───────────────────────────────────────

    /**
     * Item ids diverge from block ids less than you'd think, but there are
     * real exceptions (banner variants hold one banner, signs hold the per-wood
     * item, beds hold "bed", corals hold the fan, portal/frame blocks hold
     * nothing). Compact table for the exceptions; default = block id.
     */
    private val ITEM_RENAME: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        out["bed"] = "bed"
        out["skull"] = "skull"
        out["flower_pot"] = "flower_pot"
        out["standing_banner"] = "banner"     // VERIFY (per-color banner items may exist)
        out["wall_banner"] = "banner"
        // sign ITEMS keep the underscore on bedrock (dark_oak_sign), blocks ("darkoak") don't
        out["standing_sign"] = "oak_sign"
        out["wall_sign"] = "oak_sign"
        for (w in WOODS) {
            if (w == "oak") continue
            val be = if (w == "dark_oak") "darkoak" else w   // blocks flatten dark oak, items don't
            out["${be}_standing_sign"] = "${w}_sign"
            out["${be}_wall_sign"] = "${w}_sign"
        }
        out["coral_fan_hang"] = "coral_fan"     // VERIFY
        out["coral_fan_hang2"] = "coral_fan"
        out["coral_fan_hang3"] = "coral_fan"
        out
    }

    /** Bedrock ITEM id for the inventory tracker / inventory-slot matching. */
    fun toBedrockItemId(javaId: String): String? {
        val block = toBedrockBlockId(javaId) ?: return null
        val b = block.removePrefix("minecraft:")
        return "minecraft:" + (ITEM_RENAME[b] ?: b)
    }
}
