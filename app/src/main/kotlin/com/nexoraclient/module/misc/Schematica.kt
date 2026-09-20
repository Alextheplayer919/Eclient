package com.rubidiumclient.module.misc

import com.rubidiumclient.RubidiumClientApp
import com.rubidiumclient.core.proxy.CollisionGuard
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.core.schem.AutoBuilder
import com.rubidiumclient.core.schem.SchematicLoader
import com.rubidiumclient.core.schem.DebugDrawerBoxes
import com.rubidiumclient.core.schem.SchematicModel
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import java.io.File
import kotlin.math.floor

private enum class RenderStyle { PARTICLES, WIREFRAME, BOTH }

private enum class Marker(val identifier: String) {
    FLAME("minecraft:basic_flame_particle"),
    BLUE_FLAME("minecraft:blue_flame_particle"),
    BALLOON("minecraft:balloon_gas_particle"),
    HEART("minecraft:heart_particle"),
    NOTE("minecraft:note_particle")
}

/**
 * Schematica — FIXED VERSION. Proxy-edition ghost-build renderer + Auto Build.
 *
 * NOT COMPILED OR TESTED (no Kotlin compiler was available when writing this).
 * Every change is tagged SC-<n>; the full explanation of each is in
 * AUTOBUILD_FIXES.md.
 *
 * What it is: loads .litematic, .schem, .schematic or .mcstructure from the
 * schematics folder, anchors the build at your feet, and re-paints a ghost of
 * the exposed shell a couple of times a second. Text settings: "File" = the
 * exact filename; leaving it blank loads the newest one.
 *
 * ANCHOR: the build's corner is the block your FEET are in when you enable the
 * module (plus the Nudge settings), extending east (+X) and south (+Z). That
 * cell is inside your own hitbox, so step OUT of the footprint after enabling
 * (or use Nudge) — Auto Build will not place a block inside you (AB-4).
 *
 * Why particles/wireframe and not fake blocks (design rule, mirrors the
 * NoLagback claim-honesty line): the ghost is CLIENTBOUND only — the server
 * never sees it, it carries no collision, and it cannot corrupt any claim.
 *
 * Auto Build (v2a): SIMPLE / AXIS / AUTO_CONNECT blocks and single slabs,
 * bottom-up, one block per tick, only within reach. See AutoBuilder.kt.
 *
 * Files: /sdcard/Android/data/<package>/files/schematics (no permissions
 * needed), with fallbacks in Documents/Eclient/schematics and
 * Download/Eclient/schematics — first folder with files wins.
 * Anchor, file and nudge are read when the module is enabled: re-toggle the
 * module after changing them. The "Auto Build" switch itself works live (SC-1).
 */
