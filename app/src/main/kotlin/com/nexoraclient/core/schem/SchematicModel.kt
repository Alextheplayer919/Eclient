package com.rubidiumclient.core.schem

import org.cloudburstmc.nbt.NBTInputStream
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import java.io.File
import java.io.FileInputStream

/**
 * SchematicModel — parsed build template for the Schematica module.
 *
 * Supported inputs (v1):
 *   .mcstructure — native BEDROCK structure-block export.
 *                  Little-endian NBT, uncompressed. Fields: size[3],
 *                  structure.block_indices[layer0], structure.palette.default
 *                  .block_palette[{name,states,version}]. Layer 1 (waterlog)
 *                  and block_position_data are ignored.
 *   .schem       — Sponge/WorldEdit schematic v2 and v3.
 *                  GZIP + big-endian NBT. v2: root Palette{string:int} +
 *                  varint-packed BlockData byte[]. v3: same under "Blocks".
 *   .schematic   — legacy MCEdit/Classic schematic. GZIP big-endian NBT with
 *                  numeric legacy block IDs (Blocks/Data byte[]); mapped via
 *                  LEGACY_IDS, unknown IDs ghost as stone.
 *   .litematic   — Litematica format. NBT (gzip or plain), possibly multiple
 *                  Regions merged into the combined bounding box. BlockStates
 *                  are bit-packed (Litematica packer: entries never cross long
 *                  boundaries, LSB-first, bits = max(2, ceil(log2(palette)))).
 *
 * Both formats flatten cells with x fastest, then z, then y:
 *   index = (y * length + z) * width + x
 *
 * States/properties are dropped — the ghost renderer only needs identifiers.
 * Cell value -LAYER_EMPTY means "nothing here".
 */
class SchematicModel(
    val name: String,
    val width: Int,
    val height: Int,
    val length: Int,
    val palette: List<String>,          // palette index -> block identifier (states dropped)
    val cells: IntArray                 // size w*h*l; EMPTY = no block
) {
    companion object {
        const val EMPTY = -1
    }

    fun index(x: Int, y: Int, z: Int) = (y * length + z) * width + x

    fun blockAt(x: Int, y: Int, z: Int): String? {
        if (x !in 0 until width || y !in 0 until height || z !in 0 until length) return null
        val p = cells[index(x, y, z)]
        return if (p == EMPTY) null else palette.getOrNull(p)
    }

    fun isSolid(x: Int, y: Int, z: Int): Boolean {
        val id = blockAt(x, y, z) ?: return false
        return !id.endsWith(":air")
    }

    val solidCount: Int by lazy { cells.count { it != EMPTY } }

    /** Solid cells that touch openness on at least one of 6 sides — only these
     *  are visible from outside, so only these get ghost points. */
    fun exposedCells(): IntArray {
        val out = IntArray(solidCount)
        var n = 0
        for (y in 0 until height) for (z in 0 until length) for (x in 0 until width) {
            if (!isSolid(x, y, z)) continue
            if (!isSolid(x - 1, y, z) || !isSolid(x + 1, y, z) ||
                !isSolid(x, y - 1, z) || !isSolid(x, y + 1, z) ||
                !isSolid(x, y, z - 1) || !isSolid(x, y, z + 1)) {
                out[n++] = index(x, y, z)
            }
        }
        return out.copyOf(n)
    }

    fun coordsOf(idx: Int): Triple<Int, Int, Int> {
        val y = idx / (length * width)
        val rem = idx % (length * width)
        return Triple(rem % width, y, rem / width)
    }
}

object SchematicLoader {

    private val SUPPORTED = setOf("mcstructure", "schem", "schematic", "litematic")
    private const val MAX_CELLS = 6_000_000   // phone-memory sanity cap

    class LoadError(message: String) : Exception(message)

    fun load(file: File): SchematicModel {
        if (!file.isFile) throw LoadError("not a file: ${file.name}")
        return when (file.extension.lowercase()) {
            "mcstructure" -> loadMcStructure(file)
            "schem"       -> loadSchem(file)
            "schematic"   -> loadLegacySchematic(file)
            "litematic"   -> loadLitematic(file)
            else          -> throw LoadError("unsupported extension .${file.extension} (want .mcstructure/.schem/.schematic/.litematic)")
        }
    }

    /** List candidate files in a directory, newest first. */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && SUPPORTED.contains(f.extension.lowercase()) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ── .mcstructure (bedrock native) ────────────────────────────────────

