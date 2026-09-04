package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * HitAndRunProModule ("HitAndRunPro")
 *
 * Hedefe vurur, hemen ardindan dairesel bir sicrama ile pozisyon degistirir
 * (karsi vurustan kacinmayi kolaylastirir).
 *
 * Referans dosyadan farklar (guclendirme / bug fix):
 *  - KRITIK BUG FIX: Referans `SetEntityMotionPacket`'i SADECE
 *    `session.clientBound(...)` ile gonderiyordu. Bu SADECE kendi ekranini
 *    gunceller, SUNUCU bu hiz uygulamasindan HABERDAR OLMAZ — yani referanstaki
 *    "kacis" hareketinin sunucu tarafinda gercek etkisi olmazdi (bir sonraki
 *    gercek pozisyon paketinde sunucu seni eski yere geri cekerdi, gorunmez
 *    rubber-band). Artik hem `serverBound` hem `clientBound` gonderiliyor.
 *  - Referans `nearbyEntities.first()` ile RASTGELE (entityMap ic sirasina
 *    bagli) bir hedef seciyordu. Artik EN YAKIN hedef seciliyor.
 *  - Bot/friend filtreleme eklendi (referansta yoktu).
 *  - TPAura ile cakisma-onleme: TPAura az once pozisyonu degistirdiyse
 *    (TPAura.lastPositionOverrideMs), bu modul o pencerede kendi kacis
 *    hareketini GONDERMIYOR — saldiri yine de yapiliyor, sadece hareket
 *    erteleniyor. Bu, iki modulun ayni anda pozisyonu farkli yonlerde
 *    degistirip goruntu titremesi yaratmasini engeller.
 */
class HitAndRunProModule : BaseModule(
    name        = "HitAndRunPro",
    category    = ModuleCategory.COMBAT,
    description = "Vurur, hemen ardından dairesel bir sıçramayla uzaklaşır"
) {

    companion object {
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
    }

    private val range         = float("Range", 4.0f, 2f, 6f)
    private val hitDelayMs    = int  ("Hit Delay", 200, 100, 1000)
    private val jumpHeight    = float("Jump Height", 0.42f, 0.1f, 1f)
    private val circleRadius  = float("Circle Radius", 1.5f, 0.5f, 3f)
    private val playersOnly   = bool ("Players Only", true)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val antiBot       = bool ("Anti Bot", true)
    private val shortcut = bool("Shortcut", false)

    @Volatile private var lastHitMs = 0L
    private var comboAngle = 0f

    override fun onEnable() {
        super.onEnable()
        lastHitMs = 0L
        comboAngle = 0f
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastHitMs < hitDelayMs.value) return

        val target = findTarget() ?: return
        lastHitMs = now
        executeCombo(event.session, target)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        return EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId &&
                (if (playersOnly.value) e.isPlayer else true) &&
                !(ignoreFriends.value && e.isFriendEntity) &&
                !(antiBot.value && playersOnly.value && e.isPlayer && e.isLikelyBot())
        }.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun executeCombo(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val click = Vector3f.from(target.x, target.y + 1.5f, target.z)
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, slot, click)

        // TPAura az once pozisyonu degistirdiyse ustune binmiyoruz, sadece
        // saldiri yapilir, kacis hareketi bu turda atlanir.
        if (System.currentTimeMillis() - TPAura.lastPositionOverrideMs < TPAURA_CONFLICT_WINDOW_MS) return

        comboAngle += 45f
        if (comboAngle >= 360f) comboAngle = 0f
        val rad = Math.toRadians(comboAngle.toDouble())
        val offsetX = (circleRadius.value * cos(rad)).toFloat()
        val offsetZ = (circleRadius.value * sin(rad)).toFloat()

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(offsetX, jumpHeight.value, offsetZ)
        }
        session.serverBound(motionPacket)
        session.clientBound(motionPacket)
        TPAura.notifyExternalPositionOverride()
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
