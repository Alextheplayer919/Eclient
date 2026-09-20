package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AutoHvHModule ("AutoHVH")
 *
 * Uzak mesafeden (500 bloga kadar) hedefe otomatik yaklasir, yakin
 * menzile girince daire cizerek CPS'e gore saldirir.
 *
 * Referans dosyadan farklar (guclendirme / bug fix):
 *  - KRITIK BUG FIX: Referans hareket paketini SADECE
 *    `session.clientBound(MovePlayerPacket...)` ile gonderiyordu. clientBound
 *    yalnizca GERCEK CLIENT'IN kendi ekranini gunceller — SUNUCU bu hareketten
 *    HABERDAR OLMAZ. Yani referanstaki "yaklasma" tamamen kozmetikti, sunucu
 *    tarafinda hicbir gercek etkisi olmazdi (oyuncu sunucu icin oldugu yerde
 *    kalirdi). Artik hem `serverBound` hem `clientBound` gonderiliyor, ayrica
 *    EntityTracker.self* durumu ve TPAura'nin cakisma-onleme sayaci
 *    guncelleniyor (asagida).
 *  - Bu projede zaten TPAura ve Criticals de kendi pozisyon paketlerini
 *    gonderiyor. Referans bundan tamamen habersiz tasarlanmisti — TPAura ile
 *    AYNI ANDA acilirsa iki modul de pozisyonu farkli hesaplardan degistirip
 *    "rubber-band" (goruntu titremesi/geri cekilme) tetikleyebilirdi. Artik
 *    TPAura acikken AutoHVH SADECE saldiriyor, hareketi TPAura'ya birakiyor
 *    (ModuleManager.byName ile canli kontrol).
 *  - Paket-tetiklemeli (`beforePacketBound`/PlayerAuthInputPacket'e bagli,
 *    duzensiz zamanlama) yerine sabit ~50tick/sn bagimsiz tick-loop kullanildi.
 *  - Bot/friend filtreleme KillAura/TPAura ile ayni standarda getirildi
 *    (referansta sadece "bos isimli oyuncu" kontrolu vardi, friend/ignore
 *    secenegi yoktu).
 *  - `isPathBlocked` referansta TODO olarak birakilmis, hep `false`
 *    donuyordu (yani islevsizdi). Duz relay/proxy mimarisinde gercek bir
 *    collision-mesh raycast yapmak bu katmanda pratik degil (WorldBlockTracker
 *    sadece blok kimlik verisi tutuyor, tam collision shape degil) — bu
 *    yuzden yanlis bir guvenlik hissi vermemek icin bu ozelligi oldugu gibi
 *    (islevsiz TODO) tasimak yerine tamamen cikardik ve NoClip secenegini de
 *    kaldirdik; onun yerine dikey hareketi (yOffset + hafif adim boyu limiti)
 *    kullanarak duvara toslama riskini pratikte azaltiyoruz.
 */