    private fun loadMcStructure(file: File): SchematicModel {
        val root: NbtMap = FileInputStream(file).use { fis ->
            NbtUtils.createReaderLE(fis, false, false).use(NBTInputStream::readTag)
        } as? NbtMap ?: throw LoadError("${file.name}: root tag is not a compound")

        val size = root.getList("size", NbtType.INT)
        if (size.size < 3) throw LoadError("${file.name}: missing size[3]")
        val w = size[0]; val h = size[1]; val l = size[2]

        val structure = root.getCompound("structure")
            ?: throw LoadError("${file.name}: no structure compound")
        val layers = structure.getList("block_indices", NbtType.LIST)
        if (layers.isEmpty()) throw LoadError("${file.name}: no block_indices")
        val layer0 = layers[0] as? List<*> ?: throw LoadError("${file.name}: bad layer0")

        val paletteMap = structure.getCompound("palette")?.getCompound("default")
            ?: throw LoadError("${file.name}: no palette.default")
        val blockPalette = paletteMap.getList("block_palette", NbtType.COMPOUND)

        val palette = ArrayList<String>(blockPalette.size)
        for (entry in blockPalette) {
            palette.add(entry?.getString("name") ?: "minecraft:air")
        }

        val cells = IntArray(w * h * l) { SchematicModel.EMPTY }
        val n = minOf(layer0.size, cells.size)
        for (i in 0 until n) {
            val v = (layer0[i] as? Int) ?: SchematicModel.EMPTY
            if (v >= 0) cells[i] = v
        }
        return SchematicModel(file.nameWithoutExtension, w, h, l, palette, cells)
    }

    // ── .schem (Sponge v2/v3) ────────────────────────────────────────────

    private fun loadSchem(file: File): SchematicModel {
        val root: NbtMap = FileInputStream(file).use { fis ->
            NbtUtils.createGZIPReader(fis).use(NBTInputStream::readTag)
        } as? NbtMap ?: throw LoadError("${file.name}: root tag is not a compound")

        val w = root.getShort("Width").toInt()
        val h = root.getShort("Height").toInt()
        val l = root.getShort("Length").toInt()
        if (w <= 0 || h <= 0 || l <= 0) throw LoadError("${file.name}: bad dims ${w}x${h}x${l}")

        // v1/v2 keep Palette+BlockData at root; v3 nests them under "Blocks".
        val blocksComp: NbtMap = (if (root.containsKey("Blocks")) root.getCompound("Blocks") else root)
            ?: throw LoadError("${file.name}: no Blocks compound")
        val paletteTag = blocksComp.getCompound("Palette")
            ?: throw LoadError("${file.name}: no Palette compound")
        val data = blocksComp.getByteArray("BlockData")
            ?: blocksComp.getByteArray("Data")
            ?: throw LoadError("${file.name}: no BlockData")

        val paletteArr = arrayOfNulls<String>(paletteTag.size + 1)
        for (k in paletteTag.keys) {
            val v = (paletteTag.get(k) as? Int) ?: continue
            if (v in paletteArr.indices) paletteArr[v] = k
        }
        val palette = paletteArr.map { it ?: "minecraft:air" }

        // BlockData is a varint-packed palette index stream.
        val cells = IntArray(w * h * l) { SchematicModel.EMPTY }
        var pos = 0
        var cell = 0
        while (pos < data.size && cell < cells.size) {
            var value = 0
            var shift = 0
            while (true) {
                if (pos >= data.size) break
                val b = data[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) throw LoadError("${file.name}: corrupt varint in BlockData")
            }
            if (value > 0) cells[cell] = value   // index 0 is air per format convention
            cell++
        }
        return SchematicModel(file.nameWithoutExtension, w, h, l, palette, cells)
    }
    // ── .schematic (legacy MCEdit numeric IDs) ───────────────────────────

