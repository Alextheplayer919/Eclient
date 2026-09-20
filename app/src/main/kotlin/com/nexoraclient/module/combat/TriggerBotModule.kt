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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TriggerBotModule ("TriggerBot")
 *
 * Referans dosyadan farklar (guclendirme):
 *  - Referans sadece TEK bir gövde noktasina (ayak/gogus/kafa, 3 nokta) bakip
 *    ilk uygun ENTITY'yi (.firstOrNull()) seciyordu — birden fazla hedef FOV
 *    icindeyse hangisinin secilecegi entityMap'in ic sirasina baglı, rastgele
 *    gibi davraniyordu. Artik TUM adaylar arasindan crosshair'a EN YAKIN
 *    (en dusuk acili) hedef seciliyor.
 *  - Referansta CPS sabit degerdi (tek deger), digger modullerle (KillAura,
 *    TPAura) tutarli olsun diye Min/Max araligina cevrildi (MathUtil.cpsToDelayMs
 *    zaten diger tum combat modullerinde bu sekilde kullaniliyor).
 *  - Bot/friend filtreleme mantigi KillAura/TPAura ile ayni (isFriendEntity,
 *    isLikelyBot) — boylece "Ignore Friends" bir modulde calisip digerinde
 *    calismama tutarsizligi olmuyor.
 *  - Opsiyonel LOS (line-of-sight) kontrolu eklendi (MathUtil.hasLineOfSight,
 *    WorldBlockTracker verisine dayanir) — duvarin arkasindaki bir hedefe
 *    bakiyormus gibi gorunse bile (ör. cam/ince blok) saldirmiyor.
 */
class TriggerBotModule : BaseModule(
    name        = "TriggerBot",
    category    = ModuleCategory.COMBAT,
    description = "Nişangah bir hedefin üstündeyken otomatik saldırır"
) {

    private val cpsMin        = int  ("CPS Min",  10,   1,  20)
    private val cpsMax        = int  ("CPS Max",  14,   1,  20)
    private val range         = float("Range",    4.0f, 2f, 7f)
    private val aimTolerance  = float("Aim Tolerance", 4f, 0.5f, 20f) // derece — crosshair ile hedef arasi izin verilen max aci
    private val playersOnly   = bool ("Players Only", true)
    private val mobsOnly      = bool ("Mobs Only",    false)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val antiBot       = bool ("Anti Bot",      true)
    private val requireLos    = bool ("Require LOS",   true)
    private val shortcut = bool("Shortcut", false)

    @Volatile private var lastAttackMs = 0L

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val now     = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        val target = findLookedAtTarget() ?: return
        if (requireLos.value && !hasLos(target)) return

        lastAttackMs = now
        val session = event.session
        val slot  = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val click = Vector3f.from(target.x, target.y + 1.5f, target.z)
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, slot, click)
    }

    private fun findLookedAtTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isTarget()
        }
        if (candidates.isEmpty()) return null

        var best: EntityTracker.TrackedEntity? = null
        var bestAngle = aimTolerance.value
        for (e in candidates) {
            // ayak / govde / kafa — 3 noktanin en iyi hizalanani (referanstaki
            // gibi ince/uzun hitbox'lara karsi tolerans)
            val angle = minOf(
                angleToLookVector(e.x, e.y,        e.z),
                angleToLookVector(e.x, e.y + 0.9f, e.z),
                angleToLookVector(e.x, e.y + 1.8f, e.z)
            )
            if (angle <= bestAngle) { bestAngle = angle; best = e }
        }
        return best
    }

    // Bakis vektoru ile hedefe olan yon vektoru arasindaki aciyi hesaplar.
    // Yaw/pitch isaret kurallari RotationUtil.toPoint ile ayni kurala uyar.
    private fun angleToLookVector(tx: Float, ty: Float, tz: Float): Float {
        val dx = (tx - EntityTracker.selfX).toDouble()
        val dy = (ty - (EntityTracker.selfY + 1.62f)).toDouble()
        val dz = (tz - EntityTracker.selfZ).toDouble()
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4) return 0f

        // FIX: RotationUtil.toPoint -> yaw=atan2(-dx,dz) -> forward: fx=-sin(yaw), fz=cos(yaw)
        // Eskiden yaw+90 java-edition offseti kullaniliyordu (yanlis).
        val yawRad   = Math.toRadians(EntityTracker.selfYaw.toDouble())
        val pitchRad = Math.toRadians(-EntityTracker.selfPitch.toDouble())
        val lookX = -sin(yawRad) * cos(pitchRad)
        val lookY =  sin(pitchRad)
        val lookZ =  cos(yawRad) * cos(pitchRad)

        val dot = ((dx * lookX + dy * lookY + dz * lookZ) / dist).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot)).toFloat()
    }

    private fun hasLos(t: EntityTracker.TrackedEntity): Boolean = MathUtil.hasLineOfSight(
        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ,
        t.x, t.y + 1.2f, t.z
    )

    private fun EntityTracker.TrackedEntity.isTarget(): Boolean = when {
        isPlayer  -> playersOnly.value && !(ignoreFriends.value && isFriendEntity) && !(antiBot.value && isLikelyBot())
        isHostile -> mobsOnly.value
        else      -> false
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
