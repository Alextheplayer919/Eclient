package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.combat.rots.AbysmRot
import com.rubidiumclient.module.combat.rots.GeoRot
import com.rubidiumclient.module.combat.rots.HvHGod
import com.rubidiumclient.module.combat.rots.KillauraRots
import com.rubidiumclient.module.combat.rots.PredictiveRotator
import com.rubidiumclient.module.combat.rots.RotsSupport
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RotsAura — KillAura portada de KiwiMods (`game/module/combat/KillauraModule`) con TODOS los
 * motores de rotacion de Lynx/Kiwi, sin reescribir ninguno de ellos.
 *
 * Cada tick de `PlayerAuthInputPacket` se calculan los objetivos en rango (jugadores, con filtro
 * anti-bot y de amigos), se orienta la rotacion que se envia al servidor segun "Rot Mode" y,
 * respetando el CPS, se ataca a todos los objetivos "Packets" veces cada uno.
 *
 * Modos de rotacion (todos vienen de `module/combat/rots/`, portados tal cual):
 *  - **Abysm** (`AbysmRot` + `HvHGod`): prediccion + giro con aceleracion, con multiplicador de
 *    velocidad segun el estilo de rotacion del enemigo (spinner/snap/jitter/air/strafer/locked).
 *  - **Circle / Square / Zigzag / Spiral** (`GeoRot` + `AbysmRot`): la mira recorre una figura
 *    geometrica alrededor del pecho del enemigo.
 *  - **Predictive** (`PredictiveRotator` + `PredictiveAim`): seguimiento suave con prediccion de
 *    movimiento, con memoria entre ticks y opcion de girar la camara visible.
 *  - **Kiwi**: None, Snap, Normal, Silent, Body, Ping Predict, Lead Shot, Smooth, Legit, Jitter,
 *    Strafe, Anticipate.
 *  - **Lynx**: Lynx Smooth, Nemesis, Vortex, FrontStrafe, AirHvH Pro, FrontsX, Astral, Atomic,
 *    Syntax, Cortex, Alpha (`KillauraRots`) y **HvHGod** (elige el handler de Lynx segun el
 *    estilo del enemigo).
 *
 * Adaptaciones a ESTE cliente (los motores no se tocaron):
 *  - `GameSession`/`Entity`/`Player` de Kiwi -> `EntityTracker.TrackedEntity` + `RubidiumRelaySession`.
 *    Todo el puente (pies/ojos, hitbox, motion por tick, teclas) vive en `rots/RotsSupport`.
 *  - El ataque se envia con `PacketUtil` (swing + ITEM_USE_ON_ENTITY) en vez de `localPlayer.attack`.
 *  - `Rot Mode` era un `stringValue` con lista de opciones; aqui es un `EnumSetting` con etiquetas
 *    (el sistema de ajustes de este cliente no tiene listas de texto).
 *  - Se agregaron "Silent Rotation" y "Shortcut", que son convenciones de este cliente.
 *
 * Diferencia de comportamiento (a proposito): "Players Only" apagado ahora permite atacar mobs.
 * En el original esa opcion apagaba la KillAura entera (devolvia lista de objetivos vacia).
 */