    private val LEGACY_IDS: Map<Int, String> = mapOf(
        0 to "minecraft:air", 1 to "minecraft:stone", 2 to "minecraft:grass",
        3 to "minecraft:dirt", 4 to "minecraft:cobblestone", 5 to "minecraft:planks",
        6 to "minecraft:sapling", 7 to "minecraft:bedrock", 8 to "minecraft:flowing_water",
        9 to "minecraft:water", 10 to "minecraft:flowing_lava", 11 to "minecraft:lava",
        12 to "minecraft:sand", 13 to "minecraft:gravel", 14 to "minecraft:gold_ore",
        15 to "minecraft:iron_ore", 16 to "minecraft:coal_ore", 17 to "minecraft:log",
        18 to "minecraft:leaves", 19 to "minecraft:sponge", 20 to "minecraft:glass",
        21 to "minecraft:lapis_ore", 22 to "minecraft:lapis_block", 24 to "minecraft:sandstone",
        31 to "minecraft:tall_grass", 35 to "minecraft:wool", 37 to "minecraft:dandelion",
        38 to "minecraft:poppy", 41 to "minecraft:gold_block", 42 to "minecraft:iron_block",
        45 to "minecraft:brick_block", 46 to "minecraft:tnt", 47 to "minecraft:bookshelf",
        48 to "minecraft:mossy_cobblestone", 49 to "minecraft:obsidian", 50 to "minecraft:torch",
        53 to "minecraft:oak_stairs", 54 to "minecraft:chest", 56 to "minecraft:diamond_ore",
        57 to "minecraft:diamond_block", 58 to "minecraft:crafting_table", 67 to "minecraft:stone_stairs",
        80 to "minecraft:snow", 81 to "minecraft:cactus", 85 to "minecraft:fence",
        86 to "minecraft:pumpkin", 87 to "minecraft:netherrack", 88 to "minecraft:soul_sand",
        89 to "minecraft:glowstone", 91 to "minecraft:lit_pumpkin", 95 to "minecraft:stained_glass",
        98 to "minecraft:stonebrick", 103 to "minecraft:melon_block", 110 to "minecraft:mycelium",
        112 to "minecraft:nether_brick", 114 to "minecraft:nether_brick_stairs", 121 to "minecraft:end_stone",
        129 to "minecraft:emerald_ore", 133 to "minecraft:emerald_block", 152 to "minecraft:redstone_block",
        155 to "minecraft:quartz_block", 159 to "minecraft:stained_hardened_clay", 160 to "minecraft:stained_glass_pane",
        161 to "minecraft:leaves2", 162 to "minecraft:log2", 168 to "minecraft:prismarine",
        169 to "minecraft:sea_lantern", 170 to "minecraft:hay_block", 172 to "minecraft:hardened_clay",
        174 to "minecraft:packed_ice", 179 to "minecraft:red_sandstone", 201 to "minecraft:purpur_block",
        206 to "minecraft:end_bricks", 216 to "minecraft:bone_block"
    )

    private fun loadLegacySchematic(file: File): SchematicModel {
        val root: NbtMap = FileInputStream(file).use { fis ->
            NbtUtils.createGZIPReader(fis).use(NBTInputStream::readTag)
        } as? NbtMap ?: throw LoadError("${file.name}: root tag is not a compound")

        val w = root.getShort("Width").toInt(); val h = root.getShort("Height").toInt(); val l = root.getShort("Length").toInt()
        if (w <= 0 || h <= 0 || l <= 0) throw LoadError("${file.name}: bad dims ${w}x${h}x${l}")
        if (w * h * l > MAX_CELLS) throw LoadError("${file.name}: too big (${w * h * l} cells)")

        val blocks = root.getByteArray("Blocks") ?: throw LoadError("${file.name}: no Blocks array")
        val palette = ArrayList<String>()
        val mapIdx = HashMap<Int, Int>()
        val cells = IntArray(w * h * l) { SchematicModel.EMPTY }
        val n = minOf(blocks.size, cells.size)
        for (i in 0 until n) {
            val legacyId = blocks[i].toInt() and 0xFF
            if (legacyId == 0) continue   // air
            val p = mapIdx.getOrPut(legacyId) {
                palette.add(LEGACY_IDS[legacyId] ?: "minecraft:stone")   // unknown ghost as stone
                palette.size - 1
            }
            cells[i] = p
        }
        return SchematicModel(file.nameWithoutExtension, w, h, l, palette, cells)
    }

    // ── .litematic (Litematica) ──────────────────────────────────────────

    private fun readNbtAuto(file: File): NbtMap {
        // .litematic appears both gzipped and plain in the wild — detect by magic.
        FileInputStream(file).use { probe ->
            val b0 = probe.read(); val b1 = probe.read()
            val gz = (b0 == 0x1F && b1 == 0x8B)
            return FileInputStream(file).use { fis ->
                (if (gz) NbtUtils.createGZIPReader(fis) else NbtUtils.createReader(fis, false, false))
                    .use(NBTInputStream::readTag)
            } as? NbtMap ?: throw LoadError("${file.name}: root tag is not a compound")
        }
    }

