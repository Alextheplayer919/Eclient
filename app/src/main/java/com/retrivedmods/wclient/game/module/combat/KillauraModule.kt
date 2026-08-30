package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import com.retrivedmods.wclient.game.utils.math.getAngleDifference
import com.retrivedmods.wclient.game.utils.math.toRotation
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin

class KillauraModule : Module("killaura", ModuleCategory.Combat) {

    // --- Existing settings ---
    private var rangeValue by floatValue("range", 7f, 2f..10f)
    private var cpsValue by intValue("cps", 20, 5..30)
    private var packets by intValue("packets", 1, 1..10)
    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var antiBot by boolValue("anti_bot", true)

    private var tpAuraEnabled by boolValue("tp_aura", false)
    private var teleportBehind by boolValue("tp_behind", false)
    private var tpSpeed by intValue("tp_speed", 100, 10..500)
    private var tpYOffset by intValue("tp_y_offset", 1, -10..10)
    private var keepDistance by floatValue("keep_distance", 1.2f, 0.5f..10f)

    private var strafe by boolValue("strafe", false)
    private val strafeSpeed by floatValue("strafe_speed", 2.5f, 1f..4f)
    private val strafeRadius by floatValue("strafe_radius", 2.5f, 1f..6f)

    // --- Rotation settings (using RotationUtils) ---
    private var rotMode by boolValue("rotation", true)
    private var rotationSpeed by floatValue("rotation_speed", 30f, 0f..30f)
    private var osuRots by boolValue("osu_rots", false)
    private var wideRots by boolValue("wide_rots", true)
    private var rotYawJitter by floatValue("rot_yaw_jitter", 0.6f, 0f..5f)
    private var rotPitchJitter by floatValue("rot_pitch_jitter", 0.3f, 0f..5f)

    // --- State ---
    private var lastAttackTime = 0L
    private var tpCooldown = 0L
    private var strafeAngle = 0f
    private var headLockYaw = 0f
    private var headLockPitch = 0f

