package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * InfiniteAuraModule ("InfiniteAura")
 *
 * Her giden PlayerAuthInputPacket'te, oyuncunun BILDIRDIGI pozisyonu
 * dogrudan degistirerek kendini hedefin hemen ARKASINA "isinlar" ve
 * saldirir. TPAura'dan farki: TPAura mesafeye gore kademeli yaklasirken,
 * bu modul MESAFEDEN BAGIMSIZ (harita genelinde, MaxRange'e kadar) her
 * tick DOGRUDAN hedefin arkasina atlar — bu yuzden "sinirsiz/infinite
 * menzil" olarak adlandiriliyor.
 *
 * Referans dosyadan farklar (guclendirme):
 *  - CPS sabitti, artik Min/Max araligina cevrildi (diger tum combat
 *    modulleriyle tutarli).
 *  - Bot/friend filtreleme eklendi (referansta yoktu).
 *  - MaxRange siniri eklendi (referans TÜM entityMap'i sinirsiz taraiyordu —
 *    render mesafesinin cok disindaki / EntityTracker verisi bayat olan
 *    hedeflere de "isinlanmaya" calisirdi, bu ayar bunu kontrol edilebilir
 *    kiliyor).
 *  - "Silent lagback" mantigi (referanstaki iyi bir fikirdi) korundu ama
 *    esik degeri artik ayarlanabilir (LagbackThreshold) ve karsilastirma
 *    EntityTracker'daki (muhtemelen bizim tarafimizdan zaten override
 *    edilmis) degerler yerine PAKETIN KENDI TASIDIGI GERCEK pozisyonuyla
 *    (pkt.position, gercek client'in fiziginden gelen taze veri) yapiliyor
 *    — bu, sunucunun bizi geri cektigini (rubber-band) daha guvenilir tespit
 *    eder.
 */
class InfiniteAuraModule : BaseModule(
    name        = "InfiniteAura",
    category    = ModuleCategory.COMBAT,
    description = "Her tick hedefin hemen arkasına ışınlanıp saldırır (sınırlı olsa da çok geniş menzil)"
) {

    private val maxRange          = float("Max Range", 256f, 16f, 500f)
    private val cpsMin            = int  ("CPS Min", 10, 1, 20)
    private val cpsMax            = int  ("CPS Max", 14, 1, 20)
    private val behindOffset      = float("Behind Offset", 2.0f, 0.5f, 5.0f)
    private val playersOnly       = bool ("Players Only", true)
    private val ignoreFriends     = bool ("Ignore Friends", true)
    private val antiBot           = bool ("Anti Bot", true)
    private val silentLagbacks    = bool ("Silent Lagbacks", true)
    private val lagbackThreshold  = float("Lagback Threshold", 50f, 10f, 200f)
    private val shortcut = bool("Shortcut", false)

    // LAG FIX: findTarget() eskiden HER tick'te (PlayerAuthInputPacket,
    // ~20/sn) MaxRange'e kadar (500'e kadar) tüm entity haritasını
    // koşulsuz tarayıp sıralıyordu. KillAuraPro/LegitAura/AutoHvH ile aynı
    // önbellekleme deseni: hedef 120ms'de bir yeniden aranıyor.
    private val TARGET_SCAN_INTERVAL_MS = 120L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastScanMs = 0L

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastLagbackMs = 0L
    @Volatile private var serverSidePos: Vector3f? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastLagbackMs = 0L
        cachedTarget = null
        lastScanMs = 0L
        serverSidePos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        serverSidePos = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val now = System.currentTimeMillis()
        val currentPos = pkt.position

        val lastKnown = serverSidePos
        if (lastKnown != null &&
            MathUtil.dist3(lastKnown.x, lastKnown.y, lastKnown.z, currentPos.x, currentPos.y, currentPos.z) > lagbackThreshold.value
        ) {
            if (silentLagbacks.value) {
                serverSidePos = currentPos
                lastLagbackMs = now
            } else {
                setEnabled(false)
                return
            }
        }

        if (lastLagbackMs > 0 && now - lastLagbackMs < 100) return

        val target = findTarget()
        if (target == null) {
            serverSidePos = currentPos
            return
        }

        // FIX: RotationUtil -> target forward: fx=-sin(yaw), fz=cos(yaw)
        // behind = targetFeet + sin(yaw)*offset on x, -cos(yaw)*offset on z
        // Eskisi yaw+90 offsetiyle cos/sin kullaniyordu (yanlis convention).
        val yawRad = Math.toRadians(target.yaw.toDouble())

        val targetFeet = Vector3f.from(target.x, target.y - 1.62f, target.z)
        val behindPos = Vector3f.from(
            (targetFeet.x + sin(yawRad) * behindOffset.value).toFloat(),
            targetFeet.y,
            (targetFeet.z - cos(yawRad) * behindOffset.value).toFloat()
        )

        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs >= delayMs) {
            lastAttackMs = now
            val session = event.session
            val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
            val click = Vector3f.from(target.x, target.y + 1.5f, target.z)
            PacketUtil.sendSwing(session)
            PacketUtil.sendAttack(session, target.runtimeId, slot, click)
        }

        serverSidePos = behindPos
        pkt.position = behindPos
        pkt.rotation = calculateRotationToTarget(behindPos, targetFeet)
        EntityTracker.selfX = behindPos.x
        EntityTracker.selfY = behindPos.y
        EntityTracker.selfZ = behindPos.z
        event.cancelAndReplace(pkt)
    }

    private fun calculateRotationToTarget(from: Vector3f, to: Vector3f): Vector3f {
        val dx = to.x - from.x
        val dz = to.z - from.z
        // FIX: RotationUtil.toPoint -> yaw=atan2(-dx,dz). Eskisi atan2(dz,dx)-90 kullaniyordu.
        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        return Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()

        val cached = cachedTarget?.takeIf {
            EntityTracker.getById(it.runtimeId) != null &&
            EntityTracker.distanceTo(it) <= maxRange.value
        }
        if (cached != null && now - lastScanMs < TARGET_SCAN_INTERVAL_MS) return cached

        lastScanMs = now
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val found = EntityTracker.getEntitiesInRange(maxRange.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId &&
                (if (playersOnly.value) e.isPlayer else true) &&
                !(ignoreFriends.value && e.isFriendEntity) &&
                !(antiBot.value && playersOnly.value && e.isPlayer && e.isLikelyBot())
        }.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
        cachedTarget = found
        return found
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
