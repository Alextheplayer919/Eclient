package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.PacketUtil
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class Timer : BaseModule(
    name        = "Timer",
    category    = ModuleCategory.MOVEMENT,
    description = "Gerçek hareket/saldırı paketlerini ek enjeksiyonla çoğaltıp motoru hızlanmış gibi gösterir"
), PacketEventBus.PacketListener {

    // Tek bir alanı (tick) şişirmek yeterli değil — sunucu hızını ardışık
    // gerçek paketlerin GERÇEK ZAMANDA ne sıklıkla geldiğine bakarak
    // belirliyor. Bu yüzden burada "yalan söylemek" yerine, gerçek istemcinin
    // ürettiği her pakete ek olarak, aynı yöndeki hareketi/saldırıyı
    // enterpole ederek gerçekten daha SIK paket gönderiyoruz — PC tarafındaki
    // setTimerSpeed'in motoru gerçekten hızlandırmasına en yakın taklit bu.
    private val motionMultiplier = float("Motion Multiplier", 1.0f, 1.0f, 4.0f)
    private val attackMultiplier = float("Attack Multiplier", 1.0f, 1.0f, 4.0f)
    private val stepDelayMs      = int  ("Step Delay (ms)",    12,   2,   50)
    private val minMoveThreshold = float("Min Move Threshold", 0.02f, 0f, 0.5f)

    // PvP: TPAura/AuraV3'ün ham pozisyon sıçramalarını (Horizontal/Vertical
    // Speed) sunucuya "meşru" boyutta küçük adımlara bölünmüş gibi göstererek
    // rubber-band'i (geri atmayı) engeller. Bkz. TimerPvP.kt.
    private val pvpMode          = bool ("PvP Timer",           false)
    private val pvpMaxStepSetting = float("PvP Max Step",       0.9f,  0.3f, 2.0f)
    private val pvpStepDelay     = int  ("PvP Step Delay (ms)", 12,    2,   40)

    val pvpModeEnabled: Boolean get() = pvpMode.value
    val pvpMaxStep: Float       get() = pvpMaxStepSetting.value
    val pvpStepDelayMs: Long    get() = pvpStepDelay.value.toLong()

    @Volatile private var lastRealX    = 0f
    @Volatile private var lastRealY    = 0f
    @Volatile private var lastRealZ    = 0f
    @Volatile private var hasLastReal  = false

    override fun onEnable() {
        super.onEnable()
        hasLastReal = false
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        hasLastReal = false
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val session = event.session

        when (val pkt = event.effectivePacket) {
            is PlayerAuthInputPacket ->
                handleMotion(
                    session,
                    pkt.position.x, pkt.position.y, pkt.position.z,
                    pkt.rotation.y, pkt.rotation.x,
                    EntityTracker.selfOnGround
                )
            is MovePlayerPacket ->
                handleMotion(
                    session,
                    pkt.position.x, pkt.position.y, pkt.position.z,
                    pkt.rotation.y, pkt.rotation.x,
                    pkt.isOnGround
                )
            is InventoryTransactionPacket ->
                if (pkt.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY) {
                    handleAttack(session, pkt.runtimeEntityId)
                }
            is AnimatePacket ->
                if (pkt.action == AnimatePacket.Action.SWING_ARM) {
                    handleSwing(session)
                }
        }
    }

    private fun handleMotion(
        session: RubidiumRelaySession,
        x: Float, y: Float, z: Float,
        yaw: Float, pitch: Float,
        onGround: Boolean
    ) {
        val extra = motionMultiplier.value - 1f

        if (hasLastReal && extra > 0.01f) {
            val dx = x - lastRealX
            val dy = y - lastRealY
            val dz = z - lastRealZ

            // Oyuncu duruyorsa (dx/dz ~0) ekstra adım üretmenin faydası yok,
            // sadece boş paket trafiği yaratır — sadece gerçek hareket varken
            // devreye giriyoruz.
            if (dx * dx + dz * dz > minMoveThreshold.value * minMoveThreshold.value) {
                val fullSteps  = extra.toInt().coerceIn(0, 3)
                val fractional = extra - fullSteps

                scope.launch {
                    var cx = x; var cy = y; var cz = z
                    repeat(fullSteps) {
                        cx += dx; cy += dy; cz += dz
                        PacketUtil.sendMove(session, cx, cy, cz, yaw, pitch, onGround)
                        EntityTracker.selfX = cx; EntityTracker.selfY = cy; EntityTracker.selfZ = cz
                        delay(stepDelayMs.value.toLong())
                    }
                    if (fractional > 0.05f) {
                        cx += dx * fractional; cy += dy * fractional; cz += dz * fractional
                        PacketUtil.sendMove(session, cx, cy, cz, yaw, pitch, onGround)
                        EntityTracker.selfX = cx; EntityTracker.selfY = cy; EntityTracker.selfZ = cz
                    }
                }
            }
        }

        lastRealX = x; lastRealY = y; lastRealZ = z
        hasLastReal = true
    }

    private fun handleAttack(session: RubidiumRelaySession, targetRid: Long) {
        val extra = (attackMultiplier.value - 1f).toInt().coerceIn(0, 3)
        if (extra <= 0 || targetRid == 0L) return
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        scope.launch {
            repeat(extra) {
                delay(stepDelayMs.value.toLong())
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, targetRid, slot)
            }
        }
    }

    private fun handleSwing(session: RubidiumRelaySession) {
        // InventoryTransactionPacket (asıl saldırı) zaten AnimatePacket'i
        // tetikliyorsa handleAttack yeterli — bu sadece SADECE sallama
        // gönderilip attack paketi gelmeyen durumlar (boşa sallama) için
        // ekstra bir çoğaltma yapmaz, çünkü CritLock/hedef bilgisi burada yok.
        // Sadece görsel tutarlılık için saf swing çoğaltımı burada bırakıldı.
        val extra = (attackMultiplier.value - 1f).toInt().coerceIn(0, 3)
        if (extra <= 0) return

        scope.launch {
            repeat(extra) {
                delay(stepDelayMs.value.toLong())
                PacketUtil.sendSwing(session)
            }
        }
    }
}
