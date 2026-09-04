package com.rubidiumclient.utils

import org.cloudburstmc.math.vector.Vector3i
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// Xray modülü için cevher tarama/cache katmanı. BlockTracker'ın (chest/shulker
// takibi) tile-entity modeline benzer bir yapı kullanır, ama veri kaynağı
// farklı: BlockTracker paket-event bazlı (blok geldiğinde eklenir), OreTracker
// ise WorldBlockTracker.forEachBlockInRange ile periyodik tam-taramaya
// dayanır — çünkü cevherler tile-entity değil, düz blok verisidir ve tek tek
// paket dinleyerek yakalamanın garantisi yoktur (chunk toplu geldiği için).
object OreTracker {

    enum class OreType(val displayName: String, val colorArgb: Int, val tier: Int) {
        COAL       ("Coal",       0xFF4A4A4A.toInt(), 1),
        IRON       ("Iron",       0xFFD8A373.toInt(), 2),
        COPPER     ("Copper",     0xFFE0793E.toInt(), 1),
        GOLD       ("Gold",       0xFFFFD700.toInt(), 2),
        REDSTONE   ("Redstone",   0xFFFF1A1A.toInt(), 2),
        LAPIS      ("Lapis",      0xFF1E3ACC.toInt(), 2),
        DIAMOND    ("Diamond",    0xFF3CE7E1.toInt(), 3),
        EMERALD    ("Emerald",    0xFF17DD62.toInt(), 3),
        ANCIENT_DEBRIS("Ancient Debris", 0xFFB8654A.toInt(), 4),
        NETHER_QUARTZ("Quartz",   0xFFEDE5D8.toInt(), 1),
        NETHER_GOLD("Nether Gold",0xFFF0C33C.toInt(), 1);
    }

    data class TrackedOre(
        val pos: Vector3i,
        val type: OreType,
        val isDeepslate: Boolean,
        val discoveredAtScan: Long
    )

    // identifier -> (OreType, isDeepslate). "Normal" ve "deepslate" varyantları
    // ayrı ayrı eşleniyor çünkü kullanıcı ikisini bağımsız açıp kapatabilmeli
    // (deepslate genelde Y<0'da, normal cevher Y>0'da yoğunlaşır).
    private val IDENTIFIER_MAP: Map<String, Pair<OreType, Boolean>> = buildMap {
        fun reg(id: String, type: OreType, deepslate: Boolean) { put(id, type to deepslate) }

        reg("minecraft:coal_ore",              OreType.COAL, false)
        reg("minecraft:deepslate_coal_ore",    OreType.COAL, true)

        reg("minecraft:iron_ore",              OreType.IRON, false)
        reg("minecraft:deepslate_iron_ore",    OreType.IRON, true)

        reg("minecraft:copper_ore",            OreType.COPPER, false)
        reg("minecraft:deepslate_copper_ore",  OreType.COPPER, true)

        reg("minecraft:gold_ore",              OreType.GOLD, false)
        reg("minecraft:deepslate_gold_ore",    OreType.GOLD, true)

        reg("minecraft:redstone_ore",          OreType.REDSTONE, false)
        reg("minecraft:lit_redstone_ore",      OreType.REDSTONE, false)
        reg("minecraft:deepslate_redstone_ore", OreType.REDSTONE, true)
        reg("minecraft:lit_deepslate_redstone_ore", OreType.REDSTONE, true)

        reg("minecraft:lapis_ore",             OreType.LAPIS, false)
        reg("minecraft:deepslate_lapis_ore",   OreType.LAPIS, true)

        reg("minecraft:diamond_ore",           OreType.DIAMOND, false)
        reg("minecraft:deepslate_diamond_ore", OreType.DIAMOND, true)

        reg("minecraft:emerald_ore",           OreType.EMERALD, false)
        reg("minecraft:deepslate_emerald_ore", OreType.EMERALD, true)

        reg("minecraft:ancient_debris",        OreType.ANCIENT_DEBRIS, false)

        reg("minecraft:quartz_ore",            OreType.NETHER_QUARTZ, false)
        reg("minecraft:nether_gold_ore",       OreType.NETHER_GOLD, false)
    }

    private val trackedOres = ConcurrentHashMap<Long, TrackedOre>()
    private val scanning = AtomicBoolean(false)

    @Volatile var lastScanDurationMs: Long = 0L
        private set
    @Volatile var lastScanBlockCount: Int = 0
        private set
    @Volatile var lastScanAt: Long = 0L
        private set

    fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)

    fun isOreIdentifier(identifier: String): Boolean = identifier in IDENTIFIER_MAP

    // Eş zamanlı çift tarama önlenir (scanning flag) — tick aralığı tarama
    // süresinden kısa olursa üst üste binen taramalar hem gereksiz CPU hem
    // trackedOres üzerinde tutarsız ara-durum yaratabilirdi.
    fun scan(centerX: Int, centerY: Int, centerZ: Int, radius: Int, enabledTypes: Set<OreType>) {
        if (!scanning.compareAndSet(false, true)) return
        try {
            val start = System.currentTimeMillis()
            val found = HashMap<Long, TrackedOre>(256)

            WorldBlockTracker.forEachBlockInRange(
                centerX, centerY, centerZ, radius,
                predicate = { id -> id in IDENTIFIER_MAP },
                onMatch = { x, y, z, id ->
                    val (type, deepslate) = IDENTIFIER_MAP.getValue(id)
                    if (type in enabledTypes) {
                        val key = packKey(x, y, z)
                        found[key] = TrackedOre(Vector3i.from(x, y, z), type, deepslate, start)
                    }
                }
            )

            trackedOres.clear()
            trackedOres.putAll(found)

            lastScanDurationMs = System.currentTimeMillis() - start
            lastScanBlockCount = found.size
            lastScanAt = start
        } finally {
            scanning.set(false)
        }
    }

    fun isScanning(): Boolean = scanning.get()

    fun getAll(): Collection<TrackedOre> = trackedOres.values

    fun getAllInRange(cx: Float, cy: Float, cz: Float, range: Float): List<TrackedOre> {
        val r2 = range * range
        val result = ArrayList<TrackedOre>(64)
        for (ore in trackedOres.values) {
            val dx = ore.pos.x + 0.5f - cx
            val dy = ore.pos.y + 0.5f - cy
            val dz = ore.pos.z + 0.5f - cz
            if (dx * dx + dy * dy + dz * dz <= r2) result.add(ore)
        }
        return result
    }

    fun countByType(): Map<OreType, Int> =
        trackedOres.values.groupingBy { it.type }.eachCount()

    fun clear() {
        trackedOres.clear()
        lastScanBlockCount = 0
        lastScanDurationMs = 0L
    }

    fun size(): Int = trackedOres.size
}
