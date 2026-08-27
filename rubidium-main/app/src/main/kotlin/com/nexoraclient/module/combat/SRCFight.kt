package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SCRFighter : BaseModule(
    name        = "SCRFighter",
    category    = ModuleCategory.COMBAT,
    description = "Yüksek/alçak strafe döngüsü + timer, düşük canda hızlanır"
), PacketEventBus.PacketListener {

    enum class PriorityMode { Closest, LowestHealth, Direction }

    // Döngü fazları: yukarıda dön → hızlı in → aşağıda dön → (N tur sonra) tekrar yukarı çık
    private enum class Phase { HIGH_STRAFE, DROPPING, LOW_STRAFE, RISING }

    private val priorityMode     = enum ("Priority",            PriorityMode.LowestHealth)
    private val detectRange      = 256f

    // ── Yaklaşma (TPAura'daki gibi) ───────────────────────────────────────
    private val horizontalSpeed  = float("Horizontal Speed",    3.29f, 0.5f, 8f)
    private val verticalSpeed    = float("Vertical Speed",      1.8f,  0.1f, 8f)

    // ── Strafe döngüsü ─────────────────────────────────────────────────────
    private val range            = float("Range",               2.4f,  0.5f, 8f)
    private val strafeSpeed      = float("Strafe Speed",        2.4f,  0f,  50f)
    private val highOffset       = float("High Offset",         2.0f,  0.5f, 4f)
    private val lowOffset        = float("Low Offset",          0.0f, -1f,  2f)
    private val turnsPerLevel    = int  ("Turns Per Level",     2,     1,   6)
    private val dropSpeed        = float("Drop Speed",          1.2f,  0f,  10f)

    // ── Düşük can modu: rakip canı eşiğin altına inince high/low farkı
    // kalkar, ikisi de aynı sabit yüksekliğe (LowHealth Offset) çekilir ve
    // döngü hızlanır — rakip kaçmadan önce yakalama fazı ──────────────────
    private val lowHealthEnabled = bool ("LowHealth Mode",      true)
    private val lowHealthOffset  = float("LowHealth Offset",    0.5f, -1f,  2f)
    private val lowHealthAt      = float("LowHealth Threshold", 8f,    1f,  20f)
    private val lowHealthSpeedX  = float("LowHealth Speed x",   1.35f, 1.0f, 3f)

    // ── Timer ──────────────────────────────────────────────────────────────
    private val timerEnabled     = bool ("Timer",                true)
    private val timerBase        = float("Timer Base",          1.0f,  1.0f, 3f)
    private val timerLowHealth   = float("Timer LowHealth",     1.6f,  1.0f, 4f)

    private val rotateToTarget   = bool ("Rotate To Target",    true)
    private val ignoreFriends    = bool ("Ignore Friends",      true)
    private val antiBot          = bool ("Anti Bot",            true)
    private val shortcut         = bool ("Shortcut",            false)

    // ── State ──────────────────────────────────────────────────────────────
    private var phase          = Phase.HIGH_STRAFE
    private var strafeAngle    = 0.0
    private var turnsDone      = 0
    private var dropProgress   = 0f     // 0f (yukarıda) → 1f (aşağıda) veya tersi

    @Volatile private var lastFindMs    = 0L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null

    private val TARGET_CACHE_MS = 100L

    companion object {
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
    }

    override fun onEnable() {
        super.onEnable()
        phase        = Phase.HIGH_STRAFE
        strafeAngle  = Random.nextDouble(0.0, Math.PI * 2)
        turnsDone    = 0
        dropProgress = 0f
        lastFindMs   = 0L
        cachedTarget = null
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val target = getCachedTarget() ?: return

        val isLowHealth = lowHealthEnabled.value && target.health <= lowHealthAt.value

        // ── Timer: tick sayacını hızlandırıyoruz ─────────────────────────
        // Sunucu client'ın "ne kadar zaman geçti" iddiasına göre hareket
        // mesafesini kabul eder; tick'i normalden hızlı artırmak sunucunun
        // bizi daha hızlı hareket ediyor sanmasını sağlar. Kapalıyken
        // pkt.tick'e hiç dokunulmuyor.
        // BUG FIX: pkt.tick mutasyonu eskiden hiçbir zaman event.cancelAndReplace
        // ile gönderilmiyordu — bu projede in-place paket mutasyonu, açıkça
        // cancelAndReplace çağrılmadıkça SUNUCUYA ASLA ULAŞMIYOR (bkz.
        // Criticals/KillAuraPro/AntiCrystal'daki aynı desen). Yani Timer
        // özelliği şimdiye kadar sessizce hiçbir işe yaramıyordu.
        var tickMutated = false
        if (timerEnabled.value) {
            val timerMult = if (isLowHealth) timerLowHealth.value else timerBase.value
            if (timerMult > 1.0f) {
                pkt.tick = pkt.tick + ((timerMult - 1.0f) * 20).toLong()
                tickMutated = true
            }
        }
        if (tickMutated) event.cancelAndReplace(pkt)

        moveAndRotate(event, target, isLowHealth)
    }

    private fun getCachedTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        if (now - lastFindMs >= TARGET_CACHE_MS) {
            val fresh = findTarget()
            if (fresh?.runtimeId != cachedTarget?.runtimeId) {
                // Hedef değişti — döngüyü baştan başlat, yarım kalmış
                // pozisyon geçişinden dolayı sıçrama olmasın
                resetCycle()
            }
            cachedTarget = fresh
            lastFindMs   = now
        }
        return cachedTarget
    }

    private fun resetCycle() {
        phase        = Phase.HIGH_STRAFE
        turnsDone    = 0
        dropProgress = 0f
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val candidates = EntityTracker.getEntitiesInRange(detectRange)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (antiBot.value) it.filterNot { e -> e.isLikelyBot() } else it }

        return when (priorityMode.value) {
            PriorityMode.Closest      -> candidates.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
            PriorityMode.LowestHealth -> candidates.minByOrNull { it.health }
            PriorityMode.Direction    -> candidates.minByOrNull { EntityTracker.angleToEntity(it) }
        }
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    private fun moveAndRotate(event: PacketEvent, target: EntityTracker.TrackedEntity, isLowHealth: Boolean) {
        val session = event.session

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val targetBaseX = target.x
        val targetBaseZ = target.z
        val targetBaseY = target.y

        // Önce hedefe yaklaşma mesafesi kontrolü (TPAura ile aynı mantık) —
        // uzaktaysak strafe hesaplamadan önce horizontalSpeed/verticalSpeed
        // ile adım adım yaklaşıyoruz, aksi halde ilk temasta ışınlanma gibi
        // görünür.
        val roughTargetY = targetBaseY + (if (isLowHealth) lowHealthOffset.value else highOffset.value)
        val distToTarget = MathUtil.dist3(selfX, selfY, selfZ, targetBaseX, roughTargetY, targetBaseZ)

        val newPos: Vector3f

        if (distToTarget > range.value * 2f) {
            newPos = stepTowardTarget(selfX, selfY, selfZ, targetBaseX, roughTargetY, targetBaseZ)
        } else {
            // Düşük can modunda yükseklik seviyeleri sıkışır — hem high hem
            // low aynı lowHealthOffset'e iner, döngü sadece hızlanır
            val highY = if (isLowHealth) lowHealthOffset.value else highOffset.value
            val lowY  = if (isLowHealth) lowHealthOffset.value else lowOffset.value

            val radius = range.value
            val angularStep = strafeSpeed.value * 0.05 * (if (isLowHealth) lowHealthSpeedX.value.toDouble() else 1.0)

            newPos = when (phase) {

                Phase.HIGH_STRAFE, Phase.LOW_STRAFE -> {
                    // Tek yönlü açısal artış — asla geri gitmez, strafe
                    // bug'ına yol açan "ileri-geri salınım" burada yok
                    strafeAngle += angularStep
                    val y = if (phase == Phase.HIGH_STRAFE) highY else lowY
                    val x = targetBaseX + (cos(strafeAngle) * radius).toFloat()
                    val z = targetBaseZ + (sin(strafeAngle) * radius).toFloat()

                    if (strafeAngle >= Math.PI * 2) {
                        strafeAngle -= Math.PI * 2
                        turnsDone++
                        if (turnsDone >= turnsPerLevel.value) {
                            turnsDone = 0
                            phase = if (phase == Phase.HIGH_STRAFE) {
                                dropProgress = 0f
                                Phase.DROPPING
                            } else {
                                dropProgress = 1f
                                Phase.RISING
                            }
                        }
                    }

                    Vector3f.from(x, targetBaseY + y, z)
                }

                Phase.DROPPING -> {
                    // Hızlı/ani iniş: dropSpeed oranında ilerler, HIGH'dan
                    // LOW'a birkaç tick içinde iner
                    dropProgress += dropSpeed.value * 0.15f
                    strafeAngle  += angularStep * 0.4

                    if (dropProgress >= 1f) {
                        dropProgress = 1f
                        phase = Phase.LOW_STRAFE
                    }

                    val y = highY + (lowY - highY) * dropProgress
                    val x = targetBaseX + (cos(strafeAngle) * radius).toFloat()
                    val z = targetBaseZ + (sin(strafeAngle) * radius).toFloat()
                    Vector3f.from(x, targetBaseY + y, z)
                }

                Phase.RISING -> {
                    // LOW'dan HIGH'a geri çıkış
                    dropProgress -= dropSpeed.value * 0.12f
                    strafeAngle  += angularStep * 0.4

                    if (dropProgress <= 0f) {
                        dropProgress = 0f
                        phase = Phase.HIGH_STRAFE
                    }

                    val y = highY + (lowY - highY) * dropProgress
                    val x = targetBaseX + (cos(strafeAngle) * radius).toFloat()
                    val z = targetBaseZ + (sin(strafeAngle) * radius).toFloat()
                    Vector3f.from(x, targetBaseY + y, z)
                }
            }
        }

        val rot = if (rotateToTarget.value) RotationUtil.toEntity(target) else null

        // TPAura az önce pozisyonu değiştirdiyse üstüne binmiyoruz — iki
        // modülün aynı anda pozisyonu farklı yönlerde değiştirip görüntü
        // titremesi/rubber-band yaratmasını engeller (KillAuraPro/AutoHvH/
        // HitAndRunPro'daki ile aynı desen).
        if (System.currentTimeMillis() - TPAura.lastPositionOverrideMs < TPAURA_CONFLICT_WINDOW_MS) return

        val yaw   = rot?.yaw   ?: EntityTracker.selfYaw
        val pitch = rot?.pitch ?: EntityTracker.selfPitch

        EntityTracker.selfX = newPos.x
        EntityTracker.selfY = newPos.y
        EntityTracker.selfZ = newPos.z
        if (rot != null) {
            EntityTracker.selfYaw   = rot.yaw
            EntityTracker.selfPitch = rot.pitch
        }

        // PvP Timer açıksa büyük tek-adım sıçramaları TimerPvP üzerinden
        // meşru boyutlu adımlara bölünerek gönderilir (rubber-band önlenir).
        // Kapalıysa tek paket, anında — eski davranışla birebir aynı.
        scope.launch {
            try {
                com.rubidiumclient.utils.TimerPvP.dispatch(
                    session, selfX, selfY, selfZ, newPos.x, newPos.y, newPos.z, yaw, pitch, onGround = false
                )
            } catch (_: Exception) {}
        }
    }

    // TPAura'daki stepTowardTarget ile aynı mantık — horizontalSpeed ve
    // verticalSpeed burada devreye giriyor, yaklaşma hızını bu ayarlar belirler.
    private fun stepTowardTarget(selfX: Float, selfY: Float, selfZ: Float, tx: Float, ty: Float, tz: Float): Vector3f {
        val direction = atan2(
            (tz - selfZ).toDouble(),
            (tx - selfX).toDouble()
        ) - Math.toRadians(90.0)

        val newX = selfX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = selfZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = ty.coerceIn(selfY - verticalSpeed.value, selfY + verticalSpeed.value)
        return Vector3f.from(newX, newY, newZ)
    }
}
