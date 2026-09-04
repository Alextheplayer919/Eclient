package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * ACAModule ("ACA")
 *
 * Hafif bir "KillAura + kisa mesafe TP" kombinasyonu: hedefi bulur,
 * (istege bagli) hemen arkasina kisa bir isinlanma yapar, CPS'e gore
 * saldirir.
 *
 * Referans dosyadan farklar (guclendirme / bug fix):
 *  - KRITIK BUG FIX: Referans MovePlayerPacket'i SADECE
 *    `session.clientBound(...)` ile gonderiyordu — sunucu isinlanmayi hic
 *    ogrenmiyordu (gercek etkisi sifirdi). Artik `serverBound` +
 *    `clientBound` birlikte gonderiliyor.
 *  - TPAura ile cakisma-onleme: TPAura acikken bu modul kendi TP'sini
 *    devre disi birakiyor, sadece saldiriyor (iki modulun ayni anda
 *    pozisyonu degistirip rubber-band yaratmasini engeller).
 *  - Bot/friend filtreleme (referansta yoktu) ve `isBot()` icin oyuncu
 *    bulunamadiginda GUVENLI TARAF (bot varsay, hedefleme) yerine
 *    KillAura/TPAura ile ayni davranisa getirildi.
 *  - `isTarget()` referansta hem playersOnly hem mobsOnly false ise HICBIR
 *    seyi hedeflemiyordu (sessiz/anlasilmasi zor bir davranis). Varsayilan
 *    degerler (playersOnly=true, mobsOnly=false) ile bu durumu pratikte
 *    engelliyoruz; ayrica davranis yorumlarda acikca belirtiliyor.
 */
class ACAModule : BaseModule(
    name        = "ACA",
    category    = ModuleCategory.COMBAT,
    description = "Hedefe kısa mesafeden ışınlanıp CPS'e göre saldırır"
) {

    companion object {
        private const val TPAURA_CONFLICT_WINDOW_MS = 120L
    }

    private val playersOnly   = bool ("Players Only", true)
    private val mobsOnly      = bool ("Mobs Only", false)
    private val tpEnabled     = bool ("TP Aura", true)
    private val range         = float("Range", 7.0f, 2f, 10f)
    private val cpsMin        = int  ("CPS Min", 10, 1, 20)
    private val cpsMax        = int  ("CPS Max", 14, 1, 20)
    private val tpIntervalMs  = int  ("TP Speed", 150, 50, 2000)
    private val tpYOffset     = float("Y Offset", 0f, -10f, 10f)
    private val keepDistance  = float("Keep Distance", 2.0f, 1f, 10f)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val antiBot       = bool ("Anti Bot", true)
    private val shortcut = bool("Shortcut", false)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastTpMs = 0L

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastTpMs = 0L
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
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        val target = findTarget() ?: return
        val session = event.session

        if (tpEnabled.value && now - lastTpMs >= tpIntervalMs.value &&
            ModuleManager.byName("TPAura")?.isEnabled != true
        ) {
            teleportBehind(session, target)
            lastTpMs = now
        }

        lastAttackMs = now
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        val click = Vector3f.from(target.x, target.y + 1.5f, target.z)
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, slot, click)
    }

    private fun teleportBehind(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val targetYawRad = Math.toRadians(target.yaw.toDouble()).toFloat()
        val dirX = sin(targetYawRad)
        val dirZ = -cos(targetYawRad)
        val len = sqrt(dirX * dirX + dirZ * dirZ).let { if (it == 0f) 1f else it }
        val nx = dirX / len
        val nz = dirZ / len

        val newPos = Vector3f.from(
            target.x + nx * keepDistance.value,
            target.y + tpYOffset.value,
            target.z + nz * keepDistance.value
        )
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

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        return EntityTracker.getEntitiesInRange(range.value) { e ->
            e.runtimeId != EntityTracker.selfRuntimeId && e.isTarget()
        }.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun EntityTracker.TrackedEntity.isTarget(): Boolean = when {
        isPlayer  -> playersOnly.value && !(ignoreFriends.value && isFriendEntity) && !(antiBot.value && isLikelyBot())
        isHostile -> mobsOnly.value
        else      -> false
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L
}
