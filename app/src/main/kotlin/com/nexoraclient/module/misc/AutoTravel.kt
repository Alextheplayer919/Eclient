package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.BlockTracker.TrackedBlockType
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class AutoTravel : BaseModule(
    name        = "AutoTravel",
    category    = ModuleCategory.MISC,
    description = "Belirlenen Y seviyesine cikar, hedef X/Z koordinatina ucar (Fly) veya yururek gider (Walk), varinca /sethome atar. Walk modu engelleri gercek dunya blok verisiyle onceden tarar, tikaninca zipla/yon degistir, hala ilerlemiyorsa sikisma tespitiyle otomatik kurtulur. Opsiyonel Base Hunting: hareket etmeden, gecerken depolama bloklari bulunca ayrica /sethome atar"
) {
    enum class Stage { ASCEND, TRAVEL, ARRIVED }
    enum class TravelMode { FLY, WALK }
    private enum class AheadState { CLEAR, BLOCKED_JUMPABLE, BLOCKED_WALL }

    private val travelMode    = enum ("Travel Mode",   TravelMode.FLY)
    private val targetX      = string("Target X",      "0")
    private val targetZ      = string("Target Z",      "0")
    private val targetY      = string("Target Y",      "120")
    private val flySpeed     = float ("Fly Speed",     1.0f, 0.1f,         3f)
    private val walkSpeed    = float ("Walk Speed",    0.28f, 0.05f,       1.2f)
    private val ascendSpeed  = float ("Ascend Speed",  0.9f, 0.1f,         3f)
    private val arriveRadius = float ("Arrive Radius", 2f,   0.5f,         10f)
    private val homeName     = string("Home Name",     "target")
    private val shortcut     = bool  ("Shortcut",      false)

    private val baseHunting         = bool ("Base Hunting",         false)
    private val detectBarrel        = bool ("Barrel",               true)
    private val detectEnderChest    = bool ("Ender Chest",          true)
    private val detectShulker       = bool ("Shulker",              true)
    private val huntScanRange       = float("Hunt Scan Range",      48f, 8f,   256f)
    private val huntCooldownMinutes = float("Hunt Cooldown (min)",  3f,  0.5f, 30f)

    companion object {
        private const val TICK_MS = 250L
        private const val Y_BAND  = 1.5f

        private const val PROBE_DIST          = 0.9f
        private const val JUMP_VELOCITY       = 0.42f
        private const val JUMP_COOLDOWN_MS    = 550L
        private const val STEER_STEP_DEG      = 15f
        private const val STEER_MAX_DEG       = 90f
        private const val STEER_RECOVER_DEG   = 5f
        private const val STUCK_MOVE_THRESHOLD_SQ = 0.02f * 0.02f
        private const val STUCK_TICKS_TRIGGER = 4
        private const val STUCK_TICKS_HARD    = 12
        private const val JUMP_RETRY_EVERY_TICKS = 3

        private val PASSABLE_KEYWORDS = listOf(
            "air", "water", "grass", "flower", "sapling", "fern", "torch",
            "carpet", "snow_layer", "vine", "ladder", "rail", "sign",
            "button", "lever", "pressure_plate", "redstone_wire", "web",
            "tall_grass", "seagrass", "kelp", "coral", "fire", "dead_bush",
            "structure_void", "light_block", "wall_banner", "banner"
        )
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var stage: Stage = Stage.ASCEND
    @Volatile private var huntCounter = 1
    @Volatile private var lastHuntMs  = 0L

    @Volatile private var lastWalkX = 0f
    @Volatile private var lastWalkZ = 0f
    @Volatile private var walkStuckTicks = 0
    @Volatile private var steerAngleDeg = 0f
    @Volatile private var steerDirSign = 1f
    @Volatile private var lastJumpMs = 0L

    override fun onEnable() {
        super.onEnable()
        stage = if (travelMode.value == TravelMode.WALK) Stage.TRAVEL else Stage.ASCEND
        huntCounter = 1
        lastHuntMs  = 0L
        lastWalkX = EntityTracker.selfX
        lastWalkZ = EntityTracker.selfZ
        walkStuckTicks = 0
        steerAngleDeg = 0f
        steerDirSign = 1f
        lastJumpMs = 0L
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val walking = travelMode.value == TravelMode.WALK
        when (stage) {
            Stage.ASCEND  -> if (walking) stage = Stage.TRAVEL else tickAscend(session)
            Stage.TRAVEL  -> if (walking) tickTravelWalk(session) else tickTravel(session)
            Stage.ARRIVED -> tickArrived(session)
        }
        if (baseHunting.value) scanForBases(session)
    }

    private fun parseCoord(raw: String, fallback: Float): Float {
        val cleaned = raw.trim()
            .replace('\u2212', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace(',', '.')
        return cleaned.toFloatOrNull() ?: fallback
    }

    private fun parsedTargetX(): Float = parseCoord(targetX.value, 0f)
    private fun parsedTargetZ(): Float = parseCoord(targetZ.value, 0f)
    private fun parsedTargetY(): Float = parseCoord(targetY.value, 120f)

    private fun tickAscend(session: RubidiumRelaySession) {
        val dy = parsedTargetY() - EntityTracker.selfY
        if (kotlin.math.abs(dy) <= Y_BAND) {
            stage = Stage.TRAVEL
            return
        }
        val liftY = if (dy > 0f) ascendSpeed.value else -ascendSpeed.value
        sendMotion(session, 0f, liftY, 0f)
    }

    private fun tickTravel(session: RubidiumRelaySession) {
        val dx = parsedTargetX() - EntityTracker.selfX
        val dz = parsedTargetZ() - EntityTracker.selfZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist <= arriveRadius.value) {
            stage = Stage.ARRIVED
            onArrived(session)
            return
        }

        val dirX = dx / dist
        val dirZ = dz / dist

        val dy = parsedTargetY() - EntityTracker.selfY
        val liftY = when {
            dy >  Y_BAND -> ascendSpeed.value * 0.5f
            dy < -Y_BAND -> -ascendSpeed.value * 0.5f
            else         -> 0f
        }

        sendMotion(session, dirX * flySpeed.value, liftY, dirZ * flySpeed.value)
    }

    private fun tickArrived(session: RubidiumRelaySession) {
        sendMotion(session, 0f, 0f, 0f)
    }

    private fun tickTravelWalk(session: RubidiumRelaySession) {
        val dx = parsedTargetX() - EntityTracker.selfX
        val dz = parsedTargetZ() - EntityTracker.selfZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist <= arriveRadius.value) {
            stage = Stage.ARRIVED
            onArrived(session)
            return
        }

        val movedDx = EntityTracker.selfX - lastWalkX
        val movedDz = EntityTracker.selfZ - lastWalkZ
        val movedSq = movedDx * movedDx + movedDz * movedDz
        lastWalkX = EntityTracker.selfX
        lastWalkZ = EntityTracker.selfZ

        if (movedSq < STUCK_MOVE_THRESHOLD_SQ) {
            walkStuckTicks++
        } else {
            walkStuckTicks = 0
            if (steerAngleDeg != 0f) {
                val recover = if (steerAngleDeg > 0f) -STEER_RECOVER_DEG else STEER_RECOVER_DEG
                steerAngleDeg += recover
                if (kotlin.math.abs(steerAngleDeg) < STEER_RECOVER_DEG) steerAngleDeg = 0f
            }
        }

        val baseAngle = atan2(dz.toDouble(), dx.toDouble())
        val steerRad  = Math.toRadians(steerAngleDeg.toDouble())
        val dirX = cos(baseAngle + steerRad).toFloat()
        val dirZ = sin(baseAngle + steerRad).toFloat()

        when (probeAhead(dirX, dirZ)) {
            AheadState.BLOCKED_JUMPABLE -> tryJump(session)
            AheadState.BLOCKED_WALL     -> widenSteer()
            AheadState.CLEAR            -> {}
        }

        if (walkStuckTicks >= STUCK_TICKS_TRIGGER) {
            widenSteer()
            if (walkStuckTicks % JUMP_RETRY_EVERY_TICKS == 0) tryJump(session)
            if (walkStuckTicks >= STUCK_TICKS_HARD) {
                sendMotion(session, -dirX * walkSpeed.value * 0.6f, 0f, -dirZ * walkSpeed.value * 0.6f)
                walkStuckTicks = STUCK_TICKS_TRIGGER
                return
            }
        }

        val finalDirX = cos(baseAngle + Math.toRadians(steerAngleDeg.toDouble())).toFloat()
        val finalDirZ = sin(baseAngle + Math.toRadians(steerAngleDeg.toDouble())).toFloat()
        sendMotion(session, finalDirX * walkSpeed.value, 0f, finalDirZ * walkSpeed.value)
    }

    private fun tryJump(session: RubidiumRelaySession) {
        val now = System.currentTimeMillis()
        if (now - lastJumpMs < JUMP_COOLDOWN_MS) return
        lastJumpMs = now

        val steerRad = Math.toRadians(steerAngleDeg.toDouble())
        val baseAngle = atan2(
            (parsedTargetZ() - EntityTracker.selfZ).toDouble(),
            (parsedTargetX() - EntityTracker.selfX).toDouble()
        )
        val dirX = cos(baseAngle + steerRad).toFloat()
        val dirZ = sin(baseAngle + steerRad).toFloat()

        sendMotion(session, dirX * walkSpeed.value, JUMP_VELOCITY, dirZ * walkSpeed.value)
    }

    private fun widenSteer() {
        steerAngleDeg += STEER_STEP_DEG * steerDirSign
        if (kotlin.math.abs(steerAngleDeg) >= STEER_MAX_DEG) {
            steerAngleDeg = STEER_MAX_DEG * steerDirSign
            steerDirSign = -steerDirSign
        }
    }

    private fun probeAhead(dirX: Float, dirZ: Float): AheadState {
        val px = floor((EntityTracker.selfX + dirX * PROBE_DIST).toDouble()).toInt()
        val pz = floor((EntityTracker.selfZ + dirZ * PROBE_DIST).toDouble()).toInt()
        val feetY = floor(EntityTracker.selfY.toDouble()).toInt()

        val feetBlocked = !isPassable(px, feetY, pz)
        val headBlocked = !isPassable(px, feetY + 1, pz)
        val stepClearAbove = isPassable(px, feetY + 2, pz)

        return when {
            !feetBlocked && !headBlocked -> AheadState.CLEAR
            feetBlocked && !headBlocked && stepClearAbove -> AheadState.BLOCKED_JUMPABLE
            else -> AheadState.BLOCKED_WALL
        }
    }

    private fun isPassable(x: Int, y: Int, z: Int): Boolean {
        if (!WorldBlockTracker.hasData(x, y, z)) return true
        val id = WorldBlockTracker.getBlockIdentifier(x, y, z) ?: return true
        if (id == "minecraft:air") return true
        return PASSABLE_KEYWORDS.any { id.contains(it) }
    }

    private fun onArrived(session: RubidiumRelaySession) {
        sendMotion(session, 0f, 0f, 0f)
        session.serverBound(buildCommandPacket("/sethome ${homeName.value}"))
        announceLocal(session, "§a[AutoPositionGoing] /sethome ${homeName.value} => Successful")
    }

    private fun sendMotion(session: RubidiumRelaySession, x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(x, y, z)
        })
    }

    private fun scanForBases(session: RubidiumRelaySession) {
        val now = System.currentTimeMillis()
        val cooldownMs = (huntCooldownMinutes.value * 60_000L).toLong()
        if (now - lastHuntMs < cooldownMs) return

        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ

        val found = BlockTracker.getAllInRange(cx, cy, cz, huntScanRange.value)
            .firstOrNull { isWantedHuntType(it.type) } ?: return

        lastHuntMs = now
        val huntHomeName = "base$huntCounter"
        huntCounter++

        session.serverBound(buildCommandPacket("/sethome $huntHomeName"))
        announceLocal(session, "§a[AutoPositionGoing] /sethome $huntHomeName => Successful")
    }

    private fun isWantedHuntType(type: TrackedBlockType): Boolean = when (type) {
        TrackedBlockType.BARREL      -> detectBarrel.value
        TrackedBlockType.ENDER_CHEST -> detectEnderChest.value
        TrackedBlockType.SHULKER_BOX -> detectShulker.value
        else -> false
    }

    private fun buildCommandPacket(command: String): CommandRequestPacket = CommandRequestPacket().apply {
        this.command = command
        this.commandOriginData = CommandOriginData(
            CommandOriginType.PLAYER,
            UUID.randomUUID(),
            "",
            0L
        )
        isInternal = false
    }

    private fun announceLocal(session: RubidiumRelaySession, message: String) {
        session.clientBound(TextPacket().apply {
            type               = TextPacket.Type.SYSTEM
            isNeedsTranslation = false
            sourceName         = ""
            xuid               = ""
            platformChatId     = ""
            setMessage(message)
            setFilteredMessage("")
        })
    }
}