class Schematica : BaseModule(
    name        = "Schematica",
    category    = ModuleCategory.MISC,
    // SC-5: the old text said "v1" and ".mcstructure/.schem" only
    description = "Schematic ghost renderer + Auto Build v2a (orientation-free blocks + single slabs, reach 4.6) for .litematic/.schem/.schematic/.mcstructure"
) {

    private val fileName   = string("File (blank = newest)", "")
    private val style      = enum("Render Style", RenderStyle.WIREFRAME)
    private val marker     = enum("Marker Particle", Marker.BLUE_FLAME)
    private val layer      = int("Layer (0 = all)", 0, 0, 256)
    private val maxPoints  = int("Max Points", 350, 50, 1500)
    private val respawnMs  = int("Repaint ms", 600, 250, 2000)
    private val nudgeX     = int("Nudge X", 0, -64, 64)
    private val nudgeY     = int("Nudge Y", 0, -64, 64)
    private val nudgeZ     = int("Nudge Z", 0, -64, 64)

    // ── Auto Build ─────────────────────────────────────────────────────────
    // Places SIMPLE / AXIS / AUTO_CONNECT blocks and single slabs bottom-up, one
    // per tick, only within 4.6 blocks of the player. Everything else stays
    // manual until Phase 2b (see docs/AUTOBUILD.md). Circuit-breaker protected
    // and every placement is verified against the server's answer.
    private val autoBuild  = bool("Auto Build", false)
    // SC-4: was 40..500 (25 placements/s is far beyond anything a player can do and looks
    // exactly like a printer to an anticheat). 100 ms minimum, 150 ms default.
    private val abDelay    = int("Auto Build tick ms", 150, 100, 500)
    @Volatile private var autoBuilder: AutoBuilder? = null

    @Volatile private var model: SchematicModel? = null
    @Volatile private var exposed: IntArray = IntArray(0)
    @Volatile private var originX = 0; @Volatile private var originY = 0; @Volatile private var originZ = 0
    @Volatile private var lastSession: RubidiumRelaySession? = null
    @Volatile private var wireframeNoticeShown = false
    @Volatile private var drawerIds = LongArray(0)
    private val WIRE_COLOR = (0xD9 shl 24) or (0x59 shl 16) or (0xD9 shl 8) or 0xFF  // cyan, 85% alpha (AARRGGBB, old marker look)

    override fun onEnable() {
        super.onEnable()
        wireframeNoticeShown = false
        model = null
        exposed = IntArray(0)
        autoBuilder = null
        originX = floor(EntityTracker.selfX).toInt() + nudgeX.value
        originY = floor(CollisionGuard.feetY()).toInt() + nudgeY.value
        originZ = floor(EntityTracker.selfZ).toInt() + nudgeZ.value
        loadAsync()
        launchTickLoop(respawnMs.value.toLong()) { repaint() }
        // SC-1: ALWAYS run the loop; autoBuildTick() gates on the live switch, so turning
        // "Auto Build" on or off no longer needs a module re-toggle (and off really stops it).
        launchTickLoop(abDelay.value.toLong()) { autoBuildTick() }
    }

    override fun onDisable() {
        val ids = drawerIds
        if (ids.isNotEmpty()) {
            drawerIds = LongArray(0)
            lastSession?.clientBound(DebugDrawerBoxes.removePacket(ids))
        }
        model = null
        exposed = IntArray(0)
        autoBuilder?.stop()
        autoBuilder = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
            // SC-2: a new relay session (reconnect / server switch) means the old builder's
            // tracker and pending sends describe a connection that no longer exists.
            val prev = lastSession
            if (prev != null && prev !== event.session) {
                autoBuilder?.stop()
                autoBuilder = null
            }
            lastSession = event.session
        } else if (autoBuild.value) {
            // rich verification feed for Auto Build (name + block states)
            val pkt = event.packet
            if (pkt is UpdateBlockPacket) autoBuilder?.onUpdateBlock(pkt)
        }
    }

    // ── Auto Build driver ─────────────────────────────────────────────────

    private fun autoBuildTick() {
        // SC-1: switching the setting off stops the engine even though the loop keeps running
        if (!autoBuild.value) {
            autoBuilder?.stop()
            autoBuilder = null
            return
        }
        val session = lastSession ?: return
        val m = model ?: return
        // SC-6: work on a local reference so onDisable() nulling the field mid-tick can't NPE us
        var b = autoBuilder
        if (b == null) {
            b = AutoBuilder { msg -> announce(msg) }
            autoBuilder = b
            announce("§e[Schematica]§r Auto Build on — SIMPLE/AXIS/AUTO_CONNECT + single slabs, " +
                "reach 4.6, one block per ${abDelay.value} ms. Stand OUTSIDE the footprint. " +
                "Stairs/trapdoors/double slabs/torches/plants/doors stay manual (Phase 2b).")
            b.start(m, originX, originY, originZ)
        }
        b.tick(session)
    }

    // ── loading ──────────────────────────────────────────────────────────

    private fun schemDir(): File = SchematicLoader.resolveDir(RubidiumClientApp.instance)

    private fun loadAsync() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = schemDir()
                    val all = SchematicLoader.list(dir)
                    val target = when {
                        fileName.value.isBlank() -> all.firstOrNull()
                            ?: throw SchematicLoader.LoadError("no schematic files in ${dir.absolutePath}")
                        else -> File(dir, fileName.value).takeIf { it.isFile }
                            ?: all.firstOrNull { it.name.equals(fileName.value, true) }
                            ?: throw SchematicLoader.LoadError(
                                "${fileName.value} not found in ${dir.absolutePath}. " +
                                    "Available: ${all.take(6).joinToString { it.name }}"
                            )
                    }
                    SchematicLoader.load(target!!) to dir.absolutePath
                }
            }
            result.onSuccess { (m, dir) ->
                model = m
                exposed = m.exposedCells()
                announce("§b[Schematica]§r Loaded §e${m.name}§r (${m.width}x${m.height}x${m.length}, " +
                        "${m.solidCount} blocks, ${exposed.size} shell points) from $dir")
                DiagLog.log("SCHEMATICA", "loaded ${m.name} ${m.width}x${m.height}x${m.length} solid=${m.solidCount} exposed=${exposed.size}")
            }
            result.onFailure { e ->
                announce("§c[Schematica]§r ${e.message}")
                DiagLog.log("SCHEMATICA", "load failed: ${e.message}")
            }
        }
    }

    // ── rendering ────────────────────────────────────────────────────────

    // ── chat command surface (core/commands/ChatCommands.kt → .schem */.build *) ──

    @Volatile var ghostVisible: Boolean = true
        private set

    /** .schem load <file> — set the file and (re)load through the normal async path. */
    fun cmdSchemLoad(name: String?) {
        if (name != null) fileName.value = name
        loadAsync()
    }

    /** .schem toggle — flip ghost rendering without tearing the module down. */
    fun cmdSchemToggleGhost(): Boolean {
        ghostVisible = !ghostVisible
        return ghostVisible
    }

    /** .schem layer <n> — same as the Layer setting (0 = all). */
    fun cmdSchemLayer(n: Int) {
        layer.value = n.coerceIn(layer.min, layer.max)
    }

    /** .schem nudge x y z — offsets the anchor; reflows the origin from the player. */
    fun cmdSchemNudge(x: Int, y: Int, z: Int) {
        nudgeX.value = x.coerceIn(nudgeX.min, nudgeX.max)
        nudgeY.value = y.coerceIn(nudgeY.min, nudgeY.max)
        nudgeZ.value = z.coerceIn(nudgeZ.min, nudgeZ.max)
        originX = floor(EntityTracker.selfX).toInt() + nudgeX.value
        originY = floor(CollisionGuard.feetY()).toInt() + nudgeY.value
        originZ = floor(EntityTracker.selfZ).toInt() + nudgeZ.value
    }

    fun cmdBuildStart(): String {
        val m = model ?: return "no schematic loaded yet — .schem load <file> first"
        autoBuild.value = true
        return "auto build on (${m.name} ${m.width}x${m.height}x${m.length})"
    }

    fun cmdBuildStop(): String {
        autoBuild.value = false
        autoBuilder?.stop()
        return "auto build off — in-flight placements are verified to completion, the rest is abandoned"
    }

    fun cmdBuildStatus(): String {
        val m = model
        val b = autoBuilder
        return when {
            m == null    -> "no schematic loaded"
            b == null    -> "auto build ${if (autoBuild.value) "armed (starting)" else "off"} · model ${m.name} (${m.width}x${m.height}x${m.length})"
            else         -> b.statusLine()
        }
    }

    private fun repaint() {
        if (!ghostVisible) return
        val m = model ?: return
        val session = lastSession ?: return
        val shell = exposed
        if (shell.isEmpty()) return

        val layerFilter = layer.value
        val px = EntityTracker.selfX; val pz = EntityTracker.selfZ
        val ab = autoBuilder   // SC-3: hide cells the builder has already finished

        // Pick the closest maxPoints cells (of the layer if set) so the ghost
        // is densest where you are actually working.
        val picked = ArrayList<Int>(minOf(maxPoints.value, shell.size))
        if (shell.size <= maxPoints.value && layerFilter == 0) {
            for (i in shell) if (ab?.isDone(i) != true) picked.add(i)
        } else {
            val scored = ArrayList<Pair<Long, Int>>(shell.size)
            for (idx in shell) {
                if (ab?.isDone(idx) == true) continue
                val (x, y, z) = m.coordsOf(idx)
                if (layerFilter > 0 && y != layerFilter - 1) continue
                val ox = (originX + x).toFloat() - px
                val oz = (originZ + z).toFloat() - pz
                scored.add((ox * ox + oz * oz).toLong() to idx)
            }
            scored.sortBy { it.first }
            for (i in 0 until minOf(maxPoints.value, scored.size)) picked.add(scored[i].second)
        }
        // SC-3: nothing left to show (existing boxes expire on their own TTL)
        if (picked.isEmpty()) return

        val dim = EntityTracker.selfDimension
        // Wireframe: ServerScriptDebugDrawer (packet 328) hand-encoded via UnknownPacket
        // (DebugDrawerBoxes) — the old debug-renderer packet 164 killed sessions, and
        // Cloudburst's DebugDrawerPacket class can't exist on Android (java.awt.Color).
        // Gated to protocol >= 975 (Bedrock 26.20+); older sessions fall back to
        // particles with a one-time notice.
        val wantsWire = style.value != RenderStyle.PARTICLES
        val wireOk    = session.activeCodec.protocolVersion >= DebugDrawerBoxes.MIN_PROTOCOL
        if (!wantsWire || !wireOk) spawnParticles(m, picked, session, dim)
        if (wantsWire) {
            if (wireOk) drawDrawer(picked, session, dim)
            else if (!wireframeNoticeShown) {
                wireframeNoticeShown = true
                announce("Schematica: wireframe needs Minecraft 26.20+ (protocol 975+) — drawing with particles on this session instead.")
            }
        }
    }

    // Wireframe: one ServerScriptDebugDrawer packet per repaint, one box per
    // picked cell. Stable ids (1..N) make repaints update the same shapes in
    // place; onDisable sends explicit removals.
    private fun drawDrawer(picked: List<Int>, session: RubidiumRelaySession, dim: Int) {
        val model = model ?: return
        val ids    = LongArray(picked.size) { (it + 1).toLong() }
        val coords = FloatArray(picked.size * 3)
        for ((n, idx) in picked.withIndex()) {
            val (x, y, z) = model.coordsOf(idx)
            coords[n * 3]     = (originX + x).toFloat()
            coords[n * 3 + 1] = (originY + y).toFloat()
            coords[n * 3 + 2] = (originZ + z).toFloat()
        }
        drawerIds = ids
        session.clientBound(DebugDrawerBoxes.addBoxesPacket(
            ids        = ids,
            corners    = coords,
            argb       = WIRE_COLOR,
            ttlSeconds = respawnMs.value / 1000f + 0.4f,
            dimension  = dim
        ))
    }

    private fun spawnParticles(m: SchematicModel, picked: List<Int>, session: RubidiumRelaySession, dim: Int) {
        val identifier = marker.value.identifier
        for (idx in picked) {
            val (x, y, z) = m.coordsOf(idx)
            session.clientBound(SpawnParticleEffectPacket().apply {
                dimensionId = dim
                uniqueEntityId = -1
                position = Vector3f.from(
                    originX + x + 0.5f,
                    originY + y + 0.5f,
                    originZ + z + 0.5f
                )
                this.identifier = identifier
                this.molangVariablesJson = java.util.Optional.empty()
            })
        }
    }

    private fun announce(message: String) {
        lastSession?.clientBound(TextPacket().apply {
            type = TextPacket.Type.SYSTEM
            isNeedsTranslation = false
            sourceName = ""
            xuid = ""
            platformChatId = ""
            setMessage(message)
            setFilteredMessage("")
        })
    }
}