    private fun loadLitematic(file: File): SchematicModel {
        val root = readNbtAuto(file)
        val regions = root.getCompound("Regions")
            ?: throw LoadError("${file.name}: no Regions compound (not a litematic?)")

        data class Region(val minX: Int, val minY: Int, val minZ: Int,
                          val sx: Int, val sy: Int, val sz: Int,
                          val palette: List<String>, val cells: IntArray)

        val parsed = ArrayList<Region>()
        for (name in regions.keys) {
            val reg = regions.getCompound(name) ?: continue
            val pos = reg.getCompound("Position") ?: continue
            val size = reg.getCompound("Size") ?: continue
            val px = pos.getInt("x"); val py = pos.getInt("y"); val pz = pos.getInt("z")
            val ddx = size.getInt("x"); val ddy = size.getInt("y"); val ddz = size.getInt("z")
            val sx = kotlin.math.abs(ddx); val sy = kotlin.math.abs(ddy); val sz = kotlin.math.abs(ddz)
            if (sx == 0 || sy == 0 || sz == 0) continue

            val paletteTag = reg.getList("BlockStatePalette", NbtType.COMPOUND)
            val palette = ArrayList<String>(paletteTag.size)
            for (e in paletteTag) palette.add(e?.getString("Name") ?: "minecraft:air")

            val packed = reg.getLongArray("BlockStates")
            if (packed.isEmpty()) throw LoadError("${file.name}: region $name has no BlockStates")
            val total = sx * sy * sz
            if (total > MAX_CELLS) throw LoadError("${file.name}: region $name too big ($total cells)")

            // Litematica packing: bits = max(2, ceil(log2(paletteSize))), entries
            // LSB-first, wholly contained within each long.
            var bits = 1; while ((1 shl bits) < palette.size) bits++
            bits = bits.coerceAtLeast(2)
            val perLong = 64 / bits
            val mask = (1L shl bits) - 1L
            val cells = IntArray(total) { SchematicModel.EMPTY }
            var cell = 0
            outer@ for (lv in packed) {
                for (j in 0 until perLong) {
                    if (cell >= total) break@outer
                    val v = ((lv ushr (j * bits)) and mask).toInt()
                    if (v > 0 && v < palette.size && !palette[v].endsWith(":air")) cells[cell] = v
                    cell++
                }
            }
            // Normalize min corner (litematic sizes may be negative).
            parsed.add(Region(minOf(px, px + ddx), minOf(py, py + ddy), minOf(pz, pz + ddz), sx, sy, sz, palette, cells))
        }
        if (parsed.isEmpty()) throw LoadError("${file.name}: no usable regions")

        val gMinX = parsed.minOf { it.minX }; val gMinY = parsed.minOf { it.minY }; val gMinZ = parsed.minOf { it.minZ }
        val gMaxX = parsed.maxOf { it.minX + it.sx }; val gMaxY = parsed.maxOf { it.minY + it.sy }; val gMaxZ = parsed.maxOf { it.minZ + it.sz }
        val w = gMaxX - gMinX; val h = gMaxY - gMinY; val l = gMaxZ - gMinZ
        if (w * h * l > MAX_CELLS) throw LoadError("${file.name}: merged bounds too big (${w * h * l} cells)")

        val palette = arrayListOf("minecraft:air")
        val remap = ArrayList<IntArray>()
        for (r in parsed) {
            val m = IntArray(r.palette.size)
            for (i in r.palette.indices) {
                m[i] = palette.indexOf(r.palette[i]).takeIf { it >= 0 } ?: run { palette.add(r.palette[i]); palette.size - 1 }
            }
            remap.add(m)
        }
        val cells = IntArray(w * h * l) { SchematicModel.EMPTY }
        val model = SchematicModel(file.nameWithoutExtension, w, h, l, palette, cells)
        for ((ri, r) in parsed.withIndex()) {
            val m = remap[ri]
            for (y in 0 until r.sy) for (z in 0 until r.sz) for (x in 0 until r.sx) {
                val v = r.cells[(y * r.sz + z) * r.sx + x]
                if (v == SchematicModel.EMPTY) continue
                val gx = r.minX - gMinX + x; val gy = r.minY - gMinY + y; val gz = r.minZ - gMinZ + z
                cells[(gy * l + gz) * w + gx] = m[v]
            }
        }
        return model
    }

}
