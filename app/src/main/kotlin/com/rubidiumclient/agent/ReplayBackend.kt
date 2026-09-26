package com.rubidiumclient.agent

import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * ReplayBackend — develop modules with Minecraft closed (the "known environment").
 *
 * Reads a JSONL file of the same `{"push":"state", ...}` lines the agent emits
 * (`docs/MODULE_SDK.md`) and plays them back at the recorded rate, optionally
 * scaled by [speed]. Modules cannot tell the difference from the live agent, so
 * logic written offline behaves the same on device.
 *
 * Recording: any live session can be dumped to that shape — the agent can log
 * its own pushes, and so can the proxy (its decoded truth lines). That is also
 * how the proxy-vs-memory parity diff in MERGE_PLAN P1 is produced.
 *
 * Intents are recorded, not executed: [attempts] collects every attack/rotation/
 * input a module fires, so a module's decisions can be asserted instead of
 * eyeballed.
 */
class ReplayBackend(
    path: String,
    private val speed: Float = 1f,
    private val loop: Boolean = false,
) : Backend {

    override val name = "replay"
    override val isLive = false

    /** A replay can serve whatever the recording contains. */
    override val caps: Set<Cap>
        get() = if (frames.isEmpty()) emptySet() else Cap.values().toSet()

    /** Every intent a module fired during playback — for tests. */
    val attempts = java.util.Collections.synchronizedList(mutableListOf<String>())

    private class Frame(val t: Long, val json: JSONObject)

    private val frames: List<Frame> = runCatching {
        File(path).readLines()
            .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } }
            .filter { it.optString("push") == "state" }
            .map { Frame(it.optLong("t", 0L), it) }
    }.getOrDefault(emptyList())

    @Volatile private var pose: Pose? = null
    @Volatile private var entityList: List<Entity> = emptyList()
    @Volatile private var inventoryList: List<Item> = emptyList()

    @Volatile var position: Int = 0; private set
    val size: Int get() = frames.size

    fun start() {
        if (frames.isEmpty()) return
        thread(name = "eclient-replay", isDaemon = true) {
            var prev = frames.first().t
            do {
                frames.forEachIndexed { i, f ->
                    position = i
                    apply(f.json)
                    val gap = ((f.t - prev).coerceIn(0L, 500L)).toDouble() / speed
                    if (gap > 0) Thread.sleep(gap.toLong())
                    prev = f.t
                }
            } while (loop)
        }
    }

    private fun apply(j: JSONObject) {
        j.optJSONObject("self")?.let {
            pose = Pose(
                x = it.optDouble("x"), y = it.optDouble("y"), z = it.optDouble("z"),
                yaw = it.optDouble("yaw").toFloat(), pitch = it.optDouble("pitch").toFloat(),
                onGround = it.optBoolean("onGround", true), hp = it.optDouble("hp").toFloat(),
                vx = it.optDouble("vx"), vy = it.optDouble("vy"), vz = it.optDouble("vz"),
                hurt = it.optBoolean("hurt", false),
            )
        }
        entityList = j.optJSONArray("entities")?.let { arr ->
            (0 until arr.length()).mapNotNull { k ->
                arr.optJSONObject(k)?.let { e ->
                    Entity(
                        id = e.optInt("id"), typeId = e.optInt("type"),
                        isPlayer = e.optBoolean("player", false),
                        x = e.optDouble("x"), y = e.optDouble("y"), z = e.optDouble("z"),
                        dist = e.optDouble("dist"), hp = e.optDouble("hp").toFloat(),
                        hurt = e.optBoolean("hurt", false),
                    )
                }
            }
        } ?: emptyList()
        inventoryList = j.optJSONArray("inventory")?.let { arr ->
            (0 until arr.length()).mapNotNull { k ->
                arr.optJSONObject(k)?.let { Item(it.optInt("slot"), it.optString("id"), it.optInt("count")) }
            }
        } ?: emptyList()
    }

    override fun selfPose() = pose
    override fun entities() = entityList
    override fun inventory() = inventoryList

    override fun attack(entityId: Int) { attempts += "attack($entityId)" }
    override fun setRotation(yaw: Float, pitch: Float) { attempts += "rot(%.1f,%.1f)".format(yaw, pitch) }
    override fun setInput(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean) {
        attempts += "input($forward,$strafe,jump=$jump,sneak=$sneak)"
    }
    override fun toggleModule(name: String, enabled: Boolean) { attempts += "toggle($name=$enabled)" }
    override fun setSetting(module: String, setting: String, value: Any) { attempts += "set($module.$setting=$value)" }
}
