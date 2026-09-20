package com.rubidiumclient.utils

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import io.netty.buffer.ByteBuf
import com.rubidiumclient.core.proxy.EntityTracker
// NOT: DiagLog aynı paket (com.rubidiumclient.utils) içinde olduğu için
// import gerekmiyor - doğrudan kullanılabiliyor.
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

object WorldBlockTracker : PacketEventBus.PacketListener {

    private const val TAG = "WorldBlockTracker"
    private const val SECTION_BLOCKS = 4096

    private val sections = ConcurrentHashMap<Long, IntArray>()

    // ARTIK KULLANILMIYOR (bilerek boş bırakılıyor): network subchunk
    // formatında NBT-paletli storage hiç gelmez (bkz. readBlockStorage'daki
    // KESİN KÖK SEBEP notu) - bu harita hiçbir zaman doldurulmuyor. Kod,
    // getBlockIdentifier/forEachBlockInRange içindeki ölü-ama-zararsız
    // "<= -2 sentinel" kontrolleriyle tutarlı kalsın diye burada bırakıldı;
    // silinmesi güvenli ama gerekli değil.
    private val persistentPalettes = ConcurrentHashMap<Long, Array<String?>>()

    private val insertOrder = ConcurrentLinkedQueue<Long>()

    private const val MAX_SECTIONS = 4096

    private val overrides = ConcurrentHashMap<Long, Int>()

    private val identifierCache = ConcurrentHashMap<Int, String>()

    // Teşhis sayaçları: "hasTerrainData=false" durumunda TAM olarak hangi
    // aşamada chunk'ların atlandığını görmek için. searchPlaceBase/CrystalAura
    // hiçbir zemin bulamadığında bunları loga basarak kör tahmin yapmadan kök
    // sebebi tek seferde teşhis edebiliyoruz.
    private val statChunksReceived    = java.util.concurrent.atomic.AtomicInteger(0)
    private val statSkippedNoSubCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val statSkippedStreamed   = java.util.concurrent.atomic.AtomicInteger(0)
    private val statSkippedCaching    = java.util.concurrent.atomic.AtomicInteger(0)
    private val statSkippedNoBuf      = java.util.concurrent.atomic.AtomicInteger(0)
    private val statDecodeFailed      = java.util.concurrent.atomic.AtomicInteger(0)
    private val statSectionsStored    = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastException: String? = null
    @Volatile private var lastVersionByte: Int = -1

    // FIX (teşhis): decodeFailed sayacı SAYIYI veriyordu ama HANGİ chunk'ta,
    // hangi protokolde, hangi ham byte'larla başarısız olduğunu vermiyordu.
    // Ekranda görülen "unknown_version=85" gibi değerler sadece SON hatayı
    // tutan tekil alanlardı - önceki hatalar üzerine yazılıyordu. Artık her
    // benzersiz (version, protokol) kombinasyonu için EN AZ BİR TAM örnek
    // (chunk koordinatı + hex dump + subChunksLength + cachingEnabled) dosyaya
    // yazılıyor; aynı kombinasyon tekrar oluşursa (LOG_THROTTLE_MS) sıklıkla
    // değil seyrek olarak tekrar loglanıyor - hem dosya şişmiyor hem de gerçek
    // format teşhisi için yeterli örnek birikiyor.
    private val seenFailureKeys = ConcurrentHashMap<String, Long>()
    private const val FAILURE_LOG_THROTTLE_MS = 5000L

    // FIX (DAHA DETAYLI teşhis): tek bir subchunk'ın hex dump'ı yeterli
    // olmayabilir - offset kaymasının NEREDE başladığını görmek için o
    // chunk'taki TÜM subchunk denemelerinin (index, sy, offset, version,
    // tüketilen byte sayısı, başarı/başarısızlık) sırasını da istiyoruz.
    // Bu alanlar handleLevelChunkPacket tek thread'de (paket sırayla işlendiği
    // için) chunk başına set edilip decodeSubChunkBlocks/tryDecode içinden
    // okunuyor - global mutable state olması güvenli çünkü aynı anda sadece
    // bir LevelChunkPacket işleniyor.
    @Volatile private var curTrace: StringBuilder? = null
    @Volatile private var curChunkCx: Int = 0
    @Volatile private var curChunkCz: Int = 0
    @Volatile private var curChunkBufStart: Int = 0
    @Volatile private var curSubIndex: Int = -1
    @Volatile private var curSubSy: Int = 0
    // Bir önceki subchunk denemesinin tükettiği SON birkaç byte'ı tutar - bu,
    // "unknown_version" hatasında görülen byte'ın gerçek bir versiyon mu yoksa
    // bir önceki subchunk'ın kuyruğundan taşan çöp mü olduğunu ayırt etmeyi
    // sağlar (geriye dönük bağlam olmadan ileri dump tek başına yetersiz).
    @Volatile private var lastConsumedTailHex: String = "-"

    private fun logDecodeFailure(key: String, detail: String) {
        val now = System.currentTimeMillis()
        val last = seenFailureKeys[key]
        if (last != null && now - last < FAILURE_LOG_THROTTLE_MS) return
        seenFailureKeys[key] = now
        DiagLog.log("WorldBlockTracker", detail)
    }

    fun debugSummary(): String =
        "chunks=${statChunksReceived.get()} skip_noSubCount=${statSkippedNoSubCount.get()} " +
        "skip_streamed=${statSkippedStreamed.get()} " +
        "skip_caching=${statSkippedCaching.get()} skip_noBuf=${statSkippedNoBuf.get()} " +
        "decodeFailed=${statDecodeFailed.get()} sectionsStored=${statSectionsStored.get()} " +
        "lastEx=${lastException ?: "-"} lastVersionByte=$lastVersionByte"