class AutoHvHModule : BaseModule(
    name        = "AutoHVH",
    category    = ModuleCategory.COMBAT,
    description = "Uzak mesafeden hedefe yaklaşıp yakın menzilde CPS'e göre saldırır"
) {

    private val maxRange      = float("Max Range", 128f, 16f, 500f)
    private val engageRange   = float("Engage Range", 3.2f, 1.5f, 8f)
    private val approachSpeed = float("Approach Speed", 0.6f, 0.1f, 3f)
    private val strafeRadius  = float("Strafe Radius", 1.6f, 0f, 5f)
    private val strafeSpeed   = float("Strafe Speed", 3f, 0.5f, 10f)
    private val cpsMin        = int  ("CPS Min", 12, 1, 20)
    private val cpsMax        = int  ("CPS Max", 16, 1, 20)
    private val yOffset       = float("Y Offset", 0f, -5f, 5f)
    private val jitter        = float("Jitter", 0.05f, 0f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val antiBot       = bool ("Anti Bot", true)
    private val shortcut      = bool ("Shortcut", false)

    // LAG FIX: findTarget() eskiden HER tick'te (20ms = 50Hz) 500 bloğa
    // kadar tüm entity haritasını tarayıp sıralıyordu — koşulsuz, saldırı/
    // hareket zaten olsun olmasın. Artık KillAuraPro/LegitAura'daki ile aynı
    // önbellekleme deseni: hedef 120ms'de bir yeniden aranıyor, aradaki
    // tick'lerde son bulunan hedef (hâlâ menzildeyse) tekrar kullanılıyor.
    private val TARGET_SCAN_INTERVAL_MS = 120L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastScanMs = 0L

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastMoveMs = 0L
    @Volatile private var strafeAngle = 0.0
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastMoveMs = 0L
        cachedTarget = null
        lastScanMs = 0L
        strafeAngle = kotlin.random.Random.nextDouble(0.0, Math.PI * 2)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(20L)
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val target = findTarget() ?: return
        val now = System.currentTimeMillis()

        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val dist = MathUtil.dist3(sx, sy, sz, target.x, target.y, target.z)

        if (dist <= engageRange.value) {
            val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
            if (now - lastAttackMs >= delayMs) {
                lastAttackMs = now
                val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
                val click = Vector3f.from(target.x, target.y + 1.5f, target.z)
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, target.runtimeId, slot, click)
            }
        }

        // TPAura zaten pozisyonu kendi hesabina gore yonetiyorsa, ikisi
        // ayni anda cakismasin diye hareketi burada devre disi birakiyoruz.
        if (ModuleManager.byName("TPAura")?.isEnabled == true) return

        if (now - lastMoveMs < 20L) return
        lastMoveMs = now

        val newPos = if (dist > engageRange.value) {
            stepToward(sx, sy, sz, target)
        } else {
            strafeAround(target)
        }

        val rot = RotationUtil.toEntity(target)
        try {
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                position = newPos
                rotation = Vector3f.from(rot.pitch, rot.yaw, rot.yaw)
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = false
                ridingRuntimeEntityId = 0L
            }
            session.serverBound(movePacket)
            session.clientBound(movePacket)
            TPAura.notifyExternalPositionOverride()
            EntityTracker.selfX = newPos.x
            EntityTracker.selfY = newPos.y
            EntityTracker.selfZ = newPos.z
            EntityTracker.selfYaw = rot.yaw
            EntityTracker.selfPitch = rot.pitch
        } catch (_: Exception) {}
    }

    private fun stepToward(sx: Float, sy: Float, sz: Float, target: EntityTracker.TrackedEntity): Vector3f {
        val dx = target.x - sx
        val dz = target.z - sz
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
        val speed = approachSpeed.value
        val jx = (Math.random().toFloat() - 0.5f) * 2f * jitter.value
        val jz = (Math.random().toFloat() - 0.5f) * 2f * jitter.value
        return Vector3f.from(
            sx + dx / len * speed + jx,
            sy + ((target.y + yOffset.value) - sy).coerceIn(-speed, speed),
            sz + dz / len * speed + jz
        )
    }

    private fun strafeAround(target: EntityTracker.TrackedEntity): Vector3f {
        strafeAngle += strafeSpeed.value * 0.05
        val r = strafeRadius.value
        val jx = (Math.random().toFloat() - 0.5f) * 2f * jitter.value
        val jz = (Math.random().toFloat() - 0.5f) * 2f * jitter.value
        return Vector3f.from(
            target.x + (cos(strafeAngle) * r).toFloat() + jx,
            target.y + yOffset.value,
            target.z + (sin(strafeAngle) * r).toFloat() + jz
        )
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
        val candidates = EntityTracker.getEntitiesInRange(maxRange.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isPlayer &&
                !(ignoreFriends.value && e.isFriendEntity) &&
                !(antiBot.value && e.isLikelyBot())
        }
        val found = candidates.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
        cachedTarget = found
        return found
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
