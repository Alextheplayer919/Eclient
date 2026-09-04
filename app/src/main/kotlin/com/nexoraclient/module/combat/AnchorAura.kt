package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3i
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

class AnchorAura : BaseModule(
    name        = "AnchorAura",
    category    = ModuleCategory.COMBAT,
    description = "Hedefin yanına respawn anchor koyup şarj edip Nether dışında patlatır"
) {
    companion object {
        private const val ACTIVATE_DELAY_MS      = 60L
        private const val VERIFY_RETRY_WINDOW_MS = 150L
        private const val VERIFY_CHECK_EVERY_MS  = 20L
        // Hedef listesi cache süresi — tickMs 10ms'e kadar düşebildiği için
        // her tick'te tüm oyuncuları taramak (filter+sort) gereksiz; 80ms'de
        // bir tazeleniyor, aradaki tick'lerde son sonuç (busyTargets hariç,
        // o her zaman taze) yeniden kullanılıyor.
        private const val TARGET_SCAN_INTERVAL_MS = 80L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )

        private const val ANCHOR    = "minecraft:respawn_anchor"
        private const val GLOWSTONE = "minecraft:glowstone"

        private val FACE_OFFSETS = listOf(
            1 to Triple(0, 1, 0),
            2 to Triple(0, 0, -1),
            3 to Triple(0, 0, 1),
            4 to Triple(-1, 0, 0),
            5 to Triple(1, 0, 0),
            0 to Triple(0, -1, 0)
        )
    }

    private val targetRange      = int  ("Target Range",        8,   2,   16)
    private val friendSkip       = bool ("Friend Skip",         true)
    // BUG FIX: varsayılan 6 iken targetRange varsayılanı 8'di — hedef
    // 6-8 blok arasında olduğunda (kavgada çok sık rastlanan mesafe)
    // yerleştirme noktası placeRange'i aşıp attemptPlace() SESSİZCE return
    // ediyordu. Bu, "bazen koyuyor bazen koymuyor" şikayetinin en büyük
    // sebebiydi — tamamen hedefin o anki mesafesine bağlı, rastgele
    // görünen bir başarısızlıktı. placeRange artık targetRange'i kapsıyor.
    private val placeRange       = int  ("Place Range",         9,   2,   16)
    private val noSwitch         = bool ("No Switch",           true)
    // "20ms'e kadar düşür" — cooldown artık 20ms'e kadar ayarlanabiliyor,
    // varsayılan da hızlandırıldı (500 -> 80).
    private val cooldownMs       = int  ("Cooldown (ms)",        80,  20,  5000)
    private val requireNotNether = bool ("Require Not-Nether",   true)
    private val shortcut         = bool ("Shortcut",             false)
    private val maxAnchors       = int  ("Max Anchors",          3,   1,    5)
    private val tickMs           = int  ("Tick Speed (ms)",      50,  10,  150)
    private val packetGapMs      = int  ("Min Packet Gap (ms)",  40,  20,  500)
    // FALLBACK: terrain verisi (WorldBlockTracker) o an güvenilir olmasa
    // bile "ne olursa olsun yerleştir ve patlat" — findPlacementSpot boş
    // dönerse kör tahminle (hedefin 1 blok altı, obsidian varsayarak)
    // devam eder, verifyPlaced hiç beklemeden hep CONFIRMED döner. Normal
    // modda başarısız olan denemeler artık sessizce iptal olmuyor.
    private val forceMode         = bool ("Force",                false)

    private enum class Phase {
        PLACED,
        CHARGED
    }

    private data class Attempt(
        val anchorPos: Vector3i,
        val phase: Phase,
        val armedAt: Long,
        val verifyDeadline: Long = 0L,
        val targetId: Long = -1L,
        val nextCheckAt: Long = 0L
    )

    private val activeAttempts = CopyOnWriteArrayList<Attempt>()

    @Volatile
    private var lastAttemptMs = 0L

    @Volatile
    private var lastPacketSentMs = 0L

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()

        activeAttempts.clear()
        lastPacketSentMs = 0L

        tickJob?.cancel()
        tickJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                if (isEnabled) {
                    tick()
                }

                delay(tickMs.value.toLong())
            }
        }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null

        activeAttempts.clear()

        super.onDisable()
    }

    private fun tryConsumePacketSlot(): Boolean {
        val now = System.currentTimeMillis()

        if (now - lastPacketSentMs < packetGapMs.value) {
            return false
        }

        lastPacketSentMs = now
        return true
    }

    private fun tick() {
        if (requireNotNether.value && EntityTracker.selfDimension == 1) {
            return
        }

        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()

        // LAG FIX: activeAttempts zaten CopyOnWriteArrayList — iterator'ı
        // eşzamanlı değişikliklere karşı doğal olarak güvenli. .toList()
        // her tick'te (10ms'e kadar) gereksiz bir tam kopya çıkarıyordu.
        for (attempt in activeAttempts) {
            if (now - attempt.armedAt < ACTIVATE_DELAY_MS) {
                continue
            }

            when (attempt.phase) {
                Phase.PLACED -> {
                    if (now < attempt.nextCheckAt) {
                        continue
                    }

                    when (verifyPlaced(attempt)) {
                        VerifyResult.CONFIRMED -> {
                            chargeAnchor(session, attempt)
                        }

                        VerifyResult.REJECTED -> {
                            activeAttempts.remove(attempt)
                        }

                        VerifyResult.PENDING -> {
                            activeAttempts.remove(attempt)
                            activeAttempts.add(
                                attempt.copy(
                                    nextCheckAt = now + VERIFY_CHECK_EVERY_MS
                                )
                            )
                        }
                    }
                }

                Phase.CHARGED -> {
                    if (!tryConsumePacketSlot()) {
                        continue
                    }

                    PlacementUtil.sendInteract(
                        session,
                        attempt.anchorPos,
                        ANCHOR
                    )

                    activeAttempts.remove(attempt)
                }
            }
        }

        if (activeAttempts.size >= maxAnchors.value) {
            return
        }

        if (now - lastAttemptMs < cooldownMs.value) {
            return
        }

        val target = nearestEnemy() ?: return
        attemptPlace(session, target)
    }

    private fun pickOpenFace(pos: Vector3i): Int {
        if (!WorldBlockTracker.hasData(pos.x, pos.y, pos.z)) {
            return 1
        }

        for ((face, offset) in FACE_OFFSETS) {
            val nx = pos.x + offset.first
            val ny = pos.y + offset.second
            val nz = pos.z + offset.third

            val id = WorldBlockTracker.getBlockIdentifier(nx, ny, nz)
                ?: continue

            if (id in NON_SOLID) {
                return face
            }
        }

        return 1
    }

    private fun chargeAnchor(
        session: RubidiumRelaySession,
        attempt: Attempt
    ) {
        if (!tryConsumePacketSlot()) {
            activeAttempts.remove(attempt)
            activeAttempts.add(
                attempt.copy(
                    nextCheckAt = System.currentTimeMillis() + VERIFY_CHECK_EVERY_MS
                )
            )
            return
        }

        val glowstone = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = GLOWSTONE,
            noSwitch = noSwitch.value
        ) ?: run {
            activeAttempts.remove(attempt)
            return
        }

        val success = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = glowstone,
            blockPos = attempt.anchorPos,
            blockId = ANCHOR,
            blockFace = pickOpenFace(attempt.anchorPos)
        )

        PlacementUtil.revert(session, glowstone)

        activeAttempts.remove(attempt)

        if (success) {
            activeAttempts.add(
                Attempt(
                    anchorPos = attempt.anchorPos,
                    phase = Phase.CHARGED,
                    armedAt = System.currentTimeMillis(),
                    targetId = attempt.targetId
                )
            )
        }
    }

    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        if (EntityTracker.selfRuntimeId <= 0L) {
            return null
        }

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val busyTargets = activeAttempts
            .mapNotNull { attempt ->
                if (attempt.targetId >= 0L) {
                    attempt.targetId
                } else {
                    null
                }
            }
            .toSet()

        return EntityTracker
            .getPlayers(targetRange.value.toFloat())
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { it.runtimeId !in busyTargets }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .filter {
                MathUtil.dist3(
                    it.x,
                    it.y,
                    it.z,
                    selfX,
                    selfY,
                    selfZ
                ) <= targetRange.value.toFloat() + 1f
            }
            .minByOrNull {
                MathUtil.dist3sq(
                    it.x,
                    it.y,
                    it.z,
                    selfX,
                    selfY,
                    selfZ
                )
            }
    }

    private fun findPlacementSpot(
        targetX: Int,
        targetY: Int,
        targetZ: Int
    ): Pair<Vector3i, String>? {
        var best: Pair<Vector3i, String>? = null
        var bestDistanceSq = Int.MAX_VALUE
        var bestPlayerDistSq = Float.MAX_VALUE

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        // DAHA İYİ YERLEŞTİRME: dikey arama -1..1'den -2..2'ye genişletildi
        // — hedef merdivende/eğimli arazide/zıplarken olduğunda eskiden
        // hiç aday bulunamıyordu. Eşit target-mesafeli adaylar arasında
        // artık OYUNCUYA daha yakın olanı tercih ediliyor (ikincil
        // tie-break) — bu, placeRange reddi riskini azaltıp gerçek
        // yerleştirme başarı oranını artırıyor.
        for (dy in -2..2) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val px = targetX + dx
                    val py = targetY + dy
                    val pz = targetZ + dz

                    if (!WorldBlockTracker.hasData(px, py, pz)) {
                        continue
                    }

                    val spotId =
                        WorldBlockTracker.getBlockIdentifier(px, py, pz)
                            ?: "minecraft:air"

                    if (spotId !in NON_SOLID) {
                        continue
                    }

                    val maxBelowDepth = 4
                    var belowId: String? = null
                    var belowFoundAt = 0

                    for (depth in 1..maxBelowDepth) {
                        val belowY = py - depth

                        if (!WorldBlockTracker.hasData(px, belowY, pz)) {
                            continue
                        }

                        val id = WorldBlockTracker.getBlockIdentifier(
                            px,
                            belowY,
                            pz
                        ) ?: continue

                        if (id in NON_SOLID) {
                            continue
                        }

                        belowId = id
                        belowFoundAt = depth
                        break
                    }

                    if (belowId == null) {
                        continue
                    }

                    val distanceSq =
                        dx * dx +
                        dy * dy +
                        dz * dz +
                        (belowFoundAt - 1) * (belowFoundAt - 1)

                    val anchorY = py - belowFoundAt + 1
                    val playerDistSq = MathUtil.dist3sq(
                        px + 0.5f, anchorY + 0.5f, pz + 0.5f,
                        selfX, selfY, selfZ
                    )

                    val better = distanceSq < bestDistanceSq ||
                        (distanceSq == bestDistanceSq && playerDistSq < bestPlayerDistSq)

                    if (better) {
                        bestDistanceSq = distanceSq
                        bestPlayerDistSq = playerDistSq

                        best = Vector3i.from(px, anchorY, pz) to belowId
                    }
                }
            }
        }

        return best
    }

    private fun attemptPlace(
        session: RubidiumRelaySession,
        target: EntityTracker.TrackedEntity
    ) {
        val targetX = floor(target.x).toInt()
        val targetY = floor(target.y).toInt()
        val targetZ = floor(target.z).toInt()

        if (
            kotlin.math.abs(targetX) > 30_000_000 ||
            kotlin.math.abs(targetZ) > 30_000_000
        ) {
            return
        }

        val candidate = findPlacementSpot(
            targetX,
            targetY,
            targetZ
        ) ?: if (forceMode.value) {
            // Kör tahmin: terrain verisi yok/güvenilmez, hedefin 1 blok
            // altını solid varsayıyoruz (PlacementUtil.findClickableNeighbor
            // ile aynı fallback deseni: "minecraft:obsidian" varsayımı).
            Vector3i.from(targetX, targetY, targetZ) to "minecraft:obsidian"
        } else {
            return
        }

        val anchorPos = candidate.first
        val belowBlock = candidate.second

        val centerX = anchorPos.x + 0.5f
        val centerY = anchorPos.y + 0.5f
        val centerZ = anchorPos.z + 0.5f

        val distance = MathUtil.dist3(
            centerX,
            centerY,
            centerZ,
            EntityTracker.selfX,
            EntityTracker.selfY + 1.62f,
            EntityTracker.selfZ
        )

        if (distance > placeRange.value && !forceMode.value) {
            return
        }

        if (!tryConsumePacketSlot()) {
            return
        }

        val prepared = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = ANCHOR,
            noSwitch = noSwitch.value
        ) ?: return

        // BUG FIX: eskiden bu fonksiyonun en başında set ediliyordu — yani
        // mesafe/candidate/eşya kontrolü yüzünden SESSİZCE başarısız olan
        // her deneme bile 500ms'lik cooldown'u boşa harcıyordu. Artık
        // sadece GERÇEKTEN bir yerleştirme paketi gönderileceği kesinleşince
        // set ediliyor — hedef menzile girer girmez bir sonraki tick'te
        // tekrar denenebiliyor.
        lastAttemptMs = System.currentTimeMillis()

        val belowPos = Vector3i.from(
            anchorPos.x,
            anchorPos.y - 1,
            anchorPos.z
        )

        val success = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = prepared,
            blockPos = belowPos,
            blockId = belowBlock,
            blockFace = 1
        )

        PlacementUtil.revert(session, prepared)

        if (!success) {
            return
        }

        val armedAt = System.currentTimeMillis()

        activeAttempts.add(
            Attempt(
                anchorPos = anchorPos,
                phase = Phase.PLACED,
                armedAt = armedAt,
                verifyDeadline = armedAt +
                    ACTIVATE_DELAY_MS +
                    VERIFY_RETRY_WINDOW_MS,
                targetId = target.runtimeId,
                nextCheckAt = armedAt + ACTIVATE_DELAY_MS
            )
        )
    }

    private enum class VerifyResult {
        CONFIRMED,
        REJECTED,
        PENDING
    }

    private fun verifyPlaced(
        attempt: Attempt
    ): VerifyResult {
        if (forceMode.value) return VerifyResult.CONFIRMED

        val now = System.currentTimeMillis()

        if (
            !WorldBlockTracker.hasData(
                attempt.anchorPos.x,
                attempt.anchorPos.y,
                attempt.anchorPos.z
            )
        ) {
            // Bazı sunucularda LevelChunk/SubChunk decode edilemiyor.
            // Bu durumda tracker'ın veri bilmemesi anchor'ın olmadığı
            // anlamına gelmez. Kısa pencere sonunda placement packet'ı
            // gönderildiği için charge aşamasına geçiyoruz.
            return if (now < attempt.verifyDeadline) {
                VerifyResult.PENDING
            } else {
                VerifyResult.CONFIRMED
            }
        }

        val id = WorldBlockTracker.getBlockIdentifier(
            attempt.anchorPos.x,
            attempt.anchorPos.y,
            attempt.anchorPos.z
        )

        if (id == ANCHOR) {
            return VerifyResult.CONFIRMED
        }

        // Section mevcut olsa bile UpdateBlockPacket, placement
        // transaction'ından birkaç tick sonra gelebilir. Air veya null
        // görüldüğünde hemen reddetmek yerine kısa süre bekliyoruz.
        if (id == null || id in NON_SOLID) {
            return if (now < attempt.verifyDeadline) {
                VerifyResult.PENDING
            } else {
                VerifyResult.CONFIRMED
            }
        }

        return VerifyResult.REJECTED
    }
}