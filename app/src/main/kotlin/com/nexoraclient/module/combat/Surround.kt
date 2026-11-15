package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.CollisionGuard
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Surround — seals the cells around your feet with blast-resistant blocks so an
 * end crystal detonating beside you goes off *outside* your hitbox.
 *
 * ## What it actually places
 *
 * The ring is computed from the player's real occupied footprint, not from a
 * single assumed cell. A Bedrock player box is 0.6 wide, so standing astride a
 * block edge occupies **two or four** cells; a naive "surround my block"
 * implementation leaves a hole open exactly in that case, which is the most
 * common way to die to a crystal in a hole fight. `footprint()` enumerates the
 * occupied cells and the ring is built around their union.
 *
 * `Shape` selects the ring:
 *  - **CARDINAL** (default) — the 4 cells sharing a face with your footprint.
 *    Enough to stop a crystal pop; cheapest.
 *  - **FULL_RING** — adds the 4 diagonals, sealing a 3x3 area. Needed on
 *    servers where opponents place crystals on diagonal corners.
 *
 * ## Placement path
 *
 * Reuses the exact chain the field-validated Auto Build printer uses, so
 * nothing here invents a new packet shape:
 *
 *   `PlacementUtil.prepareItemForUse`  → hotbar find / inventory move
 *   `PlacementUtil.findClickableNeighbor` → the existing block whose face we click
 *   `PlacementUtil.sendPlacementUseRaw`   → ITEM_USE (actionType 0) + the
 *                                           mandatory consumption action record
 *   `PlacementUtil.revert`                → restore the previous hotbar slot
 *
 * The face ids from `findClickableNeighbor` are the repo's canonical
 * `core/schem/BuildBlocks.Face` numbering (DOWN 0, UP 1, NORTH 2, SOUTH 3,
 * WEST 4, EAST 5) and `clickPointFor` maps each one to a point on that face,
 * matching `BuildBlocks.clickPoint`. No rotation is sent: the ITEM_USE
 * transaction carries `blockPosition`/`blockFace`/`clickPosition`, which is all
 * the server needs for a placement — the same reason AutoTorch and Auto Build
 * never rotate.
 *
 * ## Floating holes (the support stage)
 *
 * When you stand on a 1x1 pillar the ring cells have no solid neighbour at all:
 * below them is air, above them is air, and the cells beside them are air too.
 * `findClickableNeighbor` returns null and a naive surround simply does
 * nothing. With **Support floats** on (default) the module places one block
 * *under* the ring cell first — that cell does have a solid neighbour, the
 * pillar you are standing on — and the next pass clicks the new floor's top
 * face like any other hole. Two blocks per floating cell, which is exactly what
 * a manual surround costs.
 *
 * ## Why it does not spam
 *
 * `WorldBlockTracker` only learns about a placement when the server echoes an
 * UpdateBlock/SubChunk packet back, which is tens to hundreds of ms later.
 * Until then the cell still reads as air, so every placement is recorded in
 * `pending` with a TTL and treated as "already handled". Without that, a
 * 50 ms tick would fire four packets per cell per second.
 *
 * A cell whose id is `null` (chunk data never received) is reported as UNKNOWN
 * and **skipped**, never filled in blind — placing into an unloaded chunk is
 * how you end up with obsidian inside terrain.
 *
 * ## Eating
 *
 * `pauseWhileEating` is forced **false**. The BaseModule default pauses COMBAT
 * modules mid-eat because attacking cancels the bite; a placement does not
 * cancel eating, and a defensive module that switches off while you eat a
 * gapple is worse than useless. AutoTotem makes the same choice.
 *
 * ## Settings that need a re-toggle
 *
 * None. The loop runs on a fixed 50 ms base tick and gates itself on
 * `Tick ms`, so every setting here is live.
 */
