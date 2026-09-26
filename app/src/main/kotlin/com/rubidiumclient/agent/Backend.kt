package com.rubidiumclient.agent

/**
 * The stable environment for writing modules (docs/MERGE_PLAN.md P1/P2).
 *
 * Modules never see offsets, addresses, component hashes or cores — only
 * semantics. When Minecraft updates, the agent inside the game changes and this
 * file does not. That is the whole point of the split:
 *
 *     agent (inside MC) -- state push (10-20 Hz) --> app (modules, GUI)
 *     app (modules, GUI) -- semantic intents -----> agent
 *
 * The app never asks for memory. It asks for meaning ("what can you see",
 * "swing at that actor") and the agent decides how to satisfy it using the
 * offsets it validated. That is also what keeps a loopback socket from becoming
 * an arbitrary-write primitive into the game for any other app on the device.
 */

/** Capabilities the agent reports. A module degrades on a missing cap, never crashes. */
enum class Cap {
    Pose,        // own position / rotation / health
    Entities,    // other actors
    Inventory,   // inventory contents (AutoTotem, HotbarSwitcher)
    Attack,      // swing at an actor id
    Rotation,    // write own yaw / pitch (aim)
    MoveInput,   // write joystick input (walking)
}

data class Pose(
    val x: Double, val y: Double, val z: Double,
    val yaw: Float, val pitch: Float,
    val onGround: Boolean, val hp: Float,
    val vx: Double = 0.0, val vy: Double = 0.0, val vz: Double = 0.0,
    val hurt: Boolean = false,
)

data class Entity(
    /** Packed entity id from the agent. Stable for the lifetime of the entity. */
    val id: Int,
    /** Raw type id as the game reports it (already masked by the agent). */
    val typeId: Int,
    val isPlayer: Boolean,
    val x: Double, val y: Double, val z: Double,
    val dist: Double,
    val hp: Float,
    val hurt: Boolean,
)

data class Item(val slot: Int, val id: String, val count: Int)

interface Backend {
    /** Which capabilities the current source can actually serve. */
    val caps: Set<Cap>

    /** Human-readable source, shown in the panel: "agent", "replay", "dormant". */
    val name: String

    /** True when state is arriving from something live (not replay, not dormant). */
    val isLive: Boolean

    fun selfPose(): Pose?
    fun entities(): List<Entity>
    fun inventory(): List<Item>

    // ---- intents: fire and forget; acks are advisory and reported in the log ----
    fun attack(entityId: Int)
    fun setRotation(yaw: Float, pitch: Float)
    fun setInput(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean)

    // ---- module control (reachable from the app's own GUI/chat) ----
    fun toggleModule(name: String, enabled: Boolean)
    fun setSetting(module: String, setting: String, value: Any)
}

// ---------------------------------------------------------------- helpers ---

/** Nearest actor passing [filter] within [range] — bot code reads better with this. */
fun List<Entity>.nearest(
    me: Pose, range: Double, filter: (Entity) -> Boolean = { true },
): Entity? = asSequence()
    .filter(filter)
    .filter { it.dist <= range }
    .minByOrNull {
        val dx = it.x - me.x; val dy = it.y - me.y; val dz = it.z - me.z
        dx * dx + dy * dy + dz * dz
    }

fun List<Entity>.nearestPlayer(me: Pose, range: Double): Entity? = nearest(me, range) { it.isPlayer }

fun List<Entity>.byId(id: Int): Entity? = firstOrNull { it.id == id }

/** Aiming maths, so modules do not each reinvent angle wrapping. */
object Aim {
    fun yawTo(me: Pose, tx: Double, tz: Double): Float {
        val dx = tx - me.x
        val dz = tz - me.z
        return Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
    }

    fun pitchTo(me: Pose, tx: Double, ty: Double, tz: Double): Float {
        val dx = tx - me.x
        val dy = ty - (me.y + 1.62)
        val dz = tz - me.z
        val horiz = Math.sqrt(dx * dx + dz * dz)
        return -Math.toDegrees(Math.atan2(dy, horiz)).toFloat()
    }

    /** Shortest-path interpolation; the module decides how fast to move. */
    fun blend(from: Float, to: Float, t: Float): Float {
        val d = ((to - from) % 360f + 540f) % 360f - 180f
        return from + d * t.coerceIn(0f, 1f)
    }
}