    init {
        // NOT: init() çağrısının unutulması/gecikmesi durumuna karşı savunma —
        // WorldBlockTracker referans alınır alınmaz (örn. hasAnyTerrainData()
        // çağrısıyla) kendini otomatik kaydeder. register() idempotent olduğu
        // için dışarıdan ayrıca init() çağırmak hâlâ güvenli ve tavsiye edilir
        // (asıl kritik nokta: PacketEventBus.setSession(session) her yeni
        // bağlantıda çağrıldığında WorldBlockTracker.init() de çağrılmalı —
        // aksi halde PacketEventBus.clear() sonrası (reconnect) bu obje bir
        // daha ASLA yeniden kaydolmaz çünkü Kotlin object'i sadece bir kez
        // initialize olur).
        register()
    }

    fun init() = register()

    private fun register() {
        PacketEventBus.register(this)
    }

    fun reset() {
        sections.clear(); insertOrder.clear(); overrides.clear(); persistentPalettes.clear()
        statChunksReceived.set(0); statSkippedNoSubCount.set(0); statSkippedStreamed.set(0)
        statSkippedCaching.set(0)
        statSkippedNoBuf.set(0); statDecodeFailed.set(0); statSectionsStored.set(0)
        lastException = null
        lastVersionByte = -1
        seenFailureKeys.clear()
        curTrace = null
        lastConsumedTailHex = "-"
    }

    @Volatile private var loggedCacheOverride = false

    // KRİTİK FIX: KillAura.kt'deki headlock fix'iyle birebir aynı kök sebep —
    // event.cancelAndReplace(p) çağrılmadan yapılan mutation'lar hiçbir zaman
    // server'a ulaşmıyor, relay ham (decode edilmemiş) wire byte'larını gönderiyor.
    // Bu yüzden isSupported = false ataması burada yapılsa bile server hâlâ
    // isSupported = true olarak görüyordu, blob-cache modunda kalmaya devam
    // ediyordu ve LevelChunkPacket'ler cachingEnabled=true olarak gelmeye devam
    // ediyordu — handleLevelChunkPacket bu durumda hiçbir şeyi decode etmeden
    // return ediyor, yani sections HİÇBİR ZAMAN dolmuyordu (AutoMapArt "No chunk
    // data" hatasının asıl kaynağı buydu).
    private fun handleClientCacheStatus(event: PacketEvent, p: ClientCacheStatusPacket) {
        if (p.isSupported) {
            p.isSupported = false
            if (!loggedCacheOverride) {
                loggedCacheOverride = true
            }
            event.cancelAndReplace(p)
        }
    }

    override fun onPacket(event: PacketEvent) {
        when (val p = event.packet) {
            is SubChunkPacket -> handleSubChunkPacket(p)
            is LevelChunkPacket -> handleLevelChunkPacket(p)
            is UpdateBlockPacket -> handleUpdateBlock(p)
            is UpdateSubChunkBlocksPacket -> handleUpdateSubChunkBlocks(p)
            is ClientCacheStatusPacket -> handleClientCacheStatus(event, p)
            is ChangeDimensionPacket -> reset()
            // FIX (kök sebep): sunucu değişimi/transfer (örn. lobby -> oyun
            // sunucusu) ChangeDimensionPacket GÖNDERMEZ, StartGamePacket
            // gönderir. Bu case eksik olduğu için WorldBlockTracker (bir
            // Kotlin object - tek instance, asla yeniden yaratılmıyor) eski
            // sunucudan kalma section verisini hiç temizlemiyordu. Sonuç:
            // yeni dünyada hasAnyTerrainData()=true dönüyor ama koordinatlar
            // eski dünyaya ait - AnchorAura'daki "findPlacementSpot FAIL
            // hasTerrainData=true" ve saçma tx/tz loglarının kök sebebi budur.
            is StartGamePacket -> reset()
            else -> {}
        }
    }

    fun hasAnyTerrainData(): Boolean = sections.isNotEmpty()

    fun getBlockIdentifier(x: Int, y: Int, z: Int): String? {

        val posKey = blockPosKey(x, y, z)
        overrides[posKey]?.let { return resolveIdentifier(it) }

        val cx = x shr 4
        val cz = z shr 4
        val sy = y shr 4
        val key = sectionKey(cx, sy, cz)
        val arr = sections[key] ?: return null

        val lx = x and 15
        val ly = y and 15
        val lz = z and 15
        // FIX (BİLEREK BIRAKILAN BUG - ASIL KÖK SEBEP): idx formülü burada
        // (ly shl 8) or (lz shl 4) or lx idi - yani Y en yüksek anlamlı
        // bileşendi. Ama readBlockStorage()'daki YAZMA tarafı, ağdan gelen
        // bit'leri SIRAYLA okuyup indices[bi]'ye yazıyor - bu "bi" sırası
        // doğrudan Bedrock'un GERÇEK wire-format sırasını takip eder:
        // X EN DIŞ döngü, sonra Z, en içte Y (idx = x<<8 | z<<4 | y).
        // Yani yazma X-dış/Y-iç kullanırken okuma Y-dış/X-iç kullanıyordu -
        // X ve Y birbirine karışmıştı. lx==ly olan köşegen durumlar dışında
        // HER sorgu, aslında x ve y'nin yer değiştirdiği BAŞKA bir local
        // pozisyonun verisini döndürüyordu - AnchorAura/CrystalAura'nın
        // "yanlış pozisyonlarda arıyormuş gibi" davranmasının asıl sebebi
        // buydu, onların kendi arama mantığı zaten doğruydu.
        val idx = (lx shl 8) or (lz shl 4) or ly
        val runtimeId = arr[idx]

        // FIX: persistent-palette sentinel (<= -2). Normal runtime id'ler her
        // zaman >= 0 olduğu için bu aralık çakışmıyor. -1 eski "bilinmiyor"
        // sentinel'i olarak geriye dönük uyumluluk için hâlâ null döndürüyor.
        if (runtimeId <= -2) {
            val paletteIdx = -(runtimeId + 2)
            val names = persistentPalettes[key] ?: return null
            val name = names.getOrNull(paletteIdx)
            return if (name.isNullOrBlank()) null else name
        }
        if (runtimeId < 0) return null

        return resolveIdentifier(runtimeId)
    }

