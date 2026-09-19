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

    class LoadError(message: String) : Exception(message)

    fun load(file: File): SchematicModel {
        if (!file.isFile) throw LoadError("not a file: ${file.name}")
        return when (file.extension.lowercase()) {
            "mcstructure" -> loadMcStructure(file)
            "schem"       -> loadSchem(file)
            else          -> throw LoadError("unsupported extension .${file.extension} (want .mcstructure or .schem)")
        }
    }

    /** List candidate files in a directory, newest first. */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.extension.equals("mcstructure", true) || f.extension.equals("schem", true)) }
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
        for (k in paletteTag.keySet()) {
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
}
