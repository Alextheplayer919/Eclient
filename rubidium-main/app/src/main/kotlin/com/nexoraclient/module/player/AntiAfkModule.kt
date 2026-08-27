package com.rubidiumclient.module.player

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.PacketUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

class AntiAfkModule : BaseModule(
    name        = "AntiAFK",
    category    = ModuleCategory.PLAYER,
    description = "Sunucunun AFK kick'ini engellemek için periyodik micro-hareket yapar"
) {

    private val intervalMs   = int  ("Interval",  25000, 5000, 120000)
    private val rotateYaw    = bool ("Rotate Yaw", true)
    private val microMove    = bool ("Micro Move", false)
    private val shortcut     = bool ("Shortcut",   false)

    private var tickJob: Job? = null
    private var angle = 0f

    override fun onEnable() {
        super.onEnable()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            delay(intervalMs.value.toLong())
            if (!isEnabled) continue
            val session = PacketEventBus.currentSession ?: continue
            angle = (angle + 45f) % 360f

            val yaw   = if (rotateYaw.value) angle else EntityTracker.selfYaw
            val pitch = EntityTracker.selfPitch

            if (microMove.value) {
                val rad = Math.toRadians(yaw.toDouble())
                val dx = (-sin(rad) * 0.05).toFloat()
                val dz = ( cos(rad) * 0.05).toFloat()
                PacketUtil.sendMove(
                    session,
                    EntityTracker.selfX + dx,
                    EntityTracker.selfY,
                    EntityTracker.selfZ + dz,
                    yaw, pitch, onGround = true
                )
                PacketUtil.sendMove(
                    session,
                    EntityTracker.selfX,
                    EntityTracker.selfY,
                    EntityTracker.selfZ,
                    yaw, pitch, onGround = true
                )
            } else {
                PacketUtil.sendMoveAtSelf(session, yaw = yaw, pitch = pitch)
            }
        }
    }
}
