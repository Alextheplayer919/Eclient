package com.rubidiumclient.core.relay

import android.content.Context
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * PERFORMANS FIX: Bu obje önceden init()'te ~21 item JSON dosyasını VE
 * ilgili block_palette NBT dosyalarını (GZIP decompress + NBT parse) TAMAMEN
 * senkron ve HEPSİNİ AYNI ANDA yüklüyordu — app açılışında (RibidiumClientApp
 * içinde çağrılıyor) bu ciddi bir startup gecikmesi/donma kaynağıydı, çünkü
 * bağlanılan sunucunun protokolüne göre pratikte SADECE BİR versiyon gerçekten
 * kullanılıyor. Artık init() sadece dosya listesini/versiyon eşlemesini
 * TARIYOR (ucuz, sadece dosya adı parse), gerçek NBT/JSON parse işi bir
 * versiyon ilk defa getClosestDefinitions() ile istendiğinde yapılıyor ve
 * sonrası için cache'leniyor (ConcurrentHashMap.computeIfAbsent, thread-safe).
 */
object Definitions {

    private const val TAG = "Definitions"
    private val VERSION_REGEX = Regex("v(\\d+)")

    data class VersionedDefinitions(
        val protocolVersion: Int,
        val blockDefinitions: DefinitionRegistry<BlockDefinition>,
        val blockDefinitionsHashed: DefinitionRegistry<BlockDefinition>,
        val itemDefinitions: DefinitionRegistry<ItemDefinition>
    )

    private data class FilePair(val blockFile: String, val itemFile: String)

    var cameraPresetDefinitions: DefinitionRegistry<NamedDefinition> =
        SimpleDefinitionRegistry.builder<NamedDefinition>().build()
        private set

    // Versiyon -> dosya eşlemesi: init()'te dolduruluyor, ucuz (sadece dosya
    // adlarını okuyup regex ile versiyon çıkarmak).
    private val fileMap = LinkedHashMap<Int, FilePair>()
    private val sortedVersions = mutableListOf<Int>()

    // Gerçek parse edilmiş sonuçlar burada cache'leniyor — SADECE fiilen
    // istenen versiyonlar için, ilk istekte dolduruluyor.
    private val loadedCache = ConcurrentHashMap<Int, VersionedDefinitions>()
    // Aynı block palette dosyası birden fazla item versiyonu tarafından
    // fallback olarak paylaşılabiliyor — dosya bazında ayrı cache, aynı
    // paleti iki kere parse etmemek için.
    private val blockPaletteCache =
        ConcurrentHashMap<String, Pair<DefinitionRegistry<BlockDefinition>, DefinitionRegistry<BlockDefinition>>>()

    @Volatile private var appContext: Context? = null

    @Volatile var loaded = false
        private set

    fun init(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        try {
            scanVersions(context)
            sortedVersions.clear()
            sortedVersions.addAll(fileMap.keys)
            sortedVersions.sortDescending()
            loaded = true
        } catch (e: Exception) {
        }
    }

    /** Sadece dosya adlarını tarar, hiçbir dosya İÇERİĞİ okumaz — hızlı. */
    private fun scanVersions(context: Context) {
        val files = try {
            context.assets.list("nbt") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        val blockFiles = files.filter { it.startsWith("block_palette") && it.endsWith(".nbt") }
        val itemFiles = files.filter { it.startsWith("runtime_item_states") && it.endsWith(".json") }

        val versionedBlockFiles = blockFiles.mapNotNull { f -> extractVersion(f)?.let { it to f } }.toMap()
        val fallbackBlockFile = blockFiles.firstOrNull { extractVersion(it) == null }
            ?: blockFiles.firstOrNull()

        for (itemFile in itemFiles) {
            val version = extractVersion(itemFile) ?: continue
            val blockFile = versionedBlockFiles[version] ?: fallbackBlockFile ?: continue
            fileMap[version] = FilePair(blockFile, itemFile)
        }
    }

    private fun extractVersion(filename: String): Int? =
        VERSION_REGEX.find(filename)?.groupValues?.get(1)?.toIntOrNull()

    private fun loadBlockPalette(
        context: Context,
        filename: String
    ): Pair<DefinitionRegistry<BlockDefinition>, DefinitionRegistry<BlockDefinition>> {
        val stream = context.assets.open("nbt/$filename")
        val tag = NbtUtils.createGZIPReader(stream).use { it.readTag() }
        require(tag is NbtMap) { "$filename geçersiz format (NbtMap bekleniyor)" }

        val blocks = tag.getList("blocks", NbtType.COMPOUND)
        val normal = NbtBlockDefinitionRegistry(blocks, hashed = false)
        val hashed = NbtBlockDefinitionRegistry(blocks, hashed = true)
        return normal to hashed
    }

    private fun loadItemPalette(context: Context, filename: String): DefinitionRegistry<ItemDefinition> {
        val stream = context.assets.open("nbt/$filename")
        val json = stream.bufferedReader().use { it.readText() }
        val array = org.json.JSONArray(json)

        val map = Int2ObjectOpenHashMap<NbtItemDefinition>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            map.put(i, NbtItemDefinition(i, name))
        }
        return NbtItemDefinitionRegistry(map)
    }

