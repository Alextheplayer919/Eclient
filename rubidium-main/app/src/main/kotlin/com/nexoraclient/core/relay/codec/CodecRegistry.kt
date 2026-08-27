package com.rubidiumclient.core.relay.codec

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * PERFORMANS FIX: registerAllCodecs() önceden ~50 codec sınıfının HEPSİNİ
 * Class.forName + reflection field access ile app açılışında yüklüyordu,
 * halbuki bir oturumda gerçekte SADECE bağlanan clientın protokolüne uyan
 * TEK codec kullanılıyor. Artık init() sadece protokol->classname eşlemesini
 * (hiç reflection yok, sadece sabitler) kuruyor; gerçek Class.forName +
 * reflection yükü bir codec ilk defa istendiğinde yapılıp cache'leniyor.
 */
object CodecRegistry {

    private const val TAG = "CodecRegistry"

    private data class CodecEntry(val minecraftVersionLabel: String, val className: String)

    private val classNameMap = LinkedHashMap<Int, CodecEntry>()
    private val minecraftVersionMap = HashMap<String, Int>()
    private val sortedProtocolVersions = mutableListOf<Int>()

    private val loadedCodecs = ConcurrentHashMap<Int, BedrockCodec>()

    init {
        registerAllCodecs()
        sortedProtocolVersions.addAll(classNameMap.keys)
        sortedProtocolVersions.sortDescending()
    }