    // --- Existing bot detection ---
    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false
        val playerListEntry = session.level.playerMap[this.uuid] ?: return true
        val name = playerListEntry.name?.toString() ?: ""
        if (name.isBlank()) return true
        val xuid = playerListEntry.xuid ?: ""
        if (xuid.isEmpty() || xuid == "0") return true
        if (name.trim().isEmpty()) return true
        return false
    }

    // --- Main packet hook ---
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (interceptablePacket.packet !is PlayerAuthInputPacket) return

        val pkt = interceptablePacket.packet as PlayerAuthInputPacket
        val now = System.currentTimeMillis()
        val delay = 1000L / cpsValue
        if (now - lastAttackTime < delay) return

        val targets = searchForTargets()
        if (targets.isEmpty()) return

        val primary = targets.firstOrNull()

        // --- Rotation using RotationUtils ---
        if (rotMode && primary != null) {
            val player = session.localPlayer
            // Use eye height for accurate aiming
            val from = player.vec3Position.add(0f, player.eyeHeight, 0f)
            val to = primary.vec3Position.add(0f, primary.eyeHeight, 0f)

            var targetRot = toRotation(from, to)
            var targetYaw = targetRot.yaw
            val targetPitch = targetRot.pitch

            // Optional Osu-style snapping
            if (osuRots) {
                val snap = 36.4f
                targetYaw = Math.round(targetYaw / snap) * snap
            }

            val speed = (rotationSpeed / 30f).coerceIn(0.02f, 1f)

            val (smoothYaw, smoothPitch) = if (wideRots) {
                smoothWithWideOvershoot(
                    headLockYaw, headLockPitch,
                    targetYaw, targetPitch,
                    speed, rotYawJitter, rotPitchJitter
                )
            } else {
                smoothBasic(headLockYaw, headLockPitch, targetYaw, targetPitch, speed)
            }

            headLockYaw = smoothYaw
            headLockPitch = smoothPitch

            // Apply rotation to packet and local player
            pkt.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            session.localPlayer.rotation = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
        }

        // --- Attack loop ---
        for (target in targets) {
            if (target is Player && FriendManager.isFriend(target.uuid)) continue

            if (tpAuraEnabled && now - tpCooldown >= tpSpeed) {
                teleportTo(target)
                tpCooldown = now
            }

            repeat(packets) {
                session.localPlayer.attack(target)
            }

            if (strafe) strafeAroundTarget(target)
        }

        lastAttackTime = now
    }

    // --- Target filtering ---
    private fun searchForTargets(): List<Entity> {
        val player = session.localPlayer
        return session.level.entityMap.values
            .filter { it.distance(player) <= rangeValue }
            .filter { it.isTarget() }
            .sortedBy { it.distance(player) }
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> {
                if (!playersOnly) return false
                if (antiBot && isBot()) return false
                true
            }
            is EntityUnknown -> mobsOnly && isMob()
            else -> false
        }
    }

    // --- Teleport (unchanged) ---
    private fun teleportTo(entity: Entity) {
        val player = session.localPlayer
        val pos = entity.vec3Position

        val yawRad = Math.toRadians(entity.vec3Rotation.y.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        val tpPos = if (teleportBehind) {
            Vector3f.from(
                pos.x + behind.x * keepDistance,
                pos.y + tpYOffset,
                pos.z + behind.z * keepDistance
            )
        } else {
            val dir = pos.sub(player.vec3Position).normalize()
            Vector3f.from(
                pos.x - dir.x * keepDistance,
                pos.y + tpYOffset,
                pos.z - dir.z * keepDistance
            )
        }

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = tpPos
                rotation = entity.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )
    }

    // --- Strafe (unchanged) ---
    private fun strafeAroundTarget(entity: Entity) {
        val pos = entity.vec3Position
        strafeAngle += strafeSpeed
        if (strafeAngle >= 360f) strafeAngle -= 360f

        val x = strafeRadius * cos(strafeAngle)
        val z = strafeRadius * sin(strafeAngle)

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                position = pos.add(x.toFloat(), 0f, z.toFloat())
                rotation = Vector3f.ZERO
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = true
                tick = session.localPlayer.tickExists
            }
        )
    }

    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }

    // --- Rotation helper functions using RotationUtils ---

    /**
     * Basic smoothing: moves toward target by a fixed factor each tick.
     */
    private fun smoothBasic(
        curYaw: Float, curPitch: Float,
        targetYaw: Float, targetPitch: Float,
        factor: Float
    ): Pair<Float, Float> {
        // getAngleDifference returns a value in -180..180
        val diff = getAngleDifference(curYaw, targetYaw)
        val newYaw = normalizeYaw(curYaw + diff * factor)
        val newPitch = (curPitch + (targetPitch - curPitch) * factor).coerceIn(-90f, 90f)
        return Pair(newYaw, newPitch)
    }

    /**
     * Wide rotation with overshoot and jitter for anti-detection.
     * - Random speed variation per tick
     * - Overshoots past the target by 1.2x–1.8x (yaw) and 1.1x–1.5x (pitch)
     * - Adds random jitter to final angles
     */
    private fun smoothWithWideOvershoot(
        curYaw: Float, curPitch: Float,
        targetYaw: Float, targetPitch: Float,
        baseFactor: Float,
        yawJitter: Float, pitchJitter: Float
    ): Pair<Float, Float> {
        // Randomize speed factor
        val speedJitter = 0.08f
        val factor = (baseFactor + (Math.random() * speedJitter * 2 - speedJitter).toFloat())
            .coerceIn(0.02f, 1f)

        // Use RotationUtils to get the shortest angle difference
        val diff = getAngleDifference(curYaw, targetYaw)

        // Overshoot yaw: 1.2x–1.8x past target
        val overshoot = 1.2f + (Math.random() * 0.6f).toFloat()
        val rawYaw = curYaw + diff * factor * overshoot

        // Overshoot pitch: 1.1x–1.5x
        val pitchOvershoot = 1.1f + (Math.random() * 0.4f).toFloat()
        val rawPitch = curPitch + (targetPitch - curPitch) * factor * pitchOvershoot

        // Add jitter (random offset)
        val jitteredYaw = rawYaw + (Math.random() * yawJitter * 4 - yawJitter * 2).toFloat()
        val jitteredPitch = rawPitch + (Math.random() * pitchJitter * 4 - pitchJitter * 2).toFloat()

        return Pair(normalizeYaw(jitteredYaw), jitteredPitch.coerceIn(-90f, 90f))
    }

    /**
     * Normalize yaw to -180..180 range.
     */
    private fun normalizeYaw(yaw: Float): Float {
        var y = yaw % 360f
        if (y > 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }
}
