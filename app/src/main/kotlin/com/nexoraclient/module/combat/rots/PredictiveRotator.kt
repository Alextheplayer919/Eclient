package com.rubidiumclient.module.combat.rots

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.MathUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs

/**
 * Pegamento entre la KillAura y el motor [PredictiveAim] (port del PredictiveRotator de Lynx).
 *
 * Por tick (PlayerAuthInputPacket):
 *  1. alimenta el tracker de movimiento con TODOS los candidatos (un objetivo nuevo ya llega con
 *     velocidad estimada);
 *  2. elige objetivo con histeresis (no salta entre jugadores a distancias casi iguales) y lo deja
 *     en targets[0];
 *  3. convierte posiciones a una misma referencia (pies del objetivo, ojos del jugador local);
 *  4. avanza el motor un paso y, con [Settings.cameraSync], gira tambien la camara visible.
 *
 * La KillAura solo tiene que llamar a [update] en cada tick, copiar [pitch]/[yaw] al
 * PlayerAuthInputPacket cuando devuelva true, y llamar a [reset] al activarse/desactivarse.
 *
 * Diferencia con Lynx: aqui no hay `GameSession`/`Entity`. Las posiciones y la convencion de
 * alturas las da [RotsSupport] sobre [EntityTracker] (jugadores a nivel de ojos = pies + 1.62).
 */
class PredictiveRotator {

    class Settings(
        val predTime: Float,
        val predStrength: Float,
        val smoothing: Float,
        val maxSpeed: Float,
        val accel: Float,
        val cameraSync: Boolean,
        val vertOffset: Float
    )

    private val aim = PredictiveAim()
    private var lockedId = -1L
    private var lockedSinceTick = 0L
    private var lastSyncYaw = Float.NaN
    private var lastSyncPitch = Float.NaN

    var pitch = 0f
        private set
    var yaw = 0f
        private set

    /** true mientras la KillAura debe sobreescribir la rotacion del cliente con [pitch]/[yaw]. */
    val isOverriding: Boolean get() = aim.isOverriding

    fun reset() {
        aim.reset()
        lockedId = -1L
        lockedSinceTick = 0L
        lastSyncYaw = Float.NaN
        lastSyncPitch = Float.NaN
        pitch = 0f
        yaw = 0f
    }

    /**
     * @param targets candidatos ordenados por prioridad (targets[0] = preferido). Se reordena en
     * situ para que el objetivo bloqueado quede en el indice 0.
     * @return true si la KillAura debe sobreescribir la rotacion en este tick.
     */
    fun update(
        session: RubidiumRelaySession,
        packet: PlayerAuthInputPacket,
        targets: MutableList<EntityTracker.TrackedEntity>,
        s: Settings
    ): Boolean {
        val tick = packet.tick
        aim.config = PredictiveAim.Config(
            predictionTicks = s.predTime,
            predictionStrength = s.predStrength,
            smoothing = s.smoothing,
            maxYawSpeed = s.maxSpeed,
            turnAccel = s.accel
        )

        for (e in targets) aim.observe(e.runtimeId, e.x, RotsSupport.entityFeetY(e), e.z, tick)
        aim.retain(targets.map { it.runtimeId })

        if (targets.isEmpty()) {
            lockedId = -1L
            if (!aim.isOverriding) return false
            if (s.cameraSync) {
                // La camara ya esta donde la dejamos: solo se deja de girar con frenado suave.
                aim.release(aim.yaw, aim.pitch, tick)
            } else {
                // Sin camara sincronizada: volver suavemente a la rotacion real del cliente.
                aim.release(packet.rotation.y, packet.rotation.x, tick)
            }
            publish()
            syncCamera(session, packet, s)
            return aim.isOverriding
        }

        applyTargetLock(targets, tick)
        val handler = targets[0]
        if (handler.runtimeId != lockedId) {
            lockedId = handler.runtimeId
            lockedSinceTick = tick
        }

        // Al adquirir objetivo se parte de la rotacion real de la camara (sin salto inicial).
        if (!aim.isOverriding) aim.seed(packet.rotation.y, packet.rotation.x, tick)

        aim.step(
            EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ,
            handler.runtimeId,
            handler.x, RotsSupport.entityFeetY(handler), handler.z,
            RotsSupport.hitboxWidth(handler), RotsSupport.hitboxHeight(handler),
            s.vertOffset,
            tick
        )
        publish()
        syncCamera(session, packet, s)
        return true
    }