class Surround : BaseModule(
    name        = "Surround",
    category    = ModuleCategory.COMBAT,
    description = "Seals the cells around your feet with blast-resistant blocks"
) {

    private enum class BlockChoice { OBSIDIAN, CRYING, ANY, CUSTOM }
    private enum class Shape       { CARDINAL, FULL_RING }
    private enum class Trigger     { ALWAYS, ON_TOGGLE, SMART }
    private enum class CellState   { FILLED, EMPTY, PENDING, UNKNOWN }

    private val blockChoice    = enum("Block", BlockChoice.OBSIDIAN)
    private val customId       = string("Custom id", "minecraft:obsidian")
    private val shape          = enum("Shape", Shape.CARDINAL)
    private val trigger        = enum("Trigger", Trigger.ALWAYS)
    private val threatRange    = float("Threat range", 12f, 4f, 24f)
    private val tickMs         = int("Tick ms", 80, 20, 2000)
    private val perTick        = int("Max per tick", 1, 1, 4)
    private val supportFloats  = bool("Support floats", true)
    private val onlyOnGround   = bool("Only on ground", true)
    private val autoDisable    = bool("Auto-disable when sealed", false)
    private val debug          = bool("Debug", false)

    /** Defensive: a placement does not cancel an eat, and a shield that drops
     *  while you are eating a gapple is not a shield. */
    override val pauseWhileEating: Boolean get() = false

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Volatile private var lastSession: RubidiumRelaySession? = null
    @Volatile private var nextRunAt: Long = 0L
    @Volatile private var oneShotDone: Boolean = false

    override fun onEnable() {
        super.onEnable()
        oneShotDone = false
        nextRunAt   = 0L
        pending.clear()
        launchTickLoop(BASE_TICK_MS) {
            val session = PacketEventBus.currentSession ?: return@launchTickLoop
            lastSession = session
            val now = System.currentTimeMillis()
            if (now < nextRunAt) return@launchTickLoop
            nextRunAt = now + tickMs.value.toLong().coerceAtLeast(1L)
            runCatching { tick(session) }.onFailure { log("tick threw: ${it.message}") }
        }
    }

    override fun onDisable() {
        lastSession = null
        pending.clear()
        super.onDisable()
    }

    // ── block choice ───────────────────────────────────────────────────────

    /**
     * Blast resistance 1200 for all four — the same tier as obsidian, so a
     * crystal cannot break them. `respawn_anchor` is deliberately NOT here: it
     * detonates when used outside the Nether, which would be a self-pop.
     */
    private val ANY_ORDER = listOf(
        "minecraft:obsidian",
        "minecraft:crying_obsidian",
        "minecraft:netherite_block",
        "minecraft:ancient_debris"
    )

    private fun candidateIds(): List<String> = when (blockChoice.value) {
        BlockChoice.OBSIDIAN -> listOf("minecraft:obsidian")
        BlockChoice.CRYING   -> listOf("minecraft:crying_obsidian")
        BlockChoice.ANY      -> ANY_ORDER
        BlockChoice.CUSTOM   -> listOf(normalizeId(customId.value))
    }

    private fun normalizeId(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "minecraft:obsidian"
        return if (t.contains(":")) t else "minecraft:$t"
    }

    private fun pickAvailableId(): String? =
        candidateIds().firstOrNull { PlacementUtil.findItemInInventory(it) != null }

    // ── cell model ─────────────────────────────────────────────────────────

    /** Cells a block can be placed INTO (air, fluids, and the plant-ish ids
     *  Bedrock replaces on placement). Anything else is left alone. */
    private val REPLACEABLE: Set<String> = setOf(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:water", "minecraft:flowing_water",
        "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:snow_layer", "minecraft:tallgrass", "minecraft:short_grass",
        "minecraft:fern", "minecraft:deadbush", "minecraft:dead_bush",
        "minecraft:reeds", "minecraft:sugar_cane", "minecraft:waterlily",
        "minecraft:lily_pad", "minecraft:seagrass", "minecraft:torch",
        "minecraft:soul_torch", "minecraft:redstone_torch"
    )

    private val CARDINAL = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
    )
    private val DIAGONAL = arrayOf(
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )

    /** Half the Bedrock player box width (0.6 / 2). */
    private val HALF_W = 0.3f

    /** Every cell the player's box actually occupies on this Y level. */
    private fun footprint(x: Float, z: Float): Set<Pair<Int, Int>> {
        val x0 = floor(x - HALF_W).toInt()
        val x1 = floor(x + HALF_W).toInt()
        val z0 = floor(z - HALF_W).toInt()
        val z1 = floor(z + HALF_W).toInt()
        val out = LinkedHashSet<Pair<Int, Int>>()
        for (bx in x0..x1) for (bz in z0..z1) out.add(bx to bz)
        return out
    }

    /** The ring around the footprint, nearest cell first. */
    private fun ringCells(py: Int, x: Float, z: Float): List<Pair<Int, Int>> {
        val feet = footprint(x, z)
        val dirs = if (shape.value == Shape.FULL_RING) CARDINAL + DIAGONAL else CARDINAL
        val seen = HashSet<Long>()
        val out  = ArrayList<Pair<Int, Int>>(8)
        for (cell in feet) {
            for (d in dirs) {
                val cx = cell.first + d[0]
                val cz = cell.second + d[1]
                if (feet.contains(cx to cz)) continue          // never inside the player
                if (!seen.add(PlacementUtil.posKey(cx, py, cz))) continue
                out.add(cx to cz)
            }
        }
        out.sortBy { c ->
            val dx = c.first + 0.5f - x
            val dz = c.second + 0.5f - z
            dx * dx + dz * dz
        }
        return out
    }

    private fun cellState(x: Int, y: Int, z: Int): CellState {
        val id = WorldBlockTracker.getBlockIdentifier(x, y, z) ?: return CellState.UNKNOWN
        if (id !in REPLACEABLE) return CellState.FILLED
        return if (isPending(x, y, z)) CellState.PENDING else CellState.EMPTY
    }

    // ── pending bookkeeping ────────────────────────────────────────────────

    private val pending = ConcurrentHashMap<Long, Long>()

    /** How long a sent placement counts as "done" before we dare retry it.
     *  Long enough for the server echo, short enough that a rejected placement
     *  gets another attempt. */
    private val PENDING_TTL_MS = 1500L

    private fun markPending(x: Int, y: Int, z: Int) {
        pending[PlacementUtil.posKey(x, y, z)] = System.currentTimeMillis()
    }

    private fun isPending(x: Int, y: Int, z: Int): Boolean {
        val key = PlacementUtil.posKey(x, y, z)
        val at  = pending[key] ?: return false
        if (System.currentTimeMillis() - at > PENDING_TTL_MS) {
            pending.remove(key)
            return false
        }
        return true
    }

    // ── main pass ──────────────────────────────────────────────────────────

    private fun tick(session: RubidiumRelaySession) {
        if (!WorldBlockTracker.hasAnyTerrainData()) return
        if (onlyOnGround.value && !EntityTracker.selfOnGround) return
        if (trigger.value == Trigger.ON_TOGGLE && oneShotDone) return
        if (trigger.value == Trigger.SMART && !threatNearby()) return

        val py    = floor(CollisionGuard.feetY()).toInt()
        val selfX = EntityTracker.selfX
        val selfZ = EntityTracker.selfZ
        val cells = ringCells(py, selfX, selfZ)
        if (cells.isEmpty()) return

        var filled = 0
        var inFlight = 0
        var unknown = 0
        val todo = ArrayList<Pair<Int, Int>>(cells.size)
        for (c in cells) {
            when (cellState(c.first, py, c.second)) {
                CellState.FILLED  -> filled++
                CellState.PENDING -> inFlight++
                CellState.UNKNOWN -> unknown++
                CellState.EMPTY   -> todo.add(c)
            }
        }

        if (todo.isEmpty()) {
            if (trigger.value == Trigger.ON_TOGGLE) oneShotDone = true
            // Sealed means every ring cell is a real block — pending/unknown do
            // not count, or the module would switch itself off mid-placement.
            if (autoDisable.value && unknown == 0 && inFlight == 0 && filled == cells.size) {
                announce("§a[Surround]§r sealed — disabled")
                setEnabled(false)
            }
            return
        }

        val id = pickAvailableId() ?: run {
            if (debug.value) log("no block in inventory (tried ${candidateIds().joinToString()})")
            return
        }

        val sink: ((String) -> Unit)? = if (debug.value) this::log else null
        // One prepare per pass: switching the hotbar per block would send a
        // MobEquipment packet for every cell and read as a macro server-side.
        val prepared = PlacementUtil.prepareItemForUse(session, id, debugSink = sink) ?: return
        var placed = 0
        try {
            for (c in todo) {
                if (placed >= perTick.value) break
                if (placeInto(session, prepared, id, c.first, py, c.second, sink)) placed++
            }
        } finally {
            PlacementUtil.revert(session, prepared)
        }
        if (trigger.value == Trigger.ON_TOGGLE) oneShotDone = true
        if (debug.value) {
            log("pass py=$py filled=$filled inflight=$inFlight unknown=$unknown placed=$placed/$id")
        }
    }

    /** A crystal counts as a threat on its own; players are filtered by the
     *  global friend list so a friend standing next to you does not keep the
     *  module awake. */
    private fun threatNearby(): Boolean {
        val r = threatRange.value
        if (EntityTracker.getCrystals(r).isNotEmpty()) return true
        return EntityTracker.getPlayers(r).any { !it.isFriendEntity }
    }

    // ── placement ──────────────────────────────────────────────────────────

    private fun placeInto(
        session:  RubidiumRelaySession,
        prepared: PlacementUtil.PreparedItem,
        id:       String,
        x:        Int,
        y:        Int,
        z:        Int,
        sink:     ((String) -> Unit)?
    ): Boolean {
        PlacementUtil.findClickableNeighbor(x, y, z)?.let { nb ->
            return send(session, prepared, id, nb.first, nb.third, x, y, z, sink)
        }
        // Nothing solid touches the cell — you are on a pillar. Put a floor
        // under it first; the next pass clicks that floor's top face.
        if (!supportFloats.value) return false
        val sy = y - 1
        if (isPending(x, sy, z)) return false
        val sup = PlacementUtil.findClickableNeighbor(x, sy, z) ?: return false
        sink?.invoke("support stage: floor under ($x,$y,$z) from (${sup.first.x},${sup.first.y},${sup.first.z})")
        return send(session, prepared, id, sup.first, sup.third, x, sy, z, sink)
    }

    private fun send(
        session:  RubidiumRelaySession,
        prepared: PlacementUtil.PreparedItem,
        id:       String,
        supportPos: Vector3i,
        face:     Int,
        tx:       Int,
        ty:       Int,
        tz:       Int,
        sink:     ((String) -> Unit)?
    ): Boolean {
        val ok = runCatching {
            PlacementUtil.sendPlacementUseRaw(
                session, prepared, supportPos, id,
                blockFace     = face,
                clickPosition = clickPointFor(face),
                debugSink     = sink
            )
        }.getOrDefault(false)
        if (ok) markPending(tx, ty, tz)
        return ok
    }

    /**
     * Block-relative click point for a face, using the repo's canonical
     * `BuildBlocks.Face` ids and the same per-face coordinates as
     * `BuildBlocks.clickPoint` (side faces use mid height — the 0.25/0.75 split
     * there exists to pick a slab half, which is meaningless for a full cube).
     */
    private fun clickPointFor(face: Int): Vector3f = when (face) {
        0    -> Vector3f.from(0.5f, 0.0f, 0.5f)   // DOWN
        2    -> Vector3f.from(0.5f, 0.5f, 0.0f)   // NORTH (-Z)
        3    -> Vector3f.from(0.5f, 0.5f, 1.0f)   // SOUTH (+Z)
        4    -> Vector3f.from(0.0f, 0.5f, 0.5f)   // WEST  (-X)
        5    -> Vector3f.from(1.0f, 0.5f, 0.5f)   // EAST  (+X)
        else -> Vector3f.from(0.5f, 1.0f, 0.5f)   // UP (and any unknown face)
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private fun announce(message: String) {
        runCatching {
            lastSession?.clientBound(TextPacket().apply {
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

    private fun log(msg: String) = DiagLog.log(TAG, msg)

    private companion object {
        const val TAG = "Surround"
        /** Base loop rate. `Tick ms` gates it, so this only sets the resolution
         *  at which setting changes are noticed. */
        const val BASE_TICK_MS = 50L
    }
}