    private fun registerAllCodecs() {
        registerCodec(291, "1.2.0", "org.cloudburstmc.protocol.bedrock.codec.v291.Bedrock_v291")
        registerCodec(313, "1.2.10", "org.cloudburstmc.protocol.bedrock.codec.v313.Bedrock_v313")
        registerCodec(332, "1.4.0", "org.cloudburstmc.protocol.bedrock.codec.v332.Bedrock_v332")
        registerCodec(340, "1.5.0", "org.cloudburstmc.protocol.bedrock.codec.v340.Bedrock_v340")
        registerCodec(354, "1.6.0", "org.cloudburstmc.protocol.bedrock.codec.v354.Bedrock_v354")
        registerCodec(361, "1.7.0", "org.cloudburstmc.protocol.bedrock.codec.v361.Bedrock_v361")
        registerCodec(388, "1.8.0", "org.cloudburstmc.protocol.bedrock.codec.v388.Bedrock_v388")
        registerCodec(389, "1.9.0", "org.cloudburstmc.protocol.bedrock.codec.v389.Bedrock_v389")
        registerCodec(390, "1.10.0", "org.cloudburstmc.protocol.bedrock.codec.v390.Bedrock_v390")
        registerCodec(407, "1.11.0", "org.cloudburstmc.protocol.bedrock.codec.v407.Bedrock_v407")
        registerCodec(408, "1.12.0", "org.cloudburstmc.protocol.bedrock.codec.v408.Bedrock_v408")
        registerCodec(419, "1.13.0", "org.cloudburstmc.protocol.bedrock.codec.v419.Bedrock_v419")
        registerCodec(422, "1.14.0", "org.cloudburstmc.protocol.bedrock.codec.v422.Bedrock_v422")
        registerCodec(428, "1.14.60", "org.cloudburstmc.protocol.bedrock.codec.v428.Bedrock_v428")
        registerCodec(431, "1.15.0", "org.cloudburstmc.protocol.bedrock.codec.v431.Bedrock_v431")
        registerCodec(440, "1.16.0", "org.cloudburstmc.protocol.bedrock.codec.v440.Bedrock_v440")
        registerCodec(448, "1.16.100", "org.cloudburstmc.protocol.bedrock.codec.v448.Bedrock_v448")
        registerCodec(465, "1.16.200", "org.cloudburstmc.protocol.bedrock.codec.v465.Bedrock_v465")
        registerCodec(471, "1.16.210", "org.cloudburstmc.protocol.bedrock.codec.v471.Bedrock_v471")
        registerCodec(475, "1.16.220", "org.cloudburstmc.protocol.bedrock.codec.v475.Bedrock_v475")
        registerCodec(486, "1.17.0", "org.cloudburstmc.protocol.bedrock.codec.v486.Bedrock_v486")
        registerCodec(503, "1.17.30", "org.cloudburstmc.protocol.bedrock.codec.v503.Bedrock_v503")
        registerCodec(527, "1.18.0", "org.cloudburstmc.protocol.bedrock.codec.v527.Bedrock_v527")
        registerCodec(534, "1.18.10", "org.cloudburstmc.protocol.bedrock.codec.v534.Bedrock_v534")
        registerCodec(544, "1.18.30", "org.cloudburstmc.protocol.bedrock.codec.v544.Bedrock_v544")
        registerCodec(545, "1.19.0", "org.cloudburstmc.protocol.bedrock.codec.v545.Bedrock_v545")
        registerCodec(554, "1.19.10", "org.cloudburstmc.protocol.bedrock.codec.v554.Bedrock_v554")
        registerCodec(557, "1.19.20", "org.cloudburstmc.protocol.bedrock.codec.v557.Bedrock_v557")
        registerCodec(560, "1.19.30", "org.cloudburstmc.protocol.bedrock.codec.v560.Bedrock_v560")
        registerCodec(567, "1.19.40", "org.cloudburstmc.protocol.bedrock.codec.v567.Bedrock_v567")
        registerCodec(568, "1.19.50", "org.cloudburstmc.protocol.bedrock.codec.v568.Bedrock_v568")
        registerCodec(575, "1.19.60", "org.cloudburstmc.protocol.bedrock.codec.v575.Bedrock_v575")
        registerCodec(582, "1.19.70", "org.cloudburstmc.protocol.bedrock.codec.v582.Bedrock_v582")
        registerCodec(589, "1.19.80", "org.cloudburstmc.protocol.bedrock.codec.v589.Bedrock_v589")
        registerCodec(594, "1.20.0", "org.cloudburstmc.protocol.bedrock.codec.v594.Bedrock_v594")
        registerCodec(618, "1.20.10", "org.cloudburstmc.protocol.bedrock.codec.v618.Bedrock_v618")
        registerCodec(622, "1.20.30", "org.cloudburstmc.protocol.bedrock.codec.v622.Bedrock_v622")
        registerCodec(630, "1.20.40", "org.cloudburstmc.protocol.bedrock.codec.v630.Bedrock_v630")
        registerCodec(649, "1.20.50", "org.cloudburstmc.protocol.bedrock.codec.v649.Bedrock_v649")
        registerCodec(662, "1.20.60", "org.cloudburstmc.protocol.bedrock.codec.v662.Bedrock_v662")
        registerCodec(671, "1.20.70", "org.cloudburstmc.protocol.bedrock.codec.v671.Bedrock_v671")
        registerCodec(685, "1.20.80", "org.cloudburstmc.protocol.bedrock.codec.v685.Bedrock_v685")
        registerCodec(686, "1.21.0", "org.cloudburstmc.protocol.bedrock.codec.v686.Bedrock_v686")
        registerCodec(712, "1.21.20", "org.cloudburstmc.protocol.bedrock.codec.v712.Bedrock_v712")
        registerCodec(729, "1.21.30", "org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729")
        registerCodec(748, "1.21.40", "org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748")
        registerCodec(766, "1.21.50", "org.cloudburstmc.protocol.bedrock.codec.v766.Bedrock_v766")
        registerCodec(776, "1.21.60", "org.cloudburstmc.protocol.bedrock.codec.v776.Bedrock_v776")
        registerCodec(786, "1.21.70", "org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786")
        registerCodec(800, "1.21.80", "org.cloudburstmc.protocol.bedrock.codec.v800.Bedrock_v800")
        registerCodec(818, "1.21.90", "org.cloudburstmc.protocol.bedrock.codec.v818.Bedrock_v818")
        registerCodec(819, "1.21.93", "org.cloudburstmc.protocol.bedrock.codec.v819.Bedrock_v819")
        registerCodec(827, "1.21.100", "org.cloudburstmc.protocol.bedrock.codec.v827.Bedrock_v827")
        registerCodec(844, "1.21.111~1.21.114", "org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844")
        registerCodec(859, "1.21.120~1.21.123", "org.cloudburstmc.protocol.bedrock.codec.v859.Bedrock_v859")
        registerCodec(860, "1.21.124", "org.cloudburstmc.protocol.bedrock.codec.v860.Bedrock_v860")
        // BUG FIX: 1.21.130-132 gerçekte Bedrock_v897 sınıfını kullanıyor.
        // Önceki "v898" burada hiç var olmayan bir sınıftı — Class.forName
        // sessizce başarısız olup her seferinde bir alt versiyona (860)
        // fallback ediyordu; yani 1.21.130+ hiçbir zaman gerçekten
        // desteklenmiyordu.
        registerCodec(897, "1.21.130~1.21.132", "org.cloudburstmc.protocol.bedrock.codec.v897.Bedrock_v897")

        registerCodec(924, "1.26.0~1.26.3",   "org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924")
        registerCodec(944, "1.26.10~1.26.13", "org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944")
        registerCodec(975, "1.26.20~1.26.23", "org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975")
        registerCodec(1001, "1.26.30~1.26.33", "org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001")
        // BUG FIX: Etiket sadece "1.26.40" diyordu ama protokol 2168, 1.26.40'ın
        // ilk çıkışından (4 Ağustos 2026) itibaren 26.41/26.42/26.43 hotfix'lerinden
        // geçip 26.44'e (14 Ağustos 2026 hotfix) kadar HİÇ değişmedi — yani bu tek
        // protokol numarası aslında 1.26.40~1.26.44 aralığının tamamını kapsıyor,
        // sadece 1.26.44'ü değil. Diğer girişlerdeki "~" aralık etiketleme
        // kuralıyla tutarlı hale getirildi.
        registerCodec(2168, "1.26.40~1.26.44", "org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168")
    }