    private fun loadVersion(protocolVersion: Int): VersionedDefinitions? {
        val ctx = appContext ?: return null
        val pair = fileMap[protocolVersion] ?: return null
        return try {
            val (blocks, blocksHashed) = blockPaletteCache.computeIfAbsent(pair.blockFile) {
                loadBlockPalette(ctx, pair.blockFile)
            }
            val items = loadItemPalette(ctx, pair.itemFile)
            VersionedDefinitions(protocolVersion, blocks, blocksHashed, items)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Diagnostics: which data files a given protocol resolves to, and whether the
     * exact version exists or a fallback was used. Exists so "does this build
     * really support 1.21.111?" is answerable from the device log instead of by
     * reading this file.
     */
    fun describeClosest(protocolVersion: Int): String {
        val versions = sortedVersions.toList()
        val exact = versions.contains(protocolVersion)
        val chosen = versions.firstOrNull { it <= protocolVersion } ?: versions.lastOrNull()
        val pair = chosen?.let { fileMap[it] }
        return "definitions: exact=$exact chosen=v${chosen ?: -1} " +
            "block=${pair?.blockFile ?: "?"} item=${pair?.itemFile ?: "?"} " +
            "known=${versions.take(6).joinToString(",")}"
    }

    fun getClosestDefinitions(protocolVersion: Int): VersionedDefinitions {
        loadedCache[protocolVersion]?.let { return it }

        check(sortedVersions.isNotEmpty()) {
            "Hiçbir definitions versiyonu yüklenemedi — assets/nbt/ içeriğini kontrol et"
        }

        val closest = sortedVersions.firstOrNull { it <= protocolVersion } ?: sortedVersions.last()

        return loadedCache.computeIfAbsent(closest) { v ->
            loadVersion(v) ?: error("$v için definitions yüklenemedi")
        }
    }

    class NbtBlockDefinitionRegistry(
        definitions: List<NbtMap>,
        hashed: Boolean
    ) : DefinitionRegistry<BlockDefinition> {

        private val map = Int2ObjectOpenHashMap<NbtBlockDefinition>()

        // FIX: hashed modda runtimeId'ler rastgele/negatif int'ler oldugu için
        // "0..20000 arasını sırayla dene" tarzı brute-force scan (PlacementUtil'in
        // eski yaklaşımı) pratikte hiçbir şey bulamıyordu. İsimle O(1) erişim için
        // ayrı bir harita — bir blok isminin İLK (varsayılan state) tanımı saklanıyor,
        // tıpkı eski index-tabanlı aramanın "ilk eşleşen identifier" davranışı gibi.
        private val byName = HashMap<String, NbtBlockDefinition>()

        init {
            var counter = 0
            for (def in definitions) {
                val runtimeId = if (hashed) createHash(def) else counter++
                val entry = NbtBlockDefinition(runtimeId, def)
                map.put(runtimeId, entry)
                val name = def.getString("name")
                if (!name.isNullOrEmpty()) byName.putIfAbsent(name, entry)
            }
        }

        fun findByName(name: String): BlockDefinition? = byName[name]

        override fun getDefinition(runtimeId: Int): BlockDefinition? = map.get(runtimeId)

        override fun isRegistered(definition: BlockDefinition?): Boolean =
            definition != null && map.get(definition.runtimeId) == definition

        @JvmRecord
        data class NbtBlockDefinition(val runtimeId: Int, val tag: NbtMap) : BlockDefinition {
            override fun getRuntimeId(): Int = runtimeId
        }

        private fun createHash(map: NbtMap): Int {
            val name = map.getString("name") ?: ""
            val states = map.getCompound("states")
            val stateStr = states?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { "${it.key}=${it.value}" } ?: ""
            val key = "$name:$stateStr"
            var hash = -0x7ee3779b
            for (c in key) {
                hash = hash xor c.code
                hash *= 0x01000193.toInt()
            }
            return hash
        }
    }

    class NbtItemDefinitionRegistry(
        private val map: Int2ObjectOpenHashMap<NbtItemDefinition>
    ) : DefinitionRegistry<ItemDefinition> {
        override fun getDefinition(runtimeId: Int): ItemDefinition? = map.get(runtimeId)
        override fun isRegistered(definition: ItemDefinition?): Boolean =
            definition != null && map.get(definition.runtimeId) == definition
    }

    @JvmRecord
    data class NbtItemDefinition(val runtimeId: Int, val identifier: String) : ItemDefinition {
        override fun getRuntimeId(): Int = runtimeId
        override fun getIdentifier(): String = identifier
        override fun isComponentBased(): Boolean = false
    }
}
