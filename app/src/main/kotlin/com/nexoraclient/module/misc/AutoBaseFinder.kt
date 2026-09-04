package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.BlockTracker.TrackedBlockType
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class AutoBaseFinder : BaseModule(
    name        = "AutoStashHunter",
    category    = ModuleCategory.MISC,
    description = "Belirlenen Y seviyesinde ucarak depolama bloklarini arar, bulunca otomatik /sethome atar"
) {
    enum class FlightMode { Jetpack, ElytraFly }

    private val flightMode          = enum ("Flight Mode",       FlightMode.Jetpack)
    private val targetY             = float("Target Y Level",    120f, -64f, 320f)
    private val flySpeed            = float("Fly Speed",         1.0f, 0.1f, 3f)
    private val scanRange           = float("Scan Range",        48f,  8f,   256f)
    private val detectBarrel        = bool ("Barrel",             true)
    private val detectEnderChest    = bool ("Ender Chest",        true)
    private val detectShulker       = bool ("Shulker",            true)
    private val homeCooldownMinutes = float("Home Cooldown (min)",3f,  0.5f, 30f)
    private val shortcut            = bool ("Shortcut",           false)

    companion object {
        private const val TICK_MS = 250L
        private const val Y_BAND  = 1.5f
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var glideStarted = false
    @Volatile private var homeCounter  = 1
    @Volatile private var lastHomeMs   = 0L
    private var flightYaw = 0f

    override fun onEnable() {
        super.onEnable()
        homeCounter = 1
        lastHomeMs  = 0L
        flightYaw   = EntityTracker.selfYaw
        if (flightMode.value == FlightMode.ElytraFly) startGlide()
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        if (glideStarted) stopGlide()
        super.onDisable()
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        applyFlight(session)
        scanForBases(session)
    }

    private fun applyFlight(session: RubidiumRelaySession) {
        val dy = targetY.value - EntityTracker.selfY
        val liftY = when {
            dy >  Y_BAND -> 0.6f
            dy < -Y_BAND -> -0.6f
            else         -> 0f
        }

        val yawRad = Math.toRadians(flightYaw.toDouble()).toFloat()
        val dirX = -sin(yawRad)
        val dirZ =  cos(yawRad)

        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                dirX * flySpeed.value,
                liftY,
                dirZ * flySpeed.value
            )
        })
    }

    private fun scanForBases(session: RubidiumRelaySession) {
        val now = System.currentTimeMillis()
        val cooldownMs = (homeCooldownMinutes.value * 60_000L).toLong()
        if (now - lastHomeMs < cooldownMs) return

        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ

        val found = BlockTracker.getAllInRange(cx, cy, cz, scanRange.value)
            .firstOrNull { isWantedType(it.type) } ?: return

        lastHomeMs = now
        val homeName = "base$homeCounter"
        homeCounter++

        session.serverBound(buildCommandPacket("/sethome $homeName"))
    }

    private fun isWantedType(type: TrackedBlockType): Boolean = when (type) {
        TrackedBlockType.BARREL      -> detectBarrel.value
        TrackedBlockType.ENDER_CHEST -> detectEnderChest.value
        TrackedBlockType.SHULKER_BOX -> detectShulker.value
        else -> false
    }

    private fun startGlide() {
        val session = PacketEventBus.currentSession ?: return
        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.START_GLIDE
        })
        session.clientBound(SetEntityDataPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            metadata.setFlag(EntityFlag.GLIDING, true)
        })
        glideStarted = true
    }

    private fun stopGlide() {
        val session = PacketEventBus.currentSession ?: return
        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.STOP_GLIDE
        })
        session.clientBound(SetEntityDataPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            metadata.setFlag(EntityFlag.GLIDING, false)
        })
        glideStarted = false
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
}