    /** Sadece isim eşlemesini kaydeder — hiç reflection/class-loading yapmaz, çok ucuz. */
    private fun registerCodec(protocolVersion: Int, minecraftVersionLabel: String, className: String) {
        classNameMap[protocolVersion] = CodecEntry(minecraftVersionLabel, className)
        minecraftVersionMap[minecraftVersionLabel] = protocolVersion
    }

    /** Gerçek Class.forName + reflection field access burada, ilk istekte, ve cache'lenerek. */
    private fun loadCodec(protocolVersion: Int): BedrockCodec? {
        val entry = classNameMap[protocolVersion] ?: return null
        return try {
            val codecClass = Class.forName(entry.className)
            val codecField = codecClass.getDeclaredField("CODEC")
            codecField.isAccessible = true
            codecField.get(null) as BedrockCodec
        } catch (e: Throwable) {
            null
        }
    }

    fun getCodecByProtocol(protocolVersion: Int): BedrockCodec? =
        loadedCodecs[protocolVersion] ?: loadCodec(protocolVersion)?.also { loadedCodecs[protocolVersion] = it }

    fun getClosestCodec(protocolVersion: Int): BedrockCodec {
        getCodecByProtocol(protocolVersion)?.let { return it }

        check(sortedProtocolVersions.isNotEmpty()) {
            "Hiçbir Bedrock codec yüklenemedi — bedrock-codec dependency'sini kontrol et"
        }

        // İlk denenen "en yakın" versiyon her ihtimalde gerçekten yüklenebilir
        // olmayabilir (eksik/yanlış dependency) — eskiden bu tür versiyonlar
        // zaten sortedProtocolVersions'a hiç girmiyordu (sessizce elenirdi).
        // Aynı güvenliği korumak için, o versiyon lazy-load'da başarısız
        // olursa bir sonraki en yakın adaya geçiyoruz.
        val ordered = sortedProtocolVersions.filter { it <= protocolVersion }
            .ifEmpty { sortedProtocolVersions }
            .sortedDescending()

        for (candidate in ordered) {
            getCodecByProtocol(candidate)?.let { return it }
        }
        for (candidate in sortedProtocolVersions) {
            getCodecByProtocol(candidate)?.let { return it }
        }

        error("Hiçbir Bedrock codec yüklenemedi (hepsi başarısız oldu)")
    }

    fun getLatestCodec(): BedrockCodec {
        check(sortedProtocolVersions.isNotEmpty()) {
            "Hiçbir Bedrock codec yüklenemedi — bedrock-codec dependency'sini kontrol et"
        }
        for (candidate in sortedProtocolVersions) {
            getCodecByProtocol(candidate)?.let { return it }
        }
        error("Hiçbir Bedrock codec yüklenemedi (hepsi başarısız oldu)")
    }

    fun getMinecraftVersionLabel(protocolVersion: Int): String? =
        minecraftVersionMap.entries.firstOrNull { it.value == protocolVersion }?.key

    val registeredCount: Int get() = sortedProtocolVersions.size
}