class RotsAura : BaseModule(
    name        = "RotsAura",
    category    = ModuleCategory.COMBAT,
    description = "KillAura de Kiwi/Lynx: Abysm, Geo, Predictive, Kiwi y 12 rotaciones de Lynx + HvHGod"
), PacketEventBus.PacketListener {

    override val priority: Int = 200

    private enum class RotMode(
        val label: String,
        val geoName: String? = null,
        val lynxName: String? = null
    ) {
        ABYSM("Abysm"),
        GEO_CIRCLE("Circle", geoName = "Circle"),
        GEO_SQUARE("Square", geoName = "Square"),
        GEO_ZIGZAG("Zigzag", geoName = "Zigzag"),
        GEO_SPIRAL("Spiral", geoName = "Spiral"),
        PREDICTIVE("Predictive"),

        KIWI_NONE("None"),
        KIWI_SNAP("Snap"),
        KIWI_NORMAL("Normal"),
        KIWI_SILENT("Silent"),
        KIWI_BODY("Body"),
        KIWI_PING_PREDICT("Ping Predict"),
        KIWI_LEAD_SHOT("Lead Shot"),
        KIWI_SMOOTH("Smooth"),
        KIWI_LEGIT("Legit"),
        KIWI_JITTER("Jitter"),
        KIWI_STRAFE("Strafe"),
        KIWI_ANTICIPATE("Anticipate"),

        LYNX_SMOOTH("Lynx Smooth", lynxName = "Lynx Smooth"),
        NEMESIS("Nemesis", lynxName = "Nemesis"),
        VORTEX("Vortex", lynxName = "Vortex"),
        FRONT_STRAFE("FrontStrafe", lynxName = "FrontStrafe"),
        AIR_HVH_PRO("AirHvH Pro", lynxName = "AirHvH Pro"),
        FRONTS_X("FrontsX", lynxName = "FrontsX"),
        ASTRAL("Astral", lynxName = "Astral"),
        ATOMIC("Atomic", lynxName = "Atomic"),
        SYNTAX("Syntax", lynxName = "Syntax"),
        CORTEX("Cortex", lynxName = "Cortex"),
        ALPHA("Alpha", lynxName = "Alpha"),
        HVH_GOD("HvHGod");

        val isGeo: Boolean get() = geoName != null
        val isLynx: Boolean get() = lynxName != null || this == HVH_GOD

        companion object {
            val LABELS: Map<RotMode, String> = RotMode.values().associateWith { it.label }
        }
    }

    // ── Combate ─────────────────────────────────────────────
    private val range       = float("Range",        6.0f, 1.0f, 20.0f)
    private val cps         = int  ("CPS",          20,   5,    30)
    private val packets     = int  ("Packets",      1,    1,    10)
    private val playersOnly = bool ("Players Only", true)
    private val antiBot     = bool ("Anti Bot",     true)
    private val ignoreFriends = bool("Ignore Friends", true)

    // ── Rotacion ────────────────────────────────────────────
    private val rotMode     = enum("Rot Mode", RotMode.ABYSM, RotMode.LABELS)
    private val randomize   = bool ("Randomize",    false)
    private val smoothSpeed = float("Smooth Speed", 15.0f, 1.0f, 45.0f)
    private val strafeAmp   = float("Strafe Amp",   8.0f,  0.0f, 30.0f)
    private val vertOffset  = float("Vertical Offset", 0.3f, -1.0f, 1.0f)
    private val silentRotation = bool("Silent Rotation", true)

    // Solo aplican con Rot Mode = Predictive
    private val predTime      = float("Pred Time",      2.0f,  0.0f, 8.0f)
    private val predStrength  = float("Pred Strength",  1.0f,  0.0f, 1.5f)
    private val rotSmoothing  = float("Rot Smoothing",  0.35f, 0.05f, 1.0f)
    private val rotMaxSpeed   = float("Rot Max Speed",  30.0f, 5.0f, 90.0f)
    private val rotAccel      = float("Rot Accel",      12.0f, 2.0f, 60.0f)
    // Camera Sync: ademas de la rotacion enviada al servidor, gira la camara visible del cliente.
    private val cameraSync    = bool ("Camera Sync",    true)

    private val shortcut    = bool ("Shortcut",      false)

    // ── Estado ──────────────────────────────────────────────
    private var lastAttackMs = 0L
    private var tickCounter  = 0L
    private var smoothYaw    = 0f
    private var smoothPitch  = 0f

    private val predRot = PredictiveRotator()
    private val hvhGod  = HvHGod()
    private var lynxHasRot = false
    private var lynxPitch  = 0f
    private var lynxYaw    = 0f
    private var lynxHead   = 0f

    override fun onEnable() {
        super.onEnable()
        resetRotationState()
    }

    override fun onDisable() {
        super.onDisable()
        resetRotationState()
    }

    private fun resetRotationState() {
        lastAttackMs = 0L
        tickCounter  = 0L
        smoothYaw    = 0f
        smoothPitch  = 0f
        predRot.reset()
        hvhGod.reset()
        AbysmRot.reset()
        KillauraRots.resetState()
        lynxHasRot = false
        lynxPitch  = 0f
        lynxYaw    = 0f
        lynxHead   = 0f
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val packet = event.packet as? PlayerAuthInputPacket ?: return
        val session = event.session

        tickCounter++
        val now = System.currentTimeMillis()
        val interval = 1000L / cps.value
        val targets = searchForTargets()
        val mode = rotMode.value

        if (mode == RotMode.ABYSM || mode == RotMode.HVH_GOD) {
            for (e in targets) {
                hvhGod.observe(e.runtimeId, e.yaw, e.x, RotsSupport.entityFeetY(e), e.z, packet.tick)
            }
            hvhGod.retain(targets.map { it.runtimeId })
        }

        when {
            // ── Abysm (AbysmRot + estilo del enemigo por HvHGod) ──
            mode == RotMode.ABYSM && targets.isNotEmpty() -> {
                if (predRot.isOverriding) predRot.reset()
                lynxHasRot = false
                val t = targets.first()
                val chestY = RotsSupport.entityFeetY(t) +
                             (if (t.isPlayer) RotsSupport.hitboxHeight(t) * 0.6f else 0.9f)
                hvhGod.pick(t.runtimeId, EntityTracker.selfX, EntityTracker.selfZ, packet.tick)
                val m = RotsSupport.motion(t)
                val (p, y) = AbysmRot.aim(
                    t.x, chestY, t.z,
                    m.first, m.second, m.third,
                    EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ,
                    hvhGod.lastStyle
                )
                applyRotation(packet, p, y, y)
            }

            // ── Geo (figura alrededor del pecho) ──
            mode.isGeo && targets.isNotEmpty() -> {
                if (predRot.isOverriding) predRot.reset()
                lynxHasRot = false
                val t = targets.first()
                val chestY = RotsSupport.entityFeetY(t) +
                             (if (t.isPlayer) RotsSupport.hitboxHeight(t) * 0.6f else 0.9f)
                val off = GeoRot.offset(mode.geoName!!, packet.tick)
                val (p, y) = AbysmRot.aim(
                    t.x + off.first, chestY + off.second, t.z + off.third,
                    0f, 0f, 0f,
                    EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ,
                    HvHGod.Style.UNKNOWN
                )
                applyRotation(packet, p, y, y)
            }

            // ── Predictive ──
            mode == RotMode.PREDICTIVE -> {
                // Se actualiza en cada tick, y tambien sin objetivos (vuelve suave a la camara real).
                val list = targets.toMutableList()
                if (predRot.update(session, packet, list, predictiveSettings())) {
                    applyRotation(packet, predRot.pitch, predRot.yaw, predRot.yaw)
                }
                if (list.isEmpty()) return
            }

            targets.isEmpty() -> {
                if (predRot.isOverriding) predRot.reset()
                AbysmRot.reset()
                lynxHasRot = false
                return
            }

            // ── Lynx (KillauraRots) y HvHGod ──
            mode.isLynx -> {
                if (predRot.isOverriding) predRot.reset()
                val first = targets.first()
                if (now - lastAttackMs >= interval) computeLynxRotation(mode, first, packet)
                if (lynxHasRot) applyRotation(packet, lynxPitch, lynxYaw, lynxHead)
            }

            // ── Kiwi ──
            else -> {
                if (predRot.isOverriding) predRot.reset()
                if (!applyKiwiRotation(mode, packet, targets.first())) return
            }
        }

        if (now - lastAttackMs < interval) return
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        for (target in targets) {
            repeat(packets.value) { PacketUtil.sendSwingAndAttack(session, target.runtimeId, slot) }
        }
        lastAttackMs = now
    }

    // ------------------------------------------------------------------ rotacion

    private fun applyRotation(packet: PlayerAuthInputPacket, pitch: Float, yaw: Float, head: Float) {
        packet.rotation = Vector3f.from(pitch, yaw, head)
        // Silent Rotation: solo se cambia la rotacion del paquete que va al servidor; el estado
        // global del cliente (lo que leen ESP/HUD/mira) se toca unicamente si se desactiva.
        if (!silentRotation.value) {
            EntityTracker.selfPitch = pitch
            EntityTracker.selfYaw = yaw
        }
    }

    // ------------------------------------------------------------------ Predictive

    private fun predictiveSettings() = PredictiveRotator.Settings(
        predTime     = predTime.value,
        predStrength = predStrength.value,
        smoothing    = rotSmoothing.value,
        maxSpeed     = rotMaxSpeed.value,
        accel        = rotAccel.value,
        cameraSync   = cameraSync.value,
        vertOffset   = vertOffset.value
    )

    // ------------------------------------------------------------------ Lynx (KillauraRots)

    private fun computeLynxRotation(mode: RotMode, first: EntityTracker.TrackedEntity, packet: PlayerAuthInputPacket) {
        val feetY = RotsSupport.entityFeetY(first)
        val eye = Vector3f.from(EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ)
        val chest = Vector3f.from(
            first.x,
            feetY + RotsSupport.hitboxHeight(first) * 0.55f + vertOffset.value,
            first.z
        )
        val (basePitch, baseYaw) = calcAngle(eye, chest)
        var fpitch = basePitch
        var fyaw = baseYaw
        while (fpitch > 90f) fpitch -= 180f
        while (fpitch < -90f) fpitch += 180f
        while (fyaw > 180f) fyaw -= 360f
        while (fyaw < -180f) fyaw += 360f

        val ctx = KillauraRots.Ctx(
            50f, 0.9f, 8f, 1f, 3f, 0.7f, 1.5f, 0.1f, 0.1f, 8, 20, vertOffset.value
        )
        ctx.rotPitch = fpitch
        ctx.rotYaw = fyaw
        ctx.headYaw = fyaw

        val inputs = RotsSupport.inputs(packet)
        val lpMotion = RotsSupport.localMotion(packet)
        val env = KillauraRots.Env(
            EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ,
            lpMotion.first, lpMotion.second, lpMotion.third,
            inputs.w, inputs.a, inputs.s, inputs.d, inputs.space,
            System.nanoTime() / 1e9f
        )
        val m = RotsSupport.motion(first)
        val snap = KillauraRots.Target(
            first.x, feetY, first.z,
            m.first, m.second, m.third,
            first.yaw, RotsSupport.hitboxWidth(first), RotsSupport.hitboxHeight(first)
        )

        val effective = if (mode == RotMode.HVH_GOD) {
            hvhGod.pick(first.runtimeId, EntityTracker.selfX, EntityTracker.selfZ, packet.tick)
        } else {
            mode.lynxName ?: HvHGod.DEFAULT
        }

        when (effective) {
            "Lynx Smooth"  -> KillauraRots.smooth(ctx, snap, env)
            "Nemesis"      -> KillauraRots.nemesis(ctx, snap, env)
            "Vortex"       -> KillauraRots.vortex(ctx, snap, env)
            "FrontStrafe"  -> KillauraRots.frontStrafe(ctx, snap, env, first.runtimeId)
            "AirHvH Pro"   -> KillauraRots.airHvHPro(ctx, snap, env)
            "FrontsX"      -> KillauraRots.frontsX(ctx, snap, env)
            "Astral"       -> KillauraRots.astral(ctx, snap, env)
            "Atomic"       -> KillauraRots.atomic(ctx, snap, env)
            "Syntax"       -> KillauraRots.syntax(ctx, snap, env)
            "Cortex"       -> KillauraRots.cortex(ctx, snap, env)
            "Alpha"        -> KillauraRots.alpha(ctx, snap, env)
        }
        lynxPitch = ctx.rotPitch
        lynxYaw = ctx.rotYaw
        lynxHead = ctx.headYaw
        lynxHasRot = true
    }

    // ------------------------------------------------------------------ Kiwi

    /** Devuelve false si el modo es "None" (no se toca la rotacion ni se ataca, como en Kiwi). */
    private fun applyKiwiRotation(
        mode: RotMode,
        packet: PlayerAuthInputPacket,
        first: EntityTracker.TrackedEntity
    ): Boolean {
        val eye = Vector3f.from(EntityTracker.selfX, RotsSupport.localEyeY(), EntityTracker.selfZ)
        val (pitch, yaw) = calcAngle(eye, chestPos(first))

        val outPitch: Float
        val outYaw: Float
        when (mode) {
            RotMode.KIWI_NONE -> return false
            RotMode.KIWI_SNAP, RotMode.KIWI_NORMAL, RotMode.KIWI_SILENT, RotMode.KIWI_BODY,
            RotMode.KIWI_PING_PREDICT, RotMode.KIWI_LEAD_SHOT -> {
                var p = pitch
                var y = yaw
                if (randomize.value) {
                    p += (kotlin.random.Random.nextFloat() - 0.5f) * 14.0f
                    y += (kotlin.random.Random.nextFloat() - 0.5f) * 14.0f
                }
                smoothPitch = p
                smoothYaw = y
                outPitch = p
                outYaw = y
            }
            RotMode.KIWI_JITTER -> {
                outPitch = pitch + (kotlin.random.Random.nextFloat() - 0.5f) * 14.0f
                outYaw = yaw + (kotlin.random.Random.nextFloat() - 0.5f) * 14.0f
            }
            RotMode.KIWI_SMOOTH, RotMode.KIWI_LEGIT -> {
                var d = yaw - smoothYaw
                while (d > 180f) d -= 360f
                while (d < -180f) d += 360f
                val step = smoothSpeed.value
                if (abs(d) > step) d = if (d > 0f) step else -step
                smoothYaw += d
                smoothPitch += (pitch - smoothPitch) * 0.3f
                if (mode == RotMode.KIWI_LEGIT && abs(d) < 5.0f) {
                    smoothPitch += (kotlin.random.Random.nextFloat() - 0.5f) * 3.0f
                    smoothYaw += (kotlin.random.Random.nextFloat() - 0.5f) * 3.0f
                }
                outPitch = smoothPitch
                outYaw = smoothYaw
            }
            RotMode.KIWI_STRAFE -> {
                val dx = first.x - eye.x
                val dz = first.z - eye.z
                val k = min(sqrt(dx * dx + dz * dz) / 6.0f, 1.0f)
                outYaw = sin(tickCounter * 0.35f) * strafeAmp.value * k + yaw
                outPitch = pitch + cos(tickCounter * 0.22f) * strafeAmp.value * 0.25f * k
            }
            RotMode.KIWI_ANTICIPATE -> {
                val rad = Math.toRadians(first.yaw.toDouble())
                val ahead = Vector3f.from(
                    first.x - sin(rad).toFloat() * ANTICIPATE_DEPTH,
                    chestPos(first).y,
                    first.z + cos(rad).toFloat() * ANTICIPATE_DEPTH
                )
                val (p2, y2) = calcAngle(eye, ahead)
                outPitch = p2
                outYaw = y2
            }
            else -> return false
        }
        applyRotation(packet, outPitch, outYaw, outYaw)
        return true
    }

    // ------------------------------------------------------------------ objetivos

    private fun searchForTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX
        val sy = EntityTracker.selfY
        val sz = EntityTracker.selfZ
        val out = ArrayList<EntityTracker.TrackedEntity>(8)
        for (e in EntityTracker.getAll()) {
            if (!isTarget(e)) continue
            val d2 = MathUtil.dist3sq(e.x, e.y, e.z, sx, sy, sz)
            if (d2 > range.value * range.value) continue
            out.add(e)
        }
        out.sortBy { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
        return out
    }

    private fun isTarget(e: EntityTracker.TrackedEntity): Boolean {
        if (e.runtimeId == EntityTracker.selfRuntimeId) return false
        // En Kiwi solo se apuntaba (y se atacaba) a jugadores. "Players Only" ahora tambien
        // permite mobs en vez de dejar la KillAura sin objetivos.
        if (e.isPlayer) {
            if (ignoreFriends.value && e.isFriendEntity) return false
            if (antiBot.value && isBot(e)) return false
            return true
        }
        return !playersOnly.value
    }

    // Mismo criterio anti-bot que el resto de modulos de combate de este cliente.
    private fun isBot(player: EntityTracker.TrackedEntity): Boolean =
        player.name.isBlank() || player.uniqueId == 0L

    /** (pitch, yaw) en grados desde [from] hacia [to]; misma convencion que Kiwi. */
    private fun calcAngle(from: Vector3f, to: Vector3f): Pair<Float, Float> {
        val x = (to.x - from.x).toDouble()
        val y = (to.y - from.y).toDouble()
        val z = (to.z - from.z).toDouble()
        val dist = sqrt(x * x + y * y + z * z)
        val pitch = if (dist > 0.001) (-asin(y / dist) * RAD2DEG).toFloat() else 0f
        val yaw = (-atan2(x, z) * RAD2DEG).toFloat()
        return pitch to yaw
    }

    private fun chestPos(target: EntityTracker.TrackedEntity): Vector3f = Vector3f.from(
        target.x,
        RotsSupport.entityFeetY(target) +
            (if (target.isPlayer) RotsSupport.hitboxHeight(target) * 0.5f else 0.9f),
        target.z
    )

    private companion object {
        const val RAD2DEG = 57.29577951308232
        const val ANTICIPATE_DEPTH = 3.0f
    }
}