    fun isBlock(x: Int, y: Int, z: Int, vararg identifiers: String): Boolean {
        val id = getBlockIdentifier(x, y, z) ?: return false
        return identifiers.any { it == id }
    }

    // Xray/OreTracker için: nokta-nokta getBlockIdentifier() ile 64 blok
    // yarıçapında tarama ~500K çağrı gerektirir (yavaş). Bunun yerine yüklü
    // section'ları (16x16x16) doğrudan sections map'inden enumerate edip
    // her birinin içindeki 4096 slotu tek seferde tarıyoruz — section sayısı
    // tipik oyun alanında birkaç yüzü geçmez, bu yüzden çok daha hızlı.
    // callback (x,y,z,identifier) her eşleşen blok için çağrılır; override
    // ve persistent-palette durumları getBlockIdentifier ile aynı şekilde
    // ele alınır (tek kaynak-of-truth ayrışmasın diye mantık burada tekrar
    // edilir, fakat overrides haritası küçük olduğu için maliyeti önemsizdir).
    //
    // internal (public DEĞİL): inline fonksiyon private sections/overrides/
    // persistentPalettes/sectionKey/blockPosKey/resolveIdentifier'a erişiyor.
    // Kotlin, public inline fonksiyonların private sembollere erişmesine izin
    // vermez (ABI güvenliği — dışarıdan inlining yapıldığında private sembol
    // görünür olmazdı). internal olduğunda bu kısıtlama kalkar çünkü aynı
    // derleme modülü içinde her yerden zaten erişilebilir semboller.
    internal inline fun forEachBlockInRange(
        centerX: Int, centerY: Int, centerZ: Int, radius: Int,
        crossinline predicate: (String) -> Boolean,
        crossinline onMatch: (x: Int, y: Int, z: Int, identifier: String) -> Unit
    ) {
        val minCx = (centerX - radius) shr 4
        val maxCx = (centerX + radius) shr 4
        val minCz = (centerZ - radius) shr 4
        val maxCz = (centerZ + radius) shr 4
        val minSy = (centerY - radius) shr 4
        val maxSy = (centerY + radius) shr 4

        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                for (sy in minSy..maxSy) {
                    val key = sectionKey(cx, sy, cz)
                    val arr = sections[key] ?: continue
                    val baseX = cx shl 4
                    val baseY = sy shl 4
                    val baseZ = cz shl 4

                    // FIX: aynı X/Y karışıklığı burada da vardı (getBlockIdentifier
                    // ile tutarlıydı ama ikisi de yazma tarafına göre YANLIŞTI) -
                    // doğru wire-format sırası: idx = lx<<8 | lz<<4 | ly.
                    for (idx in 0 until SECTION_BLOCKS) {
                        val ly = idx and 15
                        val lz = (idx shr 4) and 15
                        val lx = (idx shr 8) and 15
                        val wx = baseX + lx
                        val wy = baseY + ly
                        val wz = baseZ + lz

                        val dx = wx - centerX
                        val dy = wy - centerY
                        val dz = wz - centerZ
                        if (dx * dx + dy * dy + dz * dz > radius * radius) continue

                        val posKey = blockPosKey(wx, wy, wz)
                        val override = overrides[posKey]
                        val identifier = if (override != null) {
                            resolveIdentifier(override)
                        } else {
                            val runtimeId = arr[idx]
                            if (runtimeId <= -2) {
                                val paletteIdx = -(runtimeId + 2)
                                val names = persistentPalettes[key]
                                names?.getOrNull(paletteIdx)?.takeIf { it.isNotBlank() }
                            } else if (runtimeId < 0) {
                                null
                            } else {
                                resolveIdentifier(runtimeId)
                            }
                        } ?: continue

                        if (predicate(identifier)) onMatch(wx, wy, wz, identifier)
                    }
                }
            }
        }
    }

    fun hasData(x: Int, y: Int, z: Int): Boolean {
        if (overrides.containsKey(blockPosKey(x, y, z))) return true
        val cx = x shr 4; val cz = z shr 4; val sy = y shr 4
        return sections.containsKey(sectionKey(cx, sy, cz))
    }

    private val WATER_IDS = arrayOf(
        "minecraft:water", "minecraft:flowing_water", "minecraft:bubble_column"
    )

    // Oyuncunun gerçekten suya değip değmediğini dünya blok verisinden (gerçek
    // zemin/blok kontrolü) okuyoruz — sadece yağmur/hava durumuna değil,
    // fiziksel olarak suda olup olmamaya bakan güvenilir kontrol bu.
    // Hem ayak hem göz hizası kontrol edilir: yüzeyde yüzerken (baş dışarıda)
    // veya tamamen dalmışken de "suda" sayılsın diye.
    fun isPlayerInWater(): Boolean {
        val bx = floor(EntityTracker.selfX).toInt()
        val bz = floor(EntityTracker.selfZ).toInt()
        val feetY = floor(EntityTracker.selfY).toInt()
        val eyeY  = floor(EntityTracker.selfY + 1.2f).toInt()

        if (!hasData(bx, feetY, bz) && !hasData(bx, eyeY, bz)) return false

        return isBlock(bx, feetY, bz, *WATER_IDS) || isBlock(bx, eyeY, bz, *WATER_IDS)
    }

    private fun handleUpdateBlock(p: UpdateBlockPacket) {
        if (p.dataLayer != 0) return
        val runtimeId = runCatching { p.definition?.runtimeId }.getOrElse { null } ?: return
        val pos = p.blockPosition ?: return
        overrides[blockPosKey(pos.x, pos.y, pos.z)] = runtimeId
    }

    private fun handleUpdateSubChunkBlocks(p: UpdateSubChunkBlocksPacket) {
        for (entry in p.standardBlocks) {
            val runtimeId = runCatching { entry.definition?.runtimeId }.getOrElse { null } ?: continue
            val pos = entry.position ?: continue
            overrides[blockPosKey(pos.x, pos.y, pos.z)] = runtimeId
        }
    }

    private fun handleSubChunkPacket(p: SubChunkPacket) {
        // KRİTİK FIX: sub.position mutlak section koordinatı DEĞİL — paketin
        // origin'ine (isteğin atıldığı merkez konum) göre RELATİF bir offset
        // (dx,dy,dz). Bunu doğrudan mutlak kabul edip storeSection'a vermek,
        // bloğu tamamen yanlış bir section key'i altında saklıyordu (origin'e
        // yakın küçük delta değerleri altında — yani pratikte çoğunlukla
        // self'in kendi konumuna yakın bir yerde). Sonucunda hedefin GERÇEK
        // yüksekliği sorgulandığında ya hiç veri bulunamıyordu (aynı seviye:
        // delta farklı bir key'e denk geliyordu) ya da tesadüfen self'in kendi
        // seviyesindeki veriyle eşleşiyordu (hedef bir seviye fark edince) —
        // CrystalAura'nın "hedefin seviyesi yerine benim seviyeme kristal
        // koyuyor" bulgusunun asıl kök nedeni buydu.
        val origin = resolveSubChunkOrigin(p)
        for (sub in p.subChunks) {
            try {
                val rel = sub.position ?: continue
                val buf = sub.data ?: continue
                if (!buf.isReadable) continue

                val decoded = decodeSubChunkBlocks(buf.duplicate())
                if (decoded == null) continue

                storeSection(origin.x + rel.x, origin.y + rel.y, origin.z + rel.z, decoded.first, decoded.second)
            } catch (e: Exception) {
            }
        }
    }

    // Kütüphane sürümüne göre alan adı değişebildiğinden resolveSubChunkCount
    // ile aynı savunmacı reflection yaklaşımı: birkaç olası isim deneniyor.
    private fun resolveSubChunkOrigin(p: SubChunkPacket): org.cloudburstmc.math.vector.Vector3i {
        for (name in listOf("centerPosition", "position", "origin", "basePosition")) {
            try {
                val m = p.javaClass.getMethod(name)
                val v = m.invoke(p)
                if (v is org.cloudburstmc.math.vector.Vector3i) return v
            } catch (_: Exception) {}
        }
        return org.cloudburstmc.math.vector.Vector3i.ZERO
    }

    // FIX: bu fonksiyon eskiden 'private' idi. GamingPacketListener.handleChunk()
    // içinden çağrılması gerektiği için (chunk verisi buraya beslenmezse
    // WorldBlockTracker hep boş kalır, "havada kalma" bug'ının kaynağıydı)
    // erişim seviyesi public'e çekildi.
    fun handleLevelChunkPacket(p: LevelChunkPacket) {
        statChunksReceived.incrementAndGet()
        try {
            val cachingEnabled = p.isCachingEnabled()
            // FIX: Önceden cachingEnabled=true geldiğinde chunk tamamen atlanıyordu
            // (return). Modern sunucuların çoğu blob-caching'i varsayılan açar ve
            // handleClientCacheStatus'un isSupported=false zorlaması her zaman işe
            // yaramaz (paket relay araya girmeden önce gönderilmiş olabilir, ya da
            // sunucu client'ın talebini yok sayıp kendi kararına göre cache açabilir).
            // cachingEnabled=true olsa bile LevelChunkPacket.data alanı CloudburstMC
            // protokolünde çoğunlukla hâlâ ham blok verisini taşır (sadece biome
            // blob-hash'leri ayrı bir mekanizmayla senkronize edilir) — o yüzden
            // burada artık atlamıyoruz, decode etmeyi deniyoruz. Gerçekten decode
            // edilemezse (blob-only format) storedAny=false kalır ve statDecodeFailed
            // sayaç olarak işaretlenir, bu da debugSummary()'de görülebilir olur.
            if (cachingEnabled) statSkippedCaching.incrementAndGet()

            // FIX (KESİN KÖK SEBEP — LevelChunkPacket.java kaynağıyla doğrulandı):
            // requestSubChunks=true (v471+) olan modern paketlerde `data` alanı
            // SADECE biome/border-block verisi taşır — asıl blok verisi bu pakette
            // hiç yok. İstemci gerçek blokları ayrı SubChunkRequestPacket/
            // SubChunkPacket alışverişiyle çekiyor (Bedrock'un ~1.18'den beri
            // kullandığı "sub-chunk streaming" akışı; bkz. handleSubChunkPacket).
            // Eskiden bu durumda subChunksLength/subChunkLimit ikisi de 0 çıktığı
            // için kod "unknown count, tahmin et" fallback'ine düşüp `data`'yı
            // zorla eski-stil ham blok verisi sanıp decode etmeye çalışıyordu —
            // ilk byte (biome paletiyle tamamen alakasız bir şey) "subchunk
            // version" sanılıp okunuyordu (baba.txt: version=5, sonra version=0,
            // hep CHUNK_DECODE_FAILED). Artık requestSubChunks=true ise data hiç
            // decode edilmeye ÇALIŞILMIYOR — gerçek blok verisi zaten ayrıca
            // gelen SubChunkPacket'lerden (handleSubChunkPacket) doluyor.
            if (p.isRequestSubChunks) {
                statSkippedStreamed.incrementAndGet()
                return
            }

            val buf = p.data
            if (buf == null || !buf.isReadable) { statSkippedNoBuf.incrementAndGet(); return }

            val subChunksLength = resolveSubChunkCount(p)

            val cx = p.chunkX
            val cz = p.chunkZ
            val dim = EntityTracker.selfDimension
            val minSectionY = if (dim == 0) -4 else 0

            val dup = buf.duplicate()
            var storedAny = false

            // Bu chunk için tanı state'ini başlat
            val trace = StringBuilder()
            curTrace = trace
            curChunkCx = cx
            curChunkCz = cz
            curChunkBufStart = dup.readerIndex()
            lastConsumedTailHex = "-"

            if (subChunksLength > 0) {
                var consecutiveErrors = 0
                for (i in 0 until subChunksLength) {
                    if (!dup.isReadable) { trace.append("i=$i EOF\n"); break }
                    val fallbackSy = minSectionY + i
                    curSubIndex = i; curSubSy = fallbackSy
                    val decoded = decodeSubChunkBlocks(dup)
                    if (decoded == null) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) break
                        continue
                    }
                    // FIX (kök sebep): sy artık paketin KENDİ yIndex byte'ından (decoded.third)
                    // alınıyor - varsa. Eskiden her zaman minSectionY+i "tahmini" kullanılıyordu;
                    // sunucu subchunk'ları beklenen sırayla/boşluksuz göndermezse (yaygın:
                    // boş üst section'lar hiç gönderilmez) bu tahmin kayar ve zemin verisi
                    // yanlış section key'i altında saklanırdı - decode BAŞARILI görünür ama
                    // yanlış yükseklikteki veri döner (örn. gerçek zeminin yerine gökyüzü/air).
                    val sy = decoded.third ?: fallbackSy
                    if (decoded.first.isEmpty()) {
                        // Air sentinel: bu section tamamen hava dolu.
                        // Eskiden storeSection hiç çağrılmıyordu → hasData() false dönüyor,
                        // getBlockIdentifier() null dönüyor → CrystalAura "above bilinmiyor"
                        // sanıp yanlış kararlar veriyordu (air olan yere kristal koyamıyordu).
                        // Artık 4096 sıfır (runtimeId=0 = minecraft:air) kaydediyoruz.
                        storeSection(cx, sy, cz, IntArray(SECTION_BLOCKS), null)
                        consecutiveErrors = 0; continue
                    }
                    consecutiveErrors = 0
                    storeSection(cx, sy, cz, decoded.first, decoded.second)
                    statSectionsStored.incrementAndGet()
                    storedAny = true
                }
            } else {
                statSkippedNoSubCount.incrementAndGet()
                var sy = minSectionY
                var consecutiveErrors = 0
                var i = 0
                while (dup.isReadable && sy < minSectionY + 24) {
                    curSubIndex = i; curSubSy = sy
                    val decoded = decodeSubChunkBlocks(dup)
                    if (decoded == null) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) break
                        sy++; i++; continue
                    }
                    val realSy = decoded.third ?: sy
                    if (decoded.first.isEmpty()) {
                        storeSection(cx, realSy, cz, IntArray(SECTION_BLOCKS), null)
                        sy++; i++; consecutiveErrors = 0; continue
                    } // air
                    consecutiveErrors = 0
                    storeSection(cx, realSy, cz, decoded.first, decoded.second)
                    statSectionsStored.incrementAndGet()
                    storedAny = true
                    sy++; i++
                }
            }

            if (!storedAny) {
                // Tüm subchunk'lar air sentinel döndürdüyse (blocks=0, tamamen boş chunk)
                // bu bir decode hatası değil, geçerli bir "gökyüzü/boş bölge" chunk'ı.
                // Sadece gerçek decode hataları (null dönen, exception fırlatan) loglanmalı.
                val traceStr = trace.toString()
                val allAir = traceStr.lines()
                    .filter { it.startsWith("i=") }
                    .all { it.contains("blocks=0 ok=true") }

                if (!allAir) {
                    statDecodeFailed.incrementAndGet()
                    val protocol = runCatching { PacketEventBus.currentSession?.activeCodec?.protocolVersion }.getOrNull()
                    val fullHex = run {
                        val hexBuf = buf.duplicate()
                        val n = minOf(512, hexBuf.readableBytes())
                        (0 until n).joinToString(" ") {
                            hexBuf.getUnsignedByte(hexBuf.readerIndex() + it).toString(16).padStart(2, '0')
                        }
                    }
                    logDecodeFailure(
                        "chunk_no_store",
                        "CHUNK_DECODE_FAILED cx=$cx cz=$cz dim=$dim protocol=$protocol subChunksLength=$subChunksLength " +
                            "cachingEnabled=$cachingEnabled bufSize=${buf.readableBytes()} lastEx=$lastException lastVersionByte=$lastVersionByte\n" +
                            "TRACE:\n$traceStr" +
                            "FULL_HEX(first ${minOf(512, buf.readableBytes())} bytes):\n$fullHex"
                    )
                }
                // allAir=true ise sessizce atla: bu chunk haritada var ama tamamen hava dolu,
                // hata değil.
            }
            curTrace = null
        } catch (e: Exception) {
            lastException = "${e::class.simpleName}: ${e.message}"
            statDecodeFailed.incrementAndGet()
            logDecodeFailure(
                "chunk_exception_${e::class.simpleName}",
                "CHUNK_DECODE_EXCEPTION cx=${runCatching { p.chunkX }.getOrNull()} cz=${runCatching { p.chunkZ }.getOrNull()} " +
                    "${e::class.simpleName}: ${e.message}\nTRACE:\n${curTrace ?: "-"}"
            )
            curTrace = null
        }
    }

    private fun resolveSubChunkCount(p: LevelChunkPacket): Int {
        // Field dump'tan kesin bilinen isimler: subChunksLength=21, subChunkLimit=0
        // Önce getter dene (Lombok @Data: getSubChunksLength)
        for (methodName in listOf("getSubChunksLength", "getSubChunkLimit", "getSubChunkCount", "getSectionCount")) {
            try {
                val v = p.javaClass.getMethod(methodName).invoke(p)
                if (v is Int && v > 0) return v
            } catch (_: Exception) {}
        }
        // Kotlin property erişimi başarısız olabiliyor — reflection ile tüm class hiyerarşisini tara
        var cls: Class<*>? = p.javaClass
        while (cls != null) {
            for (fieldName in listOf("subChunksLength", "subChunkLimit", "subChunkCount", "sectionCount")) {
                try {
                    val f = cls.getDeclaredField(fieldName)
                    f.isAccessible = true
                    val v = f.get(p)
                    if (v is Int && v > 0) return v
                } catch (_: Exception) {}
            }
            cls = cls.superclass
        }
        return -1
    }

    private fun storeSection(cx: Int, sy: Int, cz: Int, blocks: IntArray, names: Array<String?>?) {
        val key = sectionKey(cx, sy, cz)

        // BUG FIX (CrystalAura "sadece eski yere koyuyor" / AnchorAura hiç
        // çalışmıyor kök sebebi): bu fonksiyon her subchunk update'inde
        // (aynı chunk defalarca güncellenebilir - LevelChunkPacket + sonraki
        // SubChunkPacket'ler) koşulsuz insertOrder.add(key) yapıyordu. Aynı
        // key defalarca kuyruğa giriyor, insertOrder.size gerçek benzersiz
        // section sayısından (sections.size) çok daha hızlı büyüyordu
        // (debugSummary'de "sectionsStored=8586" ama MAX_SECTIONS=4096 —
        // aradaki fark tamamen duplicate kayıt). Eviction FIFO poll() bu
        // duplicate ESKİ girdilerden birini çektiğinde, o key'in SONRADAN
        // gelen TAZE verisini sections'tan siliyordu — halbuki key hâlâ
        // "sıcak" (yakın zamanda güncellenmiş) olmasına rağmen.
        //
        // Oyuncunun kendi etrafındaki chunk'lar tam olarak en sık update
        // alan chunk'lar olduğu için, bu bug'dan EN ÇOK ETKİLENEN veri her
        // zaman "oyuncunun tam o anki çevresi" oluyordu — chunks=1648,
        // sectionsStored=8586, hasTerrainData=true görünmesine rağmen
        // findPlacementSpot/getBlockIdentifier oyuncunun ayağının dibinde
        // sürekli NO_DATA dönmesinin sebebi buydu.
        //
        // Fix: gerçek LRU - key zaten kuyruktaysa önce eski konumunu
        // kaldırıp sona (en taze) yeniden ekliyoruz. Böylece insertOrder
        // her zaman sections ile 1:1 örtüşüyor ve eviction her zaman
        // GERÇEKTEN en eski, en az kullanılan section'ı siliyor.
        val isUpdate = sections.containsKey(key)
        sections[key] = blocks
        if (names != null) persistentPalettes[key] = names else persistentPalettes.remove(key)

        if (isUpdate) insertOrder.remove(key)
        insertOrder.add(key)

        while (insertOrder.size > MAX_SECTIONS) {
            val old = insertOrder.poll() ?: break
            sections.remove(old)
            persistentPalettes.remove(old)
        }
    }

    // FIX: version 9 subchunk formatı her zaman [version=9][storageCount][yIndex][storage...]
    // şeklinde gelir — yIndex byte'ı her zaman okunmalı. Önceki skipYByte fallback mantığı
    // hem gereksizdi hem de buf offset'ini bozuyordu. Tek tryDecode çağrısı yeterli.
    // FIX: version 9 subchunk formatı her zaman [version=9][storageCount][yIndex][storage...]
    // şeklinde gelir — yIndex byte'ı her zaman okunmalı. Önceki skipYByte fallback mantığı
    // hem gereksizdi hem de buf offset'ini bozuyordu. Tek tryDecode çağrısı yeterli.
    //
    // FIX (DAHA DETAYLI teşhis): her çağrı artık curTrace'e (varsa) tek satırlık bir
    // özet ekliyor: subchunk index/sy, chunk buffer'ı içindeki relatif offset, version
    // byte, tüketilen byte sayısı, başarılı mı. Ayrıca lastConsumedTailHex'i (bu
    // subchunk'ın tükettiği SON 8 byte) günceller - bir sonraki subchunk unknown_version
    // hatası verirse, bu tail'in geriye dönük bağlam olarak loglanabilmesi için.
    // FIX (kök sebep - vertical offset): dönüş tipi artık Triple(blocks, names, realSy).
    // realSy = paketin kendi içindeki yIndex byte'ından (version 9) okunan GERÇEK
    // section Y'si. Eskiden bu byte okunup ATILIYORDU ve sy = minSectionY + i (döngü
    // index'i) ile "tahmin" ediliyordu - bu varsayım subchunk'ların HER ZAMAN
    // minSectionY'den başlayıp boşluksuz/sırayla geldiğini varsayıyordu. Sunucu bu
    // sırayı bozarsa (örn. boş üst/alt section'lar atlanırsa, ya da bu dünyanın gerçek
    // yükseklik aralığı varsayılan -4..19'dan farklıysa) decode YİNE BAŞARILI olur
    // (bu yüzden baba.txt'de hiç decode hatası YOK) ama veri YANLIŞ section key'i
    // altında saklanır - örn. gerçekte zeminin olduğu section, gökyüzü/boşluk
    // hizasındaki başka bir section'ın verisiyle karışır. Sonuç: hasData()=true
    // ama getBlockIdentifier() o noktada "air" döner, tıpkı AnchorAura'nın
    // "normal zeminde ama her yer air" bulgusuyla birebir eşleşiyor.
    private fun decodeSubChunkBlocks(buf: ByteBuf): Triple<IntArray, Array<String?>?, Int?>? {
        if (buf.readableBytes() < 2) {
            curTrace?.append("i=$curSubIndex sy=$curSubSy EOF(readable=${buf.readableBytes()})\n")
            return null
        }
        val startIdx = buf.readerIndex()
        val relOffset = startIdx - curChunkBufStart
        lastVersionByte = buf.getUnsignedByte(startIdx).toInt()
        val result = try {
            tryDecode(buf)
        } catch (e: Exception) {
            lastException = "${e::class.simpleName}: ${e.message}"
            curTrace?.append("i=$curSubIndex sy=$curSubSy off=$relOffset ver=$lastVersionByte EXCEPTION=${e::class.simpleName}:${e.message}\n")
            null
        }
        val consumed = buf.readerIndex() - startIdx
        if (result != null) {
            curTrace?.append(
                "i=$curSubIndex sy=$curSubSy off=$relOffset ver=$lastVersionByte consumed=$consumed " +
                    "blocks=${result.first.size} ok=true\n"
            )
            val tailStart = maxOf(startIdx, buf.readerIndex() - 8)
            lastConsumedTailHex = (tailStart until buf.readerIndex()).joinToString(" ") {
                buf.getUnsignedByte(it).toString(16).padStart(2, '0')
            }
        } else if (buf.readableBytes() >= 0 && consumed >= 0) {
            // tryDecode zaten kendi UNKNOWN_VERSION/BAD_STORAGE_COUNT logunu bastıysa
            // burada tekrar etmiyoruz, sadece trace'e kısa bir satır ekliyoruz.
            curTrace?.append("i=$curSubIndex sy=$curSubSy off=$relOffset ver=$lastVersionByte consumed=$consumed ok=false\n")
        }
        return result
    }

    private fun tryDecode(buf: ByteBuf): Triple<IntArray, Array<String?>?, Int?>? {
        val hexDump = (0 until minOf(8, buf.readableBytes())).joinToString(" ") {
            buf.getUnsignedByte(buf.readerIndex() + it).toString(16).padStart(2, '0')
        }

        var realSy: Int? = null
        val version = buf.readUnsignedByte().toInt()
        val storageCount: Int
        when (version) {
            1 -> storageCount = 1
            8 -> storageCount = buf.readUnsignedByte().toInt()
            9 -> {
                // FIX: gerçek format [ver=9][storageCount:1][yIndex:1 (signed, relatif)][storage...]
                // Eskiden yIndex storageCount'tan ÖNCE okunuyordu ve aradan olmayan bir
                // "dataLen" VarInt alanı okunuyordu — bu yüzden buffer offset'i ilk
                // subchunk'tan itibaren kayıyor, storageCount hep 0/çöp okunuyordu ve
                // TÜM subchunk'lar decodeFailed oluyordu (hasTerrainData=false).
                // storageCount=0 → boş/air subchunk, blok verisi yok.
                //
                // FIX 2: storageCount==0 (air) durumunda erken return ederken yIndex
                // byte'ı OKUNMADAN atlanıyordu — ama yIndex byte'ı storageCount=0 olsa
                // bile stream'de HER ZAMAN var. Bu yüzden her air subchunk'tan sonra
                // buffer 1 byte kaymış oluyordu ve BİR SONRAKİ subchunk'ın version byte'ı
                // yerine bu air subchunk'ın yIndex byte'ı (örn. sy=-4 için 0xFC=252)
                // okunuyordu -> "unknown_version=252/253/254/255" hataları buradan
                // geliyordu (CHUNK TRACE ile doğrulandı: FC,FD,FE,FF sırası tam olarak
                // ardışık sy değerlerinin imzalı byte karşılığı).
                storageCount = buf.readUnsignedByte().toInt()
                realSy = buf.readByte().toInt() // yIndex, her zaman okunmalı (storageCount=0 olsa bile) - artık kullanılıyor
                if (storageCount == 0) return Triple(IntArray(0), null, realSy) // air sentinel
            }
            else -> {
                lastException = "raw=$hexDump unknown_version=$version"
                // FIX (DAHA DETAYLI): artık sadece ileri yöndeki 16 byte değil,
                // - bir önceki subchunk'ın tükettiği SON 8 byte (lastConsumedTailHex,
                //   offset kaymasının nereden başladığını gösterir),
                // - ileri yönde 64 byte (eski 16 yerine),
                // - chunk koordinatı, subchunk index/sy, protokol sürümü
                // birlikte tek satırda loglanıyor.
                val protocol = runCatching { PacketEventBus.currentSession?.activeCodec?.protocolVersion }.getOrNull()
                val extraHex = (0 until minOf(64, buf.readableBytes())).joinToString(" ") {
                    buf.getUnsignedByte(buf.readerIndex() + it).toString(16).padStart(2, '0')
                }
                logDecodeFailure(
                    "unknown_version_$version",
                    "UNKNOWN_VERSION version=$version(0x${version.toString(16)}) protocol=$protocol " +
                        "cx=$curChunkCx cz=$curChunkCz subIndex=$curSubIndex sy=$curSubSy " +
                        "prevTail8=$lastConsumedTailHex raw64=$extraHex"
                )
                return null
            }
        }
        if (storageCount <= 0 || storageCount > 8) {
            lastException = "raw=$hexDump bad_sc=$storageCount ver=$version"
            logDecodeFailure(
                "bad_storageCount_${version}_$storageCount",
                "BAD_STORAGE_COUNT version=$version storageCount=$storageCount cx=$curChunkCx cz=$curChunkCz " +
                    "subIndex=$curSubIndex sy=$curSubSy prevTail8=$lastConsumedTailHex raw=$hexDump"
            )
            return null
        }

        var primary: IntArray? = null
        var primaryNames: Array<String?>? = null
        repeat(storageCount) { idx ->
            val storage = readBlockStorage(buf) ?: return null
            if (idx == 0) { primary = storage.first; primaryNames = storage.second }
        }
        val p = primary ?: return null
        return Triple(p, primaryNames, realSy)
    }

    // FIX (KESİN KÖK SEBEP - CloudburstMC/Protocol kaynağı + wiki.vg ile
    // doğrulandı, baba.txt trace'i bunu yansıtıyordu): "1 bit: whether the
    // chunk is serialized for Runtime or for Persistence: ALWAYS 1 WHEN
    // OVER THE NETWORK." — yani bu bit'in "1" olması "NBT-paletli disk
    // formatı" DEĞİL, "network/runtime VarInt-paletli format" anlamına
    // gelir. Eskiden `isPersistent = (header and 1) == 1` yazılmıştı ve bu
    // TAM TERSİYDİ: relay her zaman network'ten okuduğu için bu bit her
    // subchunk'ta 1 geliyordu, kod da her seferinde "bu NBT-paletli" sanıp
    // NBT reader'ı aslında VarInt runtime-ID listesi olan ham veri üzerinde
    // çalıştırıyordu. NBT reader bu veriyi rastgele tag'ler olarak
    // yorumlayıp genelde tek bir "palet elemanı" için binlerce byte
    // tüketiyordu (baba.txt: paletteSize=29 için consumed=27641 gibi) —
    // bu da buffer'ı kaydırıp SONRAKİ subchunk'ların da "unknown_version"
    // ile başarısız olmasına yol açıyordu. Disk/LevelDB formatı (gerçek NBT
    // palette) relay bağlamında ASLA gelmez — chunk verisi her zaman
    // SubChunkPacket/LevelChunkPacket üzerinden network'ten okunur. Bu
    // yüzden NBT dalını tamamen kaldırıyoruz; palette her zaman VarInt
    // runtime-ID listesi olarak okunuyor.
    private fun readBlockStorage(buf: ByteBuf): Pair<IntArray, Array<String?>?>? {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        if (bitsPerBlock !in intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)) return null

        // bitsPerBlock == 0: tek runtimeId'lik "uniform" storage, index
        // array'i yok, palette de tek elemanlı VarInt.
        if (bitsPerBlock == 0) {
            val id = readSignedVarInt(buf)
            return IntArray(SECTION_BLOCKS) { id } to null
        }

        val indices = IntArray(SECTION_BLOCKS)
        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (SECTION_BLOCKS + blocksPerWord - 1) / blocksPerWord
        val mask = (1 shl bitsPerBlock) - 1
        var bi = 0
        repeat(wordCount) {
            val word = buf.readIntLE()
            var w = word
            var c = 0
            while (c < blocksPerWord && bi < SECTION_BLOCKS) {
                indices[bi] = w and mask
                w = w ushr bitsPerBlock
                bi++; c++
            }
        }

        val paletteSize = readSignedVarInt(buf)
        if (paletteSize <= 0 || paletteSize > 8192) return null
        val palette = IntArray(paletteSize) { readSignedVarInt(buf) }

        val result = IntArray(SECTION_BLOCKS) { i ->
            val p = indices[i]
            if (p < palette.size) palette[p] else 0
        }
        return result to null
    }

    // FIX: block storage'daki paletteSize ve palette elemanları (runtime id'ler)
    // PMMP'nin kendi network serializer'ında (ChunkSerializer::serializeSubChunk)
    // VarInt::writeSignedInt ile, yani ZIGZAG kodlamayla yazılıyor — "yes, this
    // is intentionally zigzag" diye açıkça yorumlanmış. Bunları readUnsignedVarInt
    // ile (zigzag çözmeden) okumak pozitif değerleri ~2 katına çıkarıyordu, bu da
    // paletteSize bound check'ini yanlış tetikliyor ya da var olmayan/yanlış bir
    // block runtime id'sine denk geliyordu (resolveIdentifier sürekli null
    // dönüyordu — CrystalAura/searchPlaceBase zemin bulamama sorununun bir diğer
    // kök nedeni buydu).
    private fun readSignedVarInt(buf: ByteBuf): Int {
        val raw = readUnsignedVarInt(buf)
        return (raw ushr 1) xor -(raw and 1)
    }

    private fun readUnsignedVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.readUnsignedByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalStateException("VarInt too long")
        }
        return result
    }

    internal fun resolveIdentifier(runtimeId: Int): String? {
        identifierCache[runtimeId]?.let { return it }
        val session = PacketEventBus.currentSession ?: return null

        fun extract(def: Any?): String? = when (def) {
            is org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition -> def.identifier
            is com.rubidiumclient.core.relay.Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
            else -> null
        }

        // Birincil kaynak: aktif session'ın kendi registry'si
        val primary = runCatching {
            extract(session.clientSession.peer.codecHelper.blockDefinitions?.getDefinition(runtimeId))
        }.getOrNull()

        if (primary != null) {
            identifierCache[runtimeId] = primary
            return primary
        }

        // Fallback: CrystalAura.getBlockDefinition() ile aynı mantık —
        // session registry'si boş/uyumsuzsa en yakın protokol tanımına düş
        val fallback = runCatching {
            extract(
                com.rubidiumclient.core.relay.Definitions
                    .getClosestDefinitions(session.activeCodec.protocolVersion)
                    .blockDefinitions
                    ?.getDefinition(runtimeId)
            )
        }.getOrNull() ?: return null

        identifierCache[runtimeId] = fallback
        return fallback
    }

    private fun sectionKey(cx: Int, sy: Int, cz: Int): Long {
        val cxL = cx.toLong() and 0xFFFFFFL
        val syL = (sy + 128).toLong() and 0xFFL
        val czL = cz.toLong() and 0xFFFFFFL
        return (cxL shl 32) or (syL shl 24) or czL
    }

    private fun blockPosKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
