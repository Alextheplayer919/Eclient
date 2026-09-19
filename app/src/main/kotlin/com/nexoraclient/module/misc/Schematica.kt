package com.rubidiumclient.module.misc

import com.rubidiumclient.RubidiumClientApp
import com.rubidiumclient.core.proxy.CollisionGuard
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
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
 * Schematica — proxy-edition ghost-build renderer.
 *
 * What it is: loads .mcstructure (bedrock native) or .schem (Sponge/WorldEdit)
 * from the schematics folder, anchors the build at your feet, and re-paints a
 * dotted particle ghost of the exposed shell a couple of times a second. Text
 * settings: "File" = the exact filename; leaving it blank loads the newest one.
 *
 * Why particles and not fake blocks (design rule, mirrors the NoLagback claim-
 * honesty line): SpawnParticleEffect packets go CLIENTBOUND only — the server
 * never sees them, they carry no collision, and they cannot corrupt any claim.
 * A fake-block hologram would poison the client's own collision model, turn
 * the ghost into a physics lie (walk onto a fake floor → claims floating
 * positions → flags), and fight everything NoLagback is built on. Particles
 * are zero-risk by construction.
 *
 * Files: /sdcard/Android/data/<package>/files/schematics (no permissions
 * needed), with fallbacks in Documents/Eclient/schematics and
 * Download/Eclient/schematics — first folder with files wins.
 * Re-toggle the module after editing settings or moving the anchor.
 */
class Schematica : BaseModule(
    name        = "Schematica",
    category    = ModuleCategory.MISC,
    description = "Ghost-build renderer: particle hologram of .mcstructure/.schem files (client-side visuals only)"
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
        originX = floor(EntityTracker.selfX).toInt() + nudgeX.value
        originY = floor(CollisionGuard.feetY()).toInt() + nudgeY.value
        originZ = floor(EntityTracker.selfZ).toInt() + nudgeZ.value
        loadAsync()
        launchTickLoop(respawnMs.value.toLong()) { repaint() }
    }

    override fun onDisable() {
        val ids = drawerIds
        if (ids.isNotEmpty()) {
            drawerIds = LongArray(0)
            lastSession?.clientBound(DebugDrawerBoxes.removePacket(ids))
        }
        model = null
        exposed = IntArray(0)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
            lastSession = event.session
        }
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
                            ?: throw SchematicLoader.LoadError("no .mcstructure/.schem in ${dir.absolutePath}")
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

    private fun repaint() {
        val m = model ?: return
        val session = lastSession ?: return
        val shell = exposed
        if (shell.isEmpty()) return

        val layerFilter = layer.value
        val px = EntityTracker.selfX; val pz = EntityTracker.selfZ

        // Pick the closest maxPoints cells (of the layer if set) so the ghost
        // is densest where you are actually working.
        val picked = ArrayList<Int>(minOf(maxPoints.value, shell.size))
        if (shell.size <= maxPoints.value && layerFilter == 0) {
            for (i in shell) picked.add(i)
        } else {
            val scored = ArrayList<Pair<Long, Int>>(shell.size)
            for (idx in shell) {
                val (x, y, z) = m.coordsOf(idx)
                if (layerFilter > 0 && y != layerFilter - 1) continue
                val ox = (originX + x).toFloat() - px
                val oz = (originZ + z).toFloat() - pz
                scored.add((ox * ox + oz * oz).toLong() to idx)
            }
            scored.sortBy { it.first }
            for (i in 0 until minOf(maxPoints.value, scored.size)) picked.add(scored[i].second)
        }

        val dim = EntityTracker.selfDimension
        // Wireframe is back, correctly this time: ServerScriptDebugDrawer
        // (packet 328) hand-encoded via UnknownPacket (DebugDrawerBoxes) —
        // the old debug-renderer packet 164 killed sessions, and Cloudburst's
        // DebugDrawerPacket class can't exist on Android (java.awt.Color).
        // Gated to protocol >= 975 (Bedrock 26.20+): the encoder targets that
        // chain's shared BOX wire layout exactly; older sessions fall back to
        // particles with a one-time notice (removal below is safe on old
        // clients because drawerIds only ever fills through the gated path).
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
    // place; onDisable sends explicit removals (ids only fill via the gated
    // path, so old-protocol clients never see this packet at all).
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
