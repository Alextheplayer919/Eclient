package com.rubidiumclient.utils

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket

object ChunkParser {

    private const val TAG = "ChunkParser"
    private const val MAX_SUBCHUNKS = 64
    private const val MAX_SCAN_ATTEMPTS = 512

    data class ParsedBlockEntity(val x: Int, val y: Int, val z: Int, val tag: NbtMap)

    fun extractBlockEntities(pkt: LevelChunkPacket, subChunkCount: Int? = null): List<ParsedBlockEntity> {
        val original = readDataField(pkt) ?: run {
            return emptyList()
        }

        val buf = original.duplicate()

        return try {
            val count = subChunkCount ?: resolveSubChunkCount(pkt) ?: run {
                -1
            }

            if (count >= 0) skipKnownSubChunks(buf, count) else skipSubChunksByVersionByte(buf)

            val direct = tryParseCompoundsFrom(buf.duplicate())
            if (direct.isNotEmpty()) return direct

            scanForCompounds(buf)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readDataField(pkt: LevelChunkPacket): ByteBuf? {

        try { return pkt.data } catch (_: Throwable) {}
        return try {
            val m = pkt.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }
            m?.invoke(pkt) as? ByteBuf
        } catch (_: Exception) { null }
    }

    // FIX (Xray'in hiçbir sey bulamamasinin asil sebebi): burada sadece reflection
    // method isimleri deneniyordu (getSubChunkCount/getSubChunkLimit/getSectionCount),
    // gercek LevelChunkPacket sinifinda bu isimler yok — WorldBlockTracker'in
    // resolveSubChunkCount'unda oldugu gibi asil calisan kaynak direkt
    // pkt.subChunksLength property'si. Bu deneme hic yapilmadigi icin bu fonksiyon
    // her zaman null donuyor ve extractOreBlocks/extractBlockEntities her paket
    // icin bos liste ile hemen cikiyordu.
    private fun resolveSubChunkCount(pkt: LevelChunkPacket): Int? {
        val direct = try { pkt.subChunksLength } catch (_: Throwable) { 0 }
        if (direct in 1..MAX_SUBCHUNKS) return direct

        for (methodName in listOf("getSubChunkCount", "getSubChunkLimit", "getSectionCount")) {
            try {
                val m = pkt.javaClass.getMethod(methodName)
                val v = m.invoke(pkt)
                if (v is Int && v in 0..MAX_SUBCHUNKS) return v
            } catch (_: Exception) {}
        }
        return null
    }

    private fun skipKnownSubChunks(buf: ByteBuf, count: Int) {
        repeat(count.coerceAtMost(MAX_SUBCHUNKS)) {
            if (!buf.isReadable) return
            skipOneSubChunk(buf)
        }
    }

    private fun skipSubChunksByVersionByte(buf: ByteBuf) {
        var i = 0
        while (buf.isReadable && i < MAX_SUBCHUNKS) {
            buf.markReaderIndex()
            val version = buf.readUnsignedByte().toInt()
            if (version != 1 && version != 8 && version != 9) {
                buf.resetReaderIndex()
                return
            }
            buf.resetReaderIndex()
            if (!trySkipOneSubChunk(buf)) return
            i++
        }
    }

    private fun trySkipOneSubChunk(buf: ByteBuf): Boolean = try {
        skipOneSubChunk(buf); true
    } catch (_: Exception) { false }

    private fun skipOneSubChunk(buf: ByteBuf) {
        val version = buf.readUnsignedByte().toInt()
        when (version) {
            1 -> skipBlockStorage(buf)
            8, 9 -> {
                val storageCount = buf.readUnsignedByte().toInt()
                if (version == 9) buf.readByte()
                repeat(storageCount) { skipBlockStorage(buf) }
            }
            else -> throw IllegalStateException("Tanınmayan subchunk version=$version")
        }
    }

    private fun skipBlockStorage(buf: ByteBuf) {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1

        if (isPersistent) {
            skipPersistentPalette(buf, bitsPerBlock)
            return
        }

        if (bitsPerBlock == 0) {

            readUnsignedVarInt(buf)
            return
        }

        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
        buf.skipBytes(wordCount * 4)

        val paletteSize = readUnsignedVarInt(buf)
        repeat(paletteSize) { readUnsignedVarInt(buf) }
    }

    private fun readUnsignedVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.readUnsignedByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalStateException("VarInt çok uzun")
        }
        return result
    }

    // FIX (Xray'in bazı yükseklikte cevher bulamamasının sebebi): persistent
    // (NBT) paletli bir subchunk'a rastlanınca eskiden exception fırlatılıyordu,
    // bu da extractOreBlocks/extractBlockEntities'in dış catch'ine düşüp o
    // chunk sütunundaki DAHA YÜKSEK subchunk'ların hiç işlenmemesine sebep
    // oluyordu. Artık bu bloğu (WorldBlockTracker'daki aynı yaklaşımla) doğru
    // NBT reader ile atlayıp buffer'ı hizalı tutuyoruz — bu tek subchunk'ın
    // kendi cevherleri hâlâ bulunamıyor (nadir, özel bloklar), ama sütunun
    // geri kalanı artık kaybolmuyor.
    // FIX (ASIL KÖK SEBEP - "zaten var olan sandık ESP'de görünmüyor" bug'ı):
    // Bu fonksiyon her palet elemanı için AYRI bir ByteBufInputStream + NBT
    // reader açıp `.use {}` ile hemen kapatıyordu - WorldBlockTracker.kt'de
    // (baba.txt trace'iyle) bulunup düzeltilen AYNI bug burada da vardı.
    // Kapanış, altındaki PAYLAŞILAN `buf`'ı release ediyor VE/VEYA reader'ın
    // altındaki stream katmanı tek bir NBT tag için gereğinden çok byte'ı
    // greedy okuyup atıyordu. paletteSize kadar tekrarlanınca buffer'ın
    // konumu tamamen kayıyordu - bu fonksiyon sadece "atlama" (skip) yaptığı
    // için sonuç hiç kontrol edilmiyordu, ama ARDINDAN çağrılan
    // tryParseCompoundsFrom/scanForCompounds artık YANLIŞ pozisyondan
    // okumaya başlıyor, gerçek block-entity NBT'lerini (sandık/spawner)
    // bulamıyor ve extractBlockEntities sessizce boş liste dönüyordu -
    // hem de bu try/catch'in İÇİNDE olduğu için hiçbir hata/log de
    // görünmüyordu. Deepslate/yeni nesil terrain'de persistent palet çok
    // yaygın olduğundan, bu chunk'lardaki TÜM mevcut sandıklar hiç
    // algılanmıyordu - sadece BlockEntityDataPacket ile CANLI gelen
    // (yeni yerleştirilen) bloklar handleBlockEntity üzerinden senkron
    // eklendiği için görünüyordu.
    //
    // Fix: stream + reader döngü dışında BİR KEZ açılıyor, paletteSize kadar
    // AYNI reader tekrar tekrar kullanılıyor, hiçbir zaman close edilmiyor.
    private fun skipPersistentPalette(buf: ByteBuf, bitsPerBlock: Int) {
        if (bitsPerBlock > 0) {
            val blocksPerWord = 32 / bitsPerBlock
            val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
            buf.skipBytes(wordCount * 4)
        }
        val paletteSize = readUnsignedVarInt(buf)
        if (paletteSize <= 0 || paletteSize > 8192) throw IllegalStateException("Geçersiz persistent palette boyutu")

        val stream = ByteBufInputStream(buf)
        val reader = NbtUtils.createNetworkReader(stream)
        repeat(paletteSize) {
            reader.readTag()
        }
    }

    // FIX (aynı bug sınıfı - çoklu sandık/tile-entity içeren chunk'lar için):
    // Eskiden her compound (her sandık/spawner NBT'si) için readOneCompound()
    // ÇAĞRILIYORDU ve o da kendi içinde YENİ bir stream+reader açıp kapatıyordu.
    // Bir chunk'ta 2+ block-entity varsa, ilk compound okunduktan sonra stream
    // kapanınca buf'un konumu (aynı palet bug'ındaki gibi) kayabiliyor/buf
    // release edilebiliyordu - İKİNCİ ve sonraki sandıklar bu yüzden hiç
    // bulunamıyordu. Artık tek bir reader tüm compound dizisi boyunca
    // yeniden kullanılıyor.
    private fun tryParseCompoundsFrom(buf: ByteBuf): List<ParsedBlockEntity> {
        val result = mutableListOf<ParsedBlockEntity>()
        if (!buf.isReadable) return result
        try {
            val stream = ByteBufInputStream(buf)
            val reader = NbtUtils.createNetworkReader(stream)
            while (buf.isReadable) {
                val tag = reader.readTag() as? NbtMap ?: break
                toBlockEntity(tag)?.let { result.add(it) }
            }
        } catch (_: Exception) {

        }
        return result
    }

    private fun scanForCompounds(buf: ByteBuf): List<ParsedBlockEntity> {
        val result = mutableListOf<ParsedBlockEntity>()
        var attempts = 0
        val start = buf.readerIndex()
        val end = buf.writerIndex()
        var pos = start

        while (pos < end - 1 && attempts < MAX_SCAN_ATTEMPTS) {

            if (buf.getByte(pos) == 0x0A.toByte() && buf.getByte(pos + 1) == 0x00.toByte()) {
                attempts++
                val dup = buf.duplicate()
                dup.readerIndex(pos)
                try {
                    val tag = readOneCompound(dup)
                    if (tag != null) {
                        toBlockEntity(tag)?.let { result.add(it) }
                        pos = dup.readerIndex()
                        continue
                    }
                } catch (_: Exception) {  }
            }
            pos++
        }
        return result
    }

    // FIX: `.use {}` kaldırıldı - kapanış, scanForCompounds'un çağırdığı
    // dup (buf.duplicate()) ile PAYLAŞILAN underlying buffer'ı release
    // edebiliyordu (duplicate() ayrı bir refCnt tutmaz, orijinal buf'la
    // paylaşır). Bu fonksiyon sadece TEK bir compound okuyup dönmeli,
    // stream/reader'ı kapatmak bizim işimiz değil.
    private fun readOneCompound(buf: ByteBuf): NbtMap? {
        if (!buf.isReadable) return null
        val stream = ByteBufInputStream(buf)
        val reader = NbtUtils.createNetworkReader(stream)
        val tag = reader.readTag()
        return tag as? NbtMap
    }

    private fun toBlockEntity(tag: NbtMap): ParsedBlockEntity? {
        val x = tag.getInt("x", Int.MIN_VALUE)
        val y = tag.getInt("y", Int.MIN_VALUE)
        val z = tag.getInt("z", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || z == Int.MIN_VALUE) return null
        return ParsedBlockEntity(x, y, z, tag)
    }

    data class OreHit(val x: Int, val y: Int, val z: Int, val runtimeId: Int)

    // Overworld (1.18+) section indeksi -4'ten başlar => dünya Y -64.
    // Nether/End farklı taban kullanır (0'dan başlar) — dimension'a göre bunu
    // çağıran taraf override etmeli, burada default Overworld varsayılıyor.
    const val OVERWORLD_MIN_SECTION = -4

    /**
     * Chunk içindeki hedef bloklarını (isTarget(runtimeId) == true olanları) world
     * koordinatlarıyla döner. Set<Int> yerine predicate kullanıyoruz çünkü ore runtime
     * ID'leri artık StartGamePacket'ten önceden statik bir palet listesiyle değil,
     * OreTracker tarafından ilk görüldüğünde tembel (lazy) çözülüyor — bu da
     * block-network-ID hashing kullanan sunucularda (StartGamePacket'te palet listesi
     * hiç gelmeyebilir) doğru çalışır. Predicate her subchunk'ın palette dizisindeki
     * benzersiz girişler için bir kez çağrılır (4096 blok için değil), bu yüzden
     * maliyeti ihmal edilebilir düzeydedir.
     * Persistent (NBT) palette formatlı subchunk'lar hâlâ cevher üretmiyor (o
     * bölüm atlanıyor), ama artık sütundaki üst subchunk'ların işlenmesini
     * engellemiyor — bkz. skipPersistentPalette.
     */
    fun extractOreBlocks(
        pkt: LevelChunkPacket,
        isTarget: (Int) -> Boolean,
        minSectionIndex: Int = OVERWORLD_MIN_SECTION,
        subChunkCount: Int? = null
    ): List<OreHit> {
        val original = readDataField(pkt) ?: return emptyList()
        val buf = original.duplicate()

        val chunkX = pkt.chunkX
        val chunkZ = pkt.chunkZ
        val count = subChunkCount ?: resolveSubChunkCount(pkt) ?: return emptyList()

        val result = mutableListOf<OreHit>()
        return try {
            var sectionIndex = minSectionIndex
            repeat(count.coerceAtMost(MAX_SUBCHUNKS)) {
                if (buf.isReadable) {
                    decodeOneSubChunk(buf, chunkX, chunkZ, sectionIndex * 16, isTarget, result)
                }
                sectionIndex++
            }
            result
        } catch (e: Exception) {
            result // o ana kadar toplanan sonuçları döndür
        }
    }

    private fun decodeOneSubChunk(
        buf: ByteBuf, chunkX: Int, chunkZ: Int, baseY: Int,
        isTarget: (Int) -> Boolean, out: MutableList<OreHit>
    ) {
        val version = buf.readUnsignedByte().toInt()
        when (version) {
            1 -> decodeBlockStorage(buf, chunkX, chunkZ, baseY, isTarget, out)
            8, 9 -> {
                val storageCount = buf.readUnsignedByte().toInt()
                if (version == 9) buf.readByte()
                repeat(storageCount) { layer ->
                    // 0. katman gerçek bloklar, sonraki katmanlar genelde su/waterlogging — atla
                    if (layer == 0) decodeBlockStorage(buf, chunkX, chunkZ, baseY, isTarget, out)
                    else skipBlockStorage(buf)
                }
            }
            else -> throw IllegalStateException("Tanınmayan subchunk version=$version")
        }
    }

    private fun decodeBlockStorage(
        buf: ByteBuf, chunkX: Int, chunkZ: Int, baseY: Int,
        isTarget: (Int) -> Boolean, out: MutableList<OreHit>
    ) {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1

        if (isPersistent) {
            skipPersistentPalette(buf, bitsPerBlock)
            return
        }

        if (bitsPerBlock == 0) {
            // Tüm subchunk tek bir blok (genelde air/stone) — cevher olma ihtimali pratikte yok, atla.
            readUnsignedVarInt(buf)
            return
        }

        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
        val mask = (1 shl bitsPerBlock) - 1

        val words = IntArray(wordCount)
        for (w in 0 until wordCount) words[w] = buf.readIntLE()

        val paletteSize = readUnsignedVarInt(buf)
        val palette = IntArray(paletteSize)
        for (p in 0 until paletteSize) palette[p] = readUnsignedVarInt(buf)

        // Predicate'i sadece paletin benzersiz girişleri için çağırıyoruz (tipik olarak
        // birkaç ile birkaç yüz arası), 4096 voksel için değil.
        val paletteIsTarget = BooleanArray(paletteSize) { isTarget(palette[it]) }

        for (idx in 0 until 4096) {
            val w = idx / blocksPerWord
            val slot = idx % blocksPerWord
            val paletteIndex = (words[w] ushr (slot * bitsPerBlock)) and mask
            if (paletteIndex >= palette.size) continue
            if (!paletteIsTarget[paletteIndex]) continue

            // FIX (BİLEREK BIRAKILAN BUG - devamı): bu yorum önceden
            // WorldBlockTracker.getBlockIdentifier'ın DÜZELTİLMEDEN ÖNCEKİ
            // (bozuk) formülünü "doğru referans" diye göstermişti. idx burada
            // da (WorldBlockTracker'daki 'bi' gibi) ağdan gelen ham sıralı
            // index - gerçek Bedrock wire formatı X EN ANLAMLI bit, sonra Z,
            // en içte Y (idx = x<<8 | z<<4 | y). WorldBlockTracker'da bu artık
            // düzeltildi (idx = lx<<8 | lz<<4 | ly); burada da AYNI yöne
            // çevriliyor - önceki hâli X ve Y'yi yer değiştirmiş durumdaydı,
            // yani bulunan HER cevherin konumu (lx==ly köşegen durumlar hariç)
            // yanlış hesaplanıyordu.
            val lx = idx shr 8
            val lz = (idx shr 4) and 0xF
            val ly = idx and 0xF

            out.add(OreHit(chunkX * 16 + lx, baseY + ly, chunkZ * 16 + lz, palette[paletteIndex]))
        }
    }
}
