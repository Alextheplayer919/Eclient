package com.rubidiumclient.module.world

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * AutoTorch — spawn-proofs dark floors near the player by placing torches.
 *
 * Spawn rule implemented (docs/AUTOTORCH.md, researched from official spawn
 * docs + community sources): Overworld hostiles need BLOCK LIGHT == 0 (and
 * sky light < 7, i.e. effectively no torch-ish source around). A single torch
 * (light 14, −1 per taxicab step) spawn-proofs 13 blocks in every direction
 * on flat unobstructed ground.
 *
 * IMPORTANT ground truth: Bedrock sends NO light arrays on the wire (no sky/
 * block light in LevelChunk — the client relights locally). We therefore
 * compute block light ourselves from the WorldBlockTracker's real block-id
 * map: a multi-source BFS flood from every emissive block in range, −1 per
 * taxicab step, blocked by opaque ids (conservative: ambiguous blocks count
 * as opaque, so we bias toward placing MORE torches, never fewer).
 *
 * Placement reuses PlacementUtil's proven chain (hotbar prepare → ITEM_USE
 * air-gesture on the floor's top face → revert), one torch per tick max.
 */
class AutoTorch : BaseModule(
    name        = "AutoTorch",
    category    = ModuleCategory.WORLD,
    description = "Places torches on dark, mob-spawnable floors near you (client-side light simulation)"
) {
    private val tickMs   = int("Tick ms", 600, 300, 2000)
    private val reach    = float("Reach", 4.2f, 2.5f, 4.6f)
    private val vertical = int("Vertical scan", 3, 1, 6)

    override fun onEnable() {
        super.onEnable()
        launchTickLoop(tickMs.value.toLong()) {
            val session = com.rubidiumclient.events.PacketEventBus.currentSession ?: return@launchTickLoop
            tick(session)
        }
    }

    // ── model ──────────────────────────────────────────────────────────────

    private val LIGHT_RADIUS = 13      // farthest a torch can matter
    private val MAX_EMITTER  = 15

    /** Rough emission table. Uncertain emitters use their MINIMUM value (safe bias). */
    private val EMITTERS: Map<String, Int> = run {
        val m = HashMap<String, Int>()
        fun e(v: Int, vararg ids: String) { ids.forEach { m[it] = v } }
        e(15, "minecraft:glowstone", "minecraft:sea_lantern", "minecraft:shroomlight",
            "minecraft:lava", "minecraft:flowing_lava", "minecraft:beacon",
            "minecraft:magma", "minecraft:lit_blastfurnace", "minecraft:lit_furnace",
            "minecraft:lit_smoker", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:campfire", "minecraft:lantern", "minecraft:soul_lantern",
            "minecraft:crying_obsidian", "minecraft:redstone_lamp", "minecraft:lit_redstone_lamp",
            "minecraft:verdant_froglight", "minecraft:ochre_froglight", "minecraft:pearlescent_froglight",
            "minecraft:enchanting_table", "minecraft:light_block", "minecraft:light",
            "minecraft:infested_monster_egg")
        e(14, "minecraft:torch", "minecraft:end_rod", "minecraft:pearlescent_froglight")
        e(9,  "minecraft:nether_portal")
        e(7,  "minecraft:redstone_torch", "minecraft:glow_lichen", "minecraft:amethyst_bud")
        e(3,  "minecraft:candle", "minecraft:sea_pickle", "minecraft:brown_mushroom")
        e(1,  "minecraft:lava_cauldron")
        m
    }

    /** Blocks light passes through. Everything NOT here is treated as opaque —
     * conservative (biased toward placing more torches, never too few). */
    private val TRANSLUCENT: Set<String> = buildSet {
        add("minecraft:air"); add("minecraft:cave_air"); add("minecraft:void_air")
        add("minecraft:water"); add("minecraft:flowing_water")
        add("minecraft:glass"); add("minecraft:glass_pane"); add("minecraft:tinted_glass")
        listOf("white","orange","magenta","light_blue","yellow","lime","pink","gray",
            "light_gray","cyan","purple","blue","brown","green","red","black").forEach { c ->
            add("minecraft:${c}_stained_glass"); add("minecraft:${c}_stained_glass_pane")
        }
        add("minecraft:torch"); add("minecraft:soul_torch"); add("minecraft:redstone_torch")
        add("minecraft:ladder"); add("minecraft:vine"); add("minecraft:twisting_vines")
        add("minecraft:weeping_vines"); add("minecraft:snow_layer"); add("minecraft:carpet")
        add("minecraft:cobweb"); add("minecraft:flower_pot"); add("minecraft:mangrove_roots")
        add("minecraft:fire"); add("minecraft:soul_fire"); add("minecraft:kelp")
        add("minecraft:sugar_cane"); add("minecraft:bamboo_sapling"); add("minecraft:bamboo")
    }

    private fun isTranslucent(id: String?): Boolean =
        id == null || id in TRANSLUCENT || run {
            val n = id.removePrefix("minecraft:")
            n.endsWith("_sapling") || n.endsWith("_flower") || n.endsWith("_tulip") ||
            n.endsWith("_orchid") || n.endsWith("_daisy") || n.endsWith("_cornflower") ||
            n.endsWith("_lily") || n.endsWith("_rose") || n.endsWith("_allium") ||
            n in setOf("grass", "short_grass", "fern", "large_fern", "dead_bush", "wheat",
                "carrots", "potatoes", "beetroot", "pumpkin_stem", "melon_stem",
                "tall_grass", "dandelion", "poppy", "mushroom_red", "mushroom_brown",
                "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
                "nether_sprouts", "roots", "crimson_roots", "warped_roots", "seagrass",
                "coral", "coral_fan", "coral_wall_fan", "rail", "powered_rail",
                "detector_rail", "activator_rail", "sign", "wall_sign", "standing_sign",
                "oak_sign", "end_crystal", "lever", "button", "stone_button", "tripwire",
                "tripwire_hook", "string", "repeater", "comparator", "redstone_wire",
                "candle", "candle_cake", "glow_lichen", "hanging_roots", "torchflower")
        }

    private fun isEmitter(id: String?): Int = EMITTERS[id] ?: 0

    /** Floor whose top face can host a placed torch & host spawns (opaque per our model). */
    private fun isFloor(id: String?): Boolean {
        val n = id ?: return false
        if (isToggleAbleNoSpawn(n)) return false
        return !isTranslucent(n) && isEmitter(n) == 0
    }

    /** Solid-looking ids mobs still can't spawn on (bedrock no-spawn ground). */
    private fun isToggleAbleNoSpawn(id: String): Boolean {
        val n = id.removePrefix("minecraft:")
        return n.startsWith("leaves") || n.endsWith("_leaves") || n == "barrier" || n == "bedrock"
    }

    // ── light map ──────────────────────────────────────────────────────────

    private data class Key(val x: Int, val y: Int, val z: Int)

    /** One-pass multi-source BFS over the 27³ region: seeds = all emitters. */
    private fun computeLight(cx: Int, cy: Int, cz: Int): MutableMap<Key, Int> {
        val r = LIGHT_RADIUS + 1
        val light = HashMap<Key, Int>(4096)
        val queue = ArrayDeque<Key>()
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val id = WorldBlockTracker.getBlockIdentifier(cx + dx, cy + dy, cz + dz) ?: continue
            val v = isEmitter(id)
            if (v > 1) {
                val k = Key(cx + dx, cy + dy, cz + dz)
                if ((light[k] ?: 0) < v) { light[k] = v; queue.add(k) }
            }
        }
        val dirs = arrayOf(
            intArrayOf(1,0,0), intArrayOf(-1,0,0), intArrayOf(0,1,0),
            intArrayOf(0,-1,0), intArrayOf(0,0,1), intArrayOf(0,0,-1)
        )
        val minX = cx - r; val maxX = cx + r
        val minY = cy - r; val maxY = cy + r
        val minZ = cz - r; val maxZ = cz + r
        while (!queue.isEmpty()) {
            val k = queue.poll()
            val v = light[k] ?: continue
            if (v <= 1) continue
            for (d in dirs) {
                val nx = k.x + d[0]; val ny = k.y + d[1]; val nz = k.z + d[2]
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue
                val idx = WorldBlockTracker.getBlockIdentifier(nx, ny, nz)
                if (!isTranslucent(idx)) continue
                val nk = Key(nx, ny, nz)
                val nv = v - 1
                if (nv > (light[nk] ?: 0)) { light[nk] = nv; queue.add(nk) }
            }
        }
        return light
    }

    // ── placement ──────────────────────────────────────────────────────────

    private val recentlyPlaced = ConcurrentHashMap<Long, Long>()
    private val PLACED_TTL_MS = 5_000L

    private fun tick(session: RubidiumRelaySession) {
        if (!WorldBlockTracker.hasAnyTerrainData()) return
        val px = floor(EntityTracker.selfX).toInt()
        val py = floor(EntityTracker.selfY).toInt()
        val pz = floor(EntityTracker.selfZ).toInt()

        if (torchCheckKillSwitch(session)) return

        val light = computeLight(px, py, pz)
        val rr = reach.value
        val yRange = vertical.value
        var best: Triple<Int, Int, Int>? = null
        var bestDist = Float.MAX_VALUE

        for (dy in -yRange..yRange) for (dx in -5..5) for (dz in -5..5) {
            val x = px + dx; val y = py + dy; val z = pz + dz
            // cell must be non-solid (where the torch/mob would live) …
            if (!isTranslucent(WorldBlockTracker.getBlockIdentifier(x, y, z))) continue
            // … plus headroom …
            if (!isTranslucent(WorldBlockTracker.getBlockIdentifier(x, y + 1, z))) continue
            // … standing on a spawnable floor …
            if (!isFloor(WorldBlockTracker.getBlockIdentifier(x, y - 1, z))) continue
            // … NOT the player's own cell …
            if ((x == px && y == py && z == pz) || (x == px && y == py + 1 && z == pz)) continue
            // … in placement reach of the floor block centre …
            val ddx = x + 0.5f - EntityTracker.selfX
            val ddy = y - 0.5f - (EntityTracker.selfY + 1.62f)
            val ddz = z + 0.5f - EntityTracker.selfZ
            val dist = kotlin.math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
            if (dist > rr) continue
            // … dark per our simulated block light …
            if ((light[Key(x, y, z)] ?: 0) > 0) continue
            // … and not already torched a moment ago …
            val pk = PlacementUtil.posKey(x, y, z)
            val last = recentlyPlaced[pk]
            if (last != null && System.currentTimeMillis() - last < PLACED_TTL_MS) continue

            if (dist < bestDist) { bestDist = dist; best = Triple(x, y, z) }
        }

        val t = best ?: return
        placeTorch(session, t.first, t.second, t.third)
    }

    /** False when we can proceed; true when we attempted nothing (no torch anywhere). */
    private fun torchCheckKillSwitch(session: RubidiumRelaySession): Boolean =
        PlacementUtil.findItemInInventory("minecraft:torch") == null &&
        PlacementUtil.findItemInInventory("minecraft:soul_torch") == null

    private fun placeTorch(session: RubidiumRelaySession, x: Int, y: Int, z: Int) {
        val id = if (PlacementUtil.findItemInInventory("minecraft:torch") != null)
            "minecraft:torch" else "minecraft:soul_torch"
        val prepared = PlacementUtil.prepareItemForUse(session, id) ?: return
        val floorPos = org.cloudburstmc.math.vector.Vector3i.from(x, y - 1, z)
        val ok = runCatching {
            PlacementUtil.sendPlacementUseRaw(
                session, prepared, floorPos, id,
                blockFace = 1,   // top face — standing torch
                clickPosition = Vector3f.from(0.5f, 1.0f, 0.5f)
            )
        }.getOrDefault(false)
        PlacementUtil.revert(session, prepared)
        if (ok) recentlyPlaced[PlacementUtil.posKey(x, y, z)] = System.currentTimeMillis()
    }

    override fun onPacket(event: PacketEvent) {}
}
