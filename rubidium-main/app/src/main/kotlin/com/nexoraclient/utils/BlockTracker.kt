package com.rubidiumclient.utils

import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import java.util.concurrent.ConcurrentHashMap

object BlockTracker {

    enum class TrackedBlockType(val displayName: String, val colorArgb: Int) {
        CHEST        ("Chest",        0xFFFF9B1A.toInt()),
        SHULKER_BOX  ("Shulker",      0xFFE040FB.toInt()),
        ENDER_CHEST  ("Ender Chest",  0xFF7C4DFF.toInt()),
        SPAWNER      ("Spawner",      0xFFFF1744.toInt()),
        HOPPER       ("Hopper",       0xFF00E5FF.toInt()),
        BARREL       ("Barrel",       0xFFFFD600.toInt()),
        TRAPPED_CHEST("Trapped Chest",0xFFFF6D00.toInt()),
        FURNACE      ("Furnace",      0xFFFF6E40.toInt()),
        BLAST_FURNACE("Blast Furnace",0xFFFF3D00.toInt()),
        SMOKER       ("Smoker",       0xFF69F0AE.toInt()),
        BREWING_STAND("Brewing Stand",0xFF40C4FF.toInt()),
        DISPENSER    ("Dispenser",    0xFF18FFFF.toInt()),
        DROPPER      ("Dropper",      0xFFFFFF00.toInt()),
    }

    data class TrackedBlock(
        val pos: Vector3i,
        val type: TrackedBlockType,
        val discoveredAt: Long = System.currentTimeMillis()
    )

    private val trackedBlocks = ConcurrentHashMap<Long, TrackedBlock>()

    private const val CELL_SHIFT = 4
    private val cellIndex = ConcurrentHashMap<Long, MutableSet<Long>>()

    private fun cellKey(x: Int, y: Int, z: Int): Long {
        val cx = x shr CELL_SHIFT
        val cy = y shr CELL_SHIFT
        val cz = z shr CELL_SHIFT
        return ((cx.toLong() and 0x1FFFFFL) shl 42) or
               ((cy.toLong() and 0xFFFL)    shl 21) or
               (cz.toLong() and 0x1FFFFFL)
    }

    fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)

    fun add(x: Int, y: Int, z: Int, type: TrackedBlockType) {
        val key = packKey(x, y, z)
        trackedBlocks[key] = TrackedBlock(Vector3i.from(x, y, z), type)
        cellIndex.getOrPut(cellKey(x, y, z)) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    fun remove(x: Int, y: Int, z: Int) {
        val key = packKey(x, y, z)
        trackedBlocks.remove(key)
        cellIndex[cellKey(x, y, z)]?.remove(key)
    }

    fun getAll(): Collection<TrackedBlock> = trackedBlocks.values

    fun getAllInRange(cx: Float, cy: Float, cz: Float, range: Float): List<TrackedBlock> {
        val r2 = range * range
        val cellRadius = (range.toInt() shr CELL_SHIFT) + 1
        val ccx = cx.toInt() shr CELL_SHIFT
        val ccy = cy.toInt() shr CELL_SHIFT
        val ccz = cz.toInt() shr CELL_SHIFT

        val result = ArrayList<TrackedBlock>(64)
        for (dx in -cellRadius..cellRadius) {
            for (dy in -cellRadius..cellRadius) {
                for (dz in -cellRadius..cellRadius) {
                    val key = ((ccx + dx).toLong() and 0x1FFFFFL shl 42) or
                              ((ccy + dy).toLong() and 0xFFFL    shl 21) or
                              ((ccz + dz).toLong() and 0x1FFFFFL)
                    val cell = cellIndex[key] ?: continue
                    for (blockKey in cell) {
                        val b = trackedBlocks[blockKey] ?: continue
                        val bdx = b.pos.x + 0.5f - cx
                        val bdy = b.pos.y + 0.5f - cy
                        val bdz = b.pos.z + 0.5f - cz
                        if (bdx*bdx + bdy*bdy + bdz*bdz <= r2) result.add(b)
                    }
                }
            }
        }
        return result
    }

    fun clear() {
        trackedBlocks.clear()
        cellIndex.clear()
    }

    fun size(): Int = trackedBlocks.size

    fun countByType(): Map<TrackedBlockType, Int> =
        trackedBlocks.values.groupingBy { it.type }.eachCount()

    private val paletteMap = ConcurrentHashMap<Int, TrackedBlockType>()
    @Volatile private var paletteLoaded = false

    fun loadPalette(startGame: StartGamePacket) {
        try {
            val paletteList = extractPaletteList(startGame)
            if (paletteList == null) {
                return
            }
            paletteMap.clear()
            var matched = 0
            paletteList.forEachIndexed { runtimeId, entry ->
                val name = extractBlockName(entry) ?: return@forEachIndexed
                val type = resolveBlockName(name) ?: return@forEachIndexed
                paletteMap[runtimeId] = type
                matched++
            }
            paletteLoaded = true
        } catch (e: Exception) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun extractPaletteList(startGame: StartGamePacket): List<Any>? {
        val candidateNames = listOf(
            "getBlockPalette", "getBlockProperties", "getBlockDefinitions", "getBlockPropertiesList"
        )
        for (methodName in candidateNames) {
            try {
                val m = startGame.javaClass.getMethod(methodName)
                val result = m.invoke(startGame)
                if (result is List<*>) return result.filterNotNull()
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun extractBlockName(entry: Any): String? {
        for (methodName in listOf("getName", "getIdentifier")) {
            try {
                val m = entry.javaClass.getMethod(methodName)
                val v = m.invoke(entry)
                if (v is String) return v
            } catch (_: Exception) {}
        }
        try {
            val tagMethod = entry.javaClass.getMethod("getTag")
            val tag = tagMethod.invoke(entry)
            if (tag != null) {
                val getStringMethod = tag.javaClass.getMethod("getString", String::class.java)
                val name = getStringMethod.invoke(tag, "name") as? String
                if (!name.isNullOrEmpty()) return name
            }
        } catch (_: Exception) {}
        return null
    }

    fun resolveBlockId(runtimeId: Int): TrackedBlockType? =
        paletteMap[runtimeId] ?: BLOCK_ID_MAP[runtimeId]

    fun resolveBlockName(name: String): TrackedBlockType? {
        val lower = name.lowercase()
        return when {
            lower.contains("chest") && lower.contains("ender") -> TrackedBlockType.ENDER_CHEST
            lower.contains("chest") && lower.contains("trapped") -> TrackedBlockType.TRAPPED_CHEST
            lower.contains("chest")        -> TrackedBlockType.CHEST
            lower.contains("shulker")      -> TrackedBlockType.SHULKER_BOX
            lower.contains("spawner")      -> TrackedBlockType.SPAWNER
            lower.contains("hopper")       -> TrackedBlockType.HOPPER
            lower.contains("barrel")       -> TrackedBlockType.BARREL
            lower.contains("blast_furnace")-> TrackedBlockType.BLAST_FURNACE
            lower.contains("smoker")       -> TrackedBlockType.SMOKER
            lower.contains("furnace")      -> TrackedBlockType.FURNACE
            lower.contains("brewing")      -> TrackedBlockType.BREWING_STAND
            lower.contains("dispenser")    -> TrackedBlockType.DISPENSER
            lower.contains("dropper")      -> TrackedBlockType.DROPPER
            else -> null
        }
    }

    private val BLOCK_ID_MAP: Map<Int, TrackedBlockType> = mapOf(
        54   to TrackedBlockType.CHEST,
        146  to TrackedBlockType.TRAPPED_CHEST,
        130  to TrackedBlockType.ENDER_CHEST,
        218  to TrackedBlockType.SHULKER_BOX,
        219  to TrackedBlockType.SHULKER_BOX,
        220  to TrackedBlockType.SHULKER_BOX,
        221  to TrackedBlockType.SHULKER_BOX,
        222  to TrackedBlockType.SHULKER_BOX,
        223  to TrackedBlockType.SHULKER_BOX,
        224  to TrackedBlockType.SHULKER_BOX,
        225  to TrackedBlockType.SHULKER_BOX,
        226  to TrackedBlockType.SHULKER_BOX,
        227  to TrackedBlockType.SHULKER_BOX,
        228  to TrackedBlockType.SHULKER_BOX,
        229  to TrackedBlockType.SHULKER_BOX,
        230  to TrackedBlockType.SHULKER_BOX,
        231  to TrackedBlockType.SHULKER_BOX,
        232  to TrackedBlockType.SHULKER_BOX,
        233  to TrackedBlockType.SHULKER_BOX,
        52   to TrackedBlockType.SPAWNER,
        154  to TrackedBlockType.HOPPER,
        58   to TrackedBlockType.BARREL,
        61   to TrackedBlockType.FURNACE,
        62   to TrackedBlockType.FURNACE,
        450  to TrackedBlockType.BLAST_FURNACE,
        451  to TrackedBlockType.SMOKER,
        117  to TrackedBlockType.BREWING_STAND,
        23   to TrackedBlockType.DISPENSER,
        158  to TrackedBlockType.DROPPER,
    )
}
