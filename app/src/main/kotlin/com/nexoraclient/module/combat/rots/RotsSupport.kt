package com.rubidiumclient.module.combat.rots

import com.rubidiumclient.core.proxy.EntityTracker
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Capa de adaptacion entre este cliente (EntityTracker / PlayerAuthInputPacket) y los motores de
 * rotacion portados de Kiwi/Lynx ([KillauraRots], [PredictiveAim], [PredictiveRotator],
 * [AbysmRot], [GeoRot], [HvHGod]).
 *
 * Los motores NO se tocan: esperan exactamente el mismo contrato que tenian en su cliente de
 * origen. Lo que cambia es quien les da los datos. En el original ese contrato lo daba
 * `game/kiwi/` (entityFeetY, hitboxWidth, hitboxHeight, localEyeY) sobre `GameSession`/`Entity`;
 * aqui hace lo mismo esta clase sobre [EntityTracker.TrackedEntity]:
 *
 *  - **Pies vs ojos.** `EntityTracker.selfY` puede venir en marco de ojos (PlayerAuthInput, canal
 *    de movimiento) o de pies (MovePlayer/Respawn). El flag `selfYFrameIsEye` dice cual es cual y
 *    aqui se normaliza una sola vez: [localFeetY] / [localEyeY]. Las entidades rastreadas
 *    ([EntityTracker.TrackedEntity.y]) siempre vienen en pies, igual que `Entity.posY` en Kiwi.
 *  - **Hitbox.** Los motores necesitan ancho/alto del AABB (los usan para la zona muerta y para
 *    elegir el punto de mira). Un jugador de Bedrock mide 0.6 x 1.8 (1.5 agachado); el resto de
 *    entidades reciben una caja generica, porque este cliente no guarda dimensiones por entidad.
 *  - **Velocidad por tick.** Los motores esperan el delta de posicion por tick (`Target.vel*`),
 *    no una velocidad fisica. `TrackedEntity.vel*` es el `motion` del MovePlayerPacket, que es
 *    justo eso; si un servidor no lo manda (queda a 0) se usa la diferencia de posicion contra
 *    `prev*` para no alimentar a los motores con ceros.
 *  - **Yaw de la entidad.** `TrackedEntity.yaw` (rotationYaw del cuerpo) es lo que HvHGod y
 *    FrontStrafe usaban como `rotationYaw`.
 */
object RotsSupport {

    /** Altura de ojos sobre los pies (convencion fija de este cliente, ver RotationUtil). */
    const val EYE_HEIGHT = 1.62f

    /** AABB de un jugador de Bedrock. */
    const val PLAYER_WIDTH = 0.6f
    const val PLAYER_HEIGHT = 1.8f
    const val PLAYER_SNEAK_HEIGHT = 1.5f

    /** AABB generico para entidades no jugadoras (no se guardan dimensiones por entidad). */
    const val MOB_WIDTH = 0.6f
    const val MOB_HEIGHT = 1.0f

    // ─────────────────────────── jugador local ───────────────────────────

    fun localFeetY(): Float =
        if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY - EYE_HEIGHT else EntityTracker.selfY

    fun localEyeY(): Float =
        if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY else EntityTracker.selfY + EYE_HEIGHT

    /**
     * Delta por tick del jugador local (lo que en Kiwi era `localPlayer.motionX/Y/Z`).
     *
     * `PlayerAuthInputPacket.delta` es el desplazamiento (x, y, z) desde el ultimo tick, que es
     * justo lo que se necesita. Si el servidor no lo manda se usa `motion`, que en el protocolo
     * son solo dos floats: X y Z (el vertical no viaja ahi).
     */
    fun localMotion(packet: PlayerAuthInputPacket): Triple<Float, Float, Float> {
        val d = packet.delta
        if (d != null && (d.x != 0f || d.y != 0f || d.z != 0f)) return Triple(d.x, d.y, d.z)
        val m = packet.motion ?: return Triple(0f, 0f, 0f)
        return Triple(m.x, 0f, m.y)
    }

    // ─────────────────────────── entidades ───────────────────────────

    fun entityFeetY(e: EntityTracker.TrackedEntity): Float = e.y

    fun hitboxWidth(e: EntityTracker.TrackedEntity): Float =
        if (e.isPlayer) PLAYER_WIDTH else MOB_WIDTH

    fun hitboxHeight(e: EntityTracker.TrackedEntity): Float = when {
        !e.isPlayer -> MOB_HEIGHT
        e.isSneaking -> PLAYER_SNEAK_HEIGHT
        else -> PLAYER_HEIGHT
    }

    fun entityYaw(e: EntityTracker.TrackedEntity): Float = e.yaw

    /** Delta de posicion por tick: `motion` del MovePlayer si existe, si no `pos - prev`. */
    fun motion(e: EntityTracker.TrackedEntity): Triple<Float, Float, Float> {
        val vx = e.velX
        val vy = e.velY
        val vz = e.velZ
        if (vx != 0f || vy != 0f || vz != 0f) return Triple(vx, vy, vz)
        return Triple(e.x - e.prevX, e.y - e.prevY, e.z - e.prevZ)
    }

    // ─────────────────────────── entradas del tick ───────────────────────────

    /** Banderas de teclado del ultimo PlayerAuthInputPacket (lo que Kiwi leia como `Env.key*`). */
    class Inputs(
        val w: Boolean,
        val a: Boolean,
        val s: Boolean,
        val d: Boolean,
        val space: Boolean
    )

    fun inputs(packet: PlayerAuthInputPacket) = Inputs(
        w = packet.inputData.contains(PlayerAuthInputData.UP),
        a = packet.inputData.contains(PlayerAuthInputData.LEFT),
        s = packet.inputData.contains(PlayerAuthInputData.DOWN),
        d = packet.inputData.contains(PlayerAuthInputData.RIGHT),
        space = packet.inputData.contains(PlayerAuthInputData.JUMPING)
    )
}