    private fun publish() {
        pitch = aim.pitch
        yaw = aim.yaw
    }

    /**
     * Mantiene el objetivo actual salvo que otro este claramente mas cerca y el actual lleve un
     * rato bloqueado. Evita que la mira salte entre jugadores cuyas distancias son casi iguales.
     */
    private fun applyTargetLock(targets: MutableList<EntityTracker.TrackedEntity>, tick: Long) {
        val nearest = targets[0]
        val current = if (lockedId == -1L) null else targets.firstOrNull { it.runtimeId == lockedId }

        val chosen = when {
            current == null -> nearest
            nearest === current -> current
            else -> {
                val dCur = distance(current)
                val dNear = distance(nearest)
                val clearlyCloser = dNear < dCur * LOCK_SWITCH_RATIO && dCur - dNear > LOCK_SWITCH_MIN_GAIN
                if (clearlyCloser && tick - lockedSinceTick >= LOCK_MIN_HOLD_TICKS) nearest else current
            }
        }
        if (chosen !== targets[0]) {
            targets.remove(chosen)
            targets.add(0, chosen)
        }
    }

    private fun distance(e: EntityTracker.TrackedEntity): Float =
        MathUtil.dist3(e.x, e.y, e.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

    /**
     * Gira la camara visible del cliente hacia (pitch, yaw) con un MovePlayerPacket (Mode.NORMAL)
     * hacia el cliente sobre el jugador local. La posicion enviada es la del ultimo input mas un
     * tick de movimiento, para que si el cliente la aplica no lo arrastre hacia atras. Solo se
     * envia cuando la rotacion cambio de verdad.
     */
    private fun syncCamera(session: RubidiumRelaySession, packet: PlayerAuthInputPacket, s: Settings) {
        if (!s.cameraSync) return
        if (!lastSyncYaw.isNaN()) {
            var dYaw = yaw - lastSyncYaw
            while (dYaw > 180f) dYaw -= 360f
            while (dYaw < -180f) dYaw += 360f
            if (abs(dYaw) < SYNC_EPS && abs(pitch - lastSyncPitch) < SYNC_EPS) return
        }
        lastSyncYaw = yaw
        lastSyncPitch = pitch

        val motion = RotsSupport.localMotion(packet)
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            // Solo se adelanta en horizontal y con tope. En vertical NO se adelanta: el delta
            // vertical crudo es ruidoso al caer/volar y al aplicarlo cada tick hundia o elevaba
            // al jugador y provocaba lagback.
            position = Vector3f.from(
                packet.position.x + motion.first.coerceIn(-MAX_SYNC_STEP, MAX_SYNC_STEP),
                packet.position.y,
                packet.position.z + motion.third.coerceIn(-MAX_SYNC_STEP, MAX_SYNC_STEP)
            )
            rotation = Vector3f.from(pitch, yaw, yaw)
            mode = MovePlayerPacket.Mode.NORMAL
            // Sondeo de suelo igual que en Kiwi: hay colision vertical y no se esta subiendo.
            onGround = packet.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION) &&
                       motion.second <= 0f
            ridingRuntimeEntityId = 0
            tick = packet.tick
        })
    }

    private companion object {
        const val SYNC_EPS = 0.01f
        const val MAX_SYNC_STEP = 0.6f

        // Cambio de objetivo: solo si el nuevo esta >25% mas cerca y al menos 0.75 bloques mas
        // cerca, y el actual lleva al menos 6 ticks bloqueado.
        const val LOCK_SWITCH_RATIO = 0.75f
        const val LOCK_SWITCH_MIN_GAIN = 0.75f
        const val LOCK_MIN_HOLD_TICKS = 6L
    }
}
