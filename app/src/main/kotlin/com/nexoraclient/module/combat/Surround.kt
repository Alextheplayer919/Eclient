package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.CollisionGuard
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.RotationUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

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
 *  - **CARDINAL** (default) — the four N/S/E/W cells around your footprint.
 *    This is the standard HvH pattern. The diagonal corners are left open
 *    *on purpose*: filling them wastes blocks and cages the player in, and a
 *    tight cardinal ring already denies the placements that matter.
 *  - **FULL_RING** — also seals the four diagonals. Costs four more obsidian
 *    and restricts movement; only worth it where opponents abuse corners.
 *
 * ## Center, Extend and Extra Layer
 *
 *  - **Center** — a surround is only airtight from one 1x1 cell. Off-centre,
 *    the 0.6-wide box straddles two cells, so the ring is built around both and
 *    the cell you barely overlap is left as a hole with obsidian two blocks
 *    out. Snapping to the block centre collapses the footprint back to one cell.
 *    Sent only on a pass that actually places, so it never fights your walking
 *    while the ring is sealed.
 *  - **Extend** — if an opponent is standing in one of the surround spots, the
 *    pattern steps one cell further out along that axis instead of leaving the
 *    side open.
 *  - **Extra Layer** — the reactive "double surround". Not a static second ring;
 *    it fires only when a cell that was confirmed filled reads air again while a
 *    threat is in range, i.e. someone is mining through, and puts a backup block
 *    one cell further out on that side only.
 *
 * ## Instant re-placement
 *
 * The critical case: an opponent Speed-Mines a surround block and needs the gap
 * to last long enough to place a crystal. `cellState` removes a cell from the
 * pending set the moment the tracker confirms it filled, so a mined cell reads
 * `EMPTY` immediately with nothing suppressing it and the next pass refills it.
 * The pending TTL only covers the round trip before that confirmation, and the
 * rejection case — not the re-placement case.
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
 * The `blockId` handed to `sendPlacementUseRaw` is the identifier of the block
 * being CLICKED, not the block being placed — it becomes the packet's
 * `blockDefinition`, i.e. "which existing block does this face belong to".
 * `Scaffold` and `AutoBuilder` both pass the clicked block; see `send`.
 *
 * The face ids from `findClickableNeighbor` are the repo's canonical
 * `core/schem/BuildBlocks.Face` numbering (DOWN 0, UP 1, NORTH 2, SOUTH 3,
 * WEST 4, EAST 5) and `clickPointFor` maps each one to a point on that face,
 * matching `BuildBlocks.clickPoint`.
 *
 * ## Rotation (`Rotation` setting)
 *
 * A placement needs no rotation to work — the ITEM_USE transaction carries
 * `blockPosition`/`blockFace`/`clickPosition`, which is why AutoTorch and Auto
 * Build never aim. But servers and anti-cheats that *do* validate aim want to
 * see the player looking at the face they clicked, so:
 *
 *  - **SILENT** (default) — the outgoing `PlayerAuthInputPacket`'s rotation is
 *    rewritten for the pass that places, so the server sees the aim. No extra
 *    packet, no position claim, and your screen does not move. This is exactly
 *    the mechanism `RotsAura.applyRotation` uses.
 *  - **VISIBLE** — the same rewrite, plus a `clientBound` `MovePlayerPacket`
 *    that swings your own camera to the block. Injected toward the client
 *    *only*: a MovePlayerPacket carries a feet-frame position, and re-sending
 *    one to the server would be a redundant position claim.
 *  - **NONE** — placements go out un-aimed, as before.
 *
 * Two things this deliberately does NOT use:
 *
 *  - `RotationUtil.toPoint` computes `dy = ty - (selfY + 1.62f)`, but `selfY`
 *    is already eye-frame whenever `EntityTracker.selfYFrameIsEye` is true —
 *    and EntityTracker's own note says "V3 servers feed AuthInput only", so
 *    that is the common case. Using it would double-count 1.62 and throw the
 *    pitch out by tens of degrees at one block of range. `lookRotation`
 *    derives the eye Y from `CollisionGuard.feetY()`, which is frame-correct.
 *  - `PacketUtil.sendMove` / `sendMoveAtSelf` feed `selfY` straight into a
 *    feet-frame MovePlayerPacket — the same frame bug, but this time it would
 *    tell the server we are 1.62 higher than we are.
 *
 * Because one auth-input packet carries one rotation, a rotating pass places
 * **one** block (the nearest open cell) instead of `Max per tick`. The ring is
 * at most 8 cells, so at the default 80 ms it still closes in under a second.
 *
 * ## Driver
 *
 * The pass runs from the outgoing `PlayerAuthInputPacket`, gated by `Tick ms`.
 * Driving from the packet rather than a coroutine timer is what makes the
 * ordering correct: the rotation is rewritten and the ITEM_USE is sent inside
 * the same handler, so the server never sees a placement arrive before the aim
 * that justifies it. Trade-off: no placements while the game sends no auth
 * input (paused, in a container screen) — the same trade-off RotsAura and
 * NeonAura make, and you are not in a hole fight then.
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
 * `pending` with a TTL and treated as "already handled". Without that, every
 * auth input would fire four packets per cell per second.
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
 * None. Every setting is read on the pass that uses it.
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

    /** Where the aim goes. SILENT = server only, VISIBLE = server + your screen. */
    private enum class RotMode     { SILENT, VISIBLE, NONE }

    private val blockChoice    = enum("Block", BlockChoice.OBSIDIAN)
    private val customId       = string("Custom id", "minecraft:obsidian")
    private val shape          = enum("Shape", Shape.CARDINAL)
    private val trigger        = enum("Trigger", Trigger.ALWAYS)
    private val rotationMode   = enum("Rotation", RotMode.SILENT)
    private val center         = bool("Center", true)
    private val extend         = bool("Extend", true)
    private val extraLayer     = bool("Extra Layer", false)
    private val threatRange    = float("Threat range", 12f, 4f, 24f)
    private val tickMs         = int("Tick ms", 80, 20, 2000)
    private val perTick        = int("Max per tick", 1, 1, 4)
    private val supportFloats  = bool("Support floats", true)
    private val onlyOnGround   = bool("Only on ground", true)
    private val autoDisable    = bool("Auto-disable when sealed", false)
    private val debug          = bool("Debug", false)
    /** Read by name from `ModuleManager.shortcutModules()`, not referenced here. */
    private val shortcut       = bool("Shortcut", false)

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
        confirmed.clear()
        lastAnchor  = null
    }

    override fun onDisable() {
        lastSession = null
        pending.clear()
        confirmed.clear()
        lastAnchor  = null
        super.onDisable()
    }

    /**
     * The whole module runs here — see "Driver" in the class doc. Rewriting the
     * rotation and sending the ITEM_USE from the same handler is what keeps the
     * aim ahead of the placement on the wire.
     */
    override fun onPacket(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val packet = event.packet as? PlayerAuthInputPacket ?: return
        val session = event.session
        lastSession = session

        val now = System.currentTimeMillis()
        if (now < nextRunAt) return
        nextRunAt = now + tickMs.value.toLong().coerceAtLeast(1L)
        runCatching { pass(session, packet) }.onFailure { log("pass threw: ${it.message}") }
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

    /** Standing eye height. Matches `CollisionGuard.EYE`. */
    private val EYE = 1.62f

    /** Guard against a feetY a hair below the block top (see `pass`). */
    private val Y_EPSILON = 0.001f

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
        if (id !in REPLACEABLE) {
            // The tracker now reports a real block here, so our placement is
            // CONFIRMED — drop it from the pending set. This is what makes
            // instant re-placement work: the moment an opponent mines the
            // block, the cell reads air with nothing suppressing it and the
            // next pass fills it again. Without the clear, the TTL would hold
            // a broken cell open for its whole window, and that window is
            // precisely the one an opposing AutoCrystal needs.
            pending.remove(PlacementUtil.posKey(x, y, z))
            confirmed.add(PlacementUtil.posKey(x, y, z))
            return CellState.FILLED
        }
        return if (isPending(x, y, z)) CellState.PENDING else CellState.EMPTY
    }

    // ── pending bookkeeping ────────────────────────────────────────────────

    private val pending = ConcurrentHashMap<Long, Long>()

    /**
     * Cells the tracker has confirmed as filled at least once, for the current
     * anchor cell only (cleared when we move to a different block). Drives the
     * reactive Extra Layer: a cell in here that reads air again was mined.
     */
    private val confirmed: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Anchor cell the `confirmed` set belongs to. */
    @Volatile private var lastAnchor: Pair<Int, Int>? = null

    /** How far to look for an entity standing in a surround spot (Extend). */
    private val EXTEND_SCAN = 6f

    /** Off-centre distance small enough to leave alone rather than snap. */
    private val CENTER_EPS = 0.05f

    /**
     * How long a sent placement is assumed to be in flight before we retry it.
     *
     * This only has to cover the round trip until the server echoes the block
     * back — `cellState` clears the entry the moment that happens. It is NOT
     * what limits re-placement after a block is mined: that is instant, because
     * a confirmed cell was already removed from this set. Keeping it short
     * matters for the other case (the server rejected the placement), where a
     * long window would leave the hole open.
     */
    private val PENDING_TTL_MS = 600L

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

    // ── placement plan ─────────────────────────────────────────────────────

    /** Which existing block face to click to fill a cell, and where the new
     *  block ends up. Split out from sending so the rotation can be computed
     *  and applied BEFORE the ITEM_USE goes out. */
    private data class Plan(
        val support:   Vector3i,
        /** Identifier of the block we click. This is NOT the block we place —
         *  see the note in [send]. */
        val supportId: String,
        val face:      Int,
        val tx:        Int,
        val ty:        Int,
        val tz:        Int
    )

    private fun planFor(x: Int, y: Int, z: Int, sink: ((String) -> Unit)?): Plan? {
        PlacementUtil.findClickableNeighbor(x, y, z)?.let { nb ->
            return Plan(nb.first, nb.second, nb.third, x, y, z)
        }
        // Nothing solid touches the cell — you are on a pillar. Put a floor
        // under it first; the next pass clicks that floor's top face.
        if (!supportFloats.value) return null
        val sy = y - 1
        if (isPending(x, sy, z)) return null
        val sup = PlacementUtil.findClickableNeighbor(x, sy, z) ?: return null
        sink?.invoke("support stage: floor under ($x,$y,$z) from (${sup.first.x},${sup.first.y},${sup.first.z})")
        return Plan(sup.first, sup.second, sup.third, x, sy, z)
    }

    // ── main pass ──────────────────────────────────────────────────────────

    private fun pass(session: RubidiumRelaySession, packet: PlayerAuthInputPacket) {
        if (!WorldBlockTracker.hasAnyTerrainData()) return
        if (onlyOnGround.value && !EntityTracker.selfOnGround) return
        if (trigger.value == Trigger.ON_TOGGLE && oneShotDone) return
        if (trigger.value == Trigger.SMART && !threatNearby()) return

        // A player standing exactly on a block top reports that integer as
        // feetY, and float jitter can land it a hair BELOW (64.9999) — floor()
        // then picks the block underneath, the whole ring is computed one level
        // low, the solid ground reads as "already filled", and the module
        // reports a sealed surround that is not there. Nudge up before floor().
        val py   = floor(CollisionGuard.feetY() + Y_EPSILON).toInt()
        val rawX = EntityTracker.selfX
        val rawZ = EntityTracker.selfZ

        // ── Center ──
        // A surround is only airtight from a single 1x1 cell. Off-centre, the
        // 0.6-wide box straddles two cells, `footprint` returns both, the ring
        // is built around their union, and the cell you barely overlap is left
        // as a hole with obsidian two blocks out — the "wide ring with a gap
        // next to me" shape. Snapping to the block centre collapses the
        // footprint to exactly one cell, so the ring is the tight 1x1 cardinal
        // pattern again.
        val cx = floor(rawX).toInt() + 0.5f
        val cz = floor(rawZ).toInt() + 0.5f
        val offCentre = center.value &&
            (abs(cx - rawX) > CENTER_EPS || abs(cz - rawZ) > CENTER_EPS)
        // Plan against where we are about to be, not where we were.
        val selfX = if (center.value) cx else rawX
        val selfZ = if (center.value) cz else rawZ

        // The confirmed-cell memory only means anything for one anchor cell;
        // drop it when we move to a different block.
        val anchor = floor(selfX).toInt() to floor(selfZ).toInt()
        if (anchor != lastAnchor) {
            confirmed.clear()
            lastAnchor = anchor
        }

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

        if (debug.value) {
            // Everything needed to answer "why did it place THERE" from one log
            // line: where the client thinks we are, which Y level was chosen,
            // which cells count as ours, and the ring that came out of it.
            log("geom self=(%.2f,%.2f) feetY=%.3f py=%d shape=%s feet=%s".format(
                selfX, selfZ, CollisionGuard.feetY(), py, shape.value,
                footprint(selfX, selfZ).sortedBy { it.first * 1000 + it.second }
            ))
            log("ring py=%d cells=%s".format(py, cells.map { c ->
                "${c.first},${c.second}=${cellState(c.first, py, c.second)}"
            }))
        }

        if (todo.isEmpty()) {
            if (trigger.value == Trigger.ON_TOGGLE) oneShotDone = true
            // Sealed means every ring cell is a real block — pending/unknown do
            // not count, or the module would switch itself off mid-placement.
            if (autoDisable.value && unknown == 0 && inFlight == 0 && filled == cells.size) {
                announce("§a[Surround]§r sealed — disabled")
                setEnabled(false)
            } else if (debug.value) {
                log("nothing to do: filled=$filled inflight=$inFlight unknown=$unknown")
            }
            return
        }

        // ── Extend ──
        // An opponent standing in one of the surround spots makes that cell
        // unplaceable, so push the pattern one cell further out along the same
        // axis and work around them instead of leaving the side open.
        if (extend.value) {
            val ax = floor(selfX).toInt()
            val az = floor(selfZ).toInt()
            for (c in cells) {
                if (cellState(c.first, py, c.second) != CellState.EMPTY) continue
                if (!entityInCell(c.first, py, c.second)) continue
                val outer = (c.first + (c.first - ax)) to (c.second + (c.second - az))
                if (cellState(outer.first, py, outer.second) == CellState.EMPTY &&
                    outer !in todo
                ) {
                    todo.add(outer)
                    if (debug.value) {
                        log("extend: ${c.first},${c.second} occupied -> ${outer.first},${outer.second}")
                    }
                }
            }
        }

        // ── Extra Layer — reactive double surround ──
        // Deliberately NOT a static second ring (that would waste blocks and
        // cage the player in). It fires only on the side an opponent is mining
        // through: a cell that was confirmed filled and now reads air, with a
        // threat in range, gets a backup block one cell further out so a crystal
        // still cannot be placed against you during the re-fill window.
        if (extraLayer.value && threatNearby()) {
            val ax = floor(selfX).toInt()
            val az = floor(selfZ).toInt()
            for (c in cells) {
                val key = PlacementUtil.posKey(c.first, py, c.second)
                if (!confirmed.contains(key)) continue
                if (cellState(c.first, py, c.second) != CellState.EMPTY) continue
                val outer = (c.first + (c.first - ax)) to (c.second + (c.second - az))
                if (cellState(outer.first, py, outer.second) == CellState.EMPTY &&
                    outer !in todo
                ) {
                    todo.add(outer)
                    if (debug.value) {
                        log("extra layer: ${c.first},${c.second} mined -> ${outer.first},${outer.second}")
                    }
                }
            }
        }

        // Send the centre move only once we know there is something to place —
        // snapping on every pass would fight the player's own walking even when
        // the ring is already sealed.
        if (offCentre) centerPlayer(session, cx, cz)

        val id = pickAvailableId() ?: run {
            if (debug.value) log("no block in inventory (tried ${candidateIds().joinToString()})")
            return
        }

        val sink: ((String) -> Unit)? = if (debug.value) this::log else null
        // One prepare per pass: switching the hotbar per block would send a
        // MobEquipment packet for every cell and read as a macro server-side.
        val prepared = PlacementUtil.prepareItemForUse(session, id, debugSink = sink) ?: return
        // One auth-input packet carries one rotation, so a rotating pass places
        // a single cell — the nearest open one, since `todo` is sorted.
        val cap = if (rotationMode.value == RotMode.NONE) perTick.value else 1
        var placed = 0
        try {
            for (c in todo) {
                if (placed >= cap) break
                val plan = planFor(c.first, py, c.second, sink) ?: continue
                applyRotation(session, packet, plan)
                if (send(session, prepared, plan, sink)) placed++
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

    /**
     * Any entity whose body overlaps this cell — the Extend trigger. The
     * vertical window is deliberately generous: entity Y is feet-frame for
     * anything tracked from MovePlayer, but a 1..2 block band either side of
     * the surround level covers both that and eye-frame records without
     * needing to resolve which one we got.
     */
    private fun entityInCell(x: Int, y: Int, z: Int): Boolean =
        EntityTracker.getEntitiesInRange(EXTEND_SCAN).any { e ->
            floor(e.x).toInt() == x &&
                floor(e.z).toInt() == z &&
                e.y > y - 1.0f && e.y < y + 2.0f
        }

    /**
     * Snap the player to the centre of the block they are standing in.
     *
     * Feet-frame Y on purpose. `PacketUtil.sendMoveAtSelf` would pass
     * `EntityTracker.selfY`, which is eye-frame on auth-input-only servers and
     * would claim a position 1.62 too high — see the class KDoc.
     * `mirrorToClient` is required, not cosmetic: without it the server has us
     * centred while the local physics engine keeps the old spot and pulls us
     * back, which is the rubber-band the `PacketUtil` comment warns about.
     */
    private fun centerPlayer(session: RubidiumRelaySession, cx: Float, cz: Float) {
        runCatching {
            PacketUtil.sendMove(
                session, cx, CollisionGuard.feetY(), cz,
                EntityTracker.selfYaw, EntityTracker.selfPitch,
                onGround       = EntityTracker.selfOnGround,
                teleport       = false,
                mirrorToClient = true
            )
            if (debug.value) log("centered to (%.2f,%.2f)".format(cx, cz))
        }.onFailure { log("center threw: ${it.message}") }
    }

    // ── rotation ───────────────────────────────────────────────────────────

    /**
     * Yaw/pitch from the player's eye to the exact point on the clicked face.
     *
     * Not `RotationUtil.toPoint`: that helper adds 1.62 to `selfY`
     * unconditionally, but `selfY` is already eye-frame whenever
     * `selfYFrameIsEye` is set — the normal case on servers that only feed
     * PlayerAuthInput. `CollisionGuard.feetY()` normalises the frame, so adding
     * the eye height back on top of it is correct either way.
     */
    private fun lookRotation(plan: Plan): RotationUtil.Rotation? {
        val cp = clickPointFor(plan.face)
        val tx = plan.support.x + cp.x
        val ty = plan.support.y + cp.y
        val tz = plan.support.z + cp.z

        val eyeY = CollisionGuard.feetY() + EYE
        val dx = tx - EntityTracker.selfX
        val dy = ty - eyeY
        val dz = tz - EntityTracker.selfZ
        val horiz = sqrt((dx * dx + dz * dz).toDouble())
        if (!horiz.isFinite()) return null

        val yaw   = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = Math.toDegrees(-atan2(dy.toDouble(), horiz)).toFloat()
        if (!yaw.isFinite() || !pitch.isFinite()) return null
        return RotationUtil.Rotation(RotationUtil.normalize(yaw), pitch.coerceIn(-90f, 90f))
    }

    private fun applyRotation(
        session: RubidiumRelaySession,
        packet:  PlayerAuthInputPacket,
        plan:    Plan
    ) {
        if (rotationMode.value == RotMode.NONE) return
        val rot = lookRotation(plan) ?: return

        // Server-side aim: rewrite the packet that is already on its way out.
        // Rotation only — never touch `packet.position`, which is the frame the
        // server is tracking us in.
        packet.rotation = Vector3f.from(rot.pitch, rot.yaw, rot.yaw)

        if (rotationMode.value == RotMode.VISIBLE) {
            // Same choice RotsAura makes when Silent Rotation is off: keep the
            // tracked state in step so our own HUD/ESP read the aimed rotation.
            EntityTracker.selfYaw   = rot.yaw
            EntityTracker.selfPitch = rot.pitch
            swingClientCamera(session, rot)
        }
        if (debug.value) {
            log("aim (${plan.tx},${plan.ty},${plan.tz}) face=${plan.face} " +
                "yaw=${"%.1f".format(rot.yaw)} pitch=${"%.1f".format(rot.pitch)} ${rotationMode.value}")
        }
    }

    /**
     * Turn the player's own view to the block.
     *
     * Deliberately `clientBound` ONLY. A MovePlayerPacket position is feet-frame
     * (`EntityTracker.handleMovePlayer`), and the obvious shortcut —
     * `PacketUtil.sendMoveAtSelf` — feeds it `EntityTracker.selfY`, which is
     * eye-frame on auth-input servers; that would tell the server we are 1.62
     * higher than we are. The server already has the aim from the rewritten
     * auth input, so this packet exists purely to move the local camera.
     */
    private fun swingClientCamera(session: RubidiumRelaySession, rot: RotationUtil.Rotation) {
        runCatching {
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = Vector3f.from(
                    EntityTracker.selfX, CollisionGuard.feetY(), EntityTracker.selfZ
                )
                rotation              = Vector3f.from(rot.pitch, rot.yaw, rot.yaw)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = EntityTracker.selfOnGround
                ridingRuntimeEntityId = 0L
            })
        }.onFailure { log("camera mirror threw: ${it.message}") }
    }

    // ── placement ──────────────────────────────────────────────────────────

    /**
     * Send one placement.
     *
     * `blockId` here is the block being **CLICKED** (`plan.supportId`), not the
     * block being placed — `sendPlacementUseRaw` resolves it into the packet's
     * `blockDefinition`, which tells the server which existing block the face
     * belongs to. Passing the placed item instead tells the server "I clicked
     * obsidian" while the real block under the cursor is grass, which is exactly
     * the kind of mismatch this codebase has already been bitten by once (see
     * the empty-`actions` note in `sendPlacementUseRaw`). Both working callers
     * do it this way: `Scaffold` passes `refId`, `AutoBuilder` passes
     * `WorldBlockTracker.getBlockIdentifier(support)`.
     */
    private fun send(
        session:  RubidiumRelaySession,
        prepared: PlacementUtil.PreparedItem,
        plan:     Plan,
        sink:     ((String) -> Unit)?
    ): Boolean {
        val ok = runCatching {
            PlacementUtil.sendPlacementUseRaw(
                session, prepared, plan.support, plan.supportId,
                blockFace     = plan.face,
                clickPosition = clickPointFor(plan.face),
                debugSink     = sink
            )
        }.getOrDefault(false)
        sink?.invoke(
            if (ok) "placed at (${plan.tx},${plan.ty},${plan.tz}) by clicking " +
                    "${plan.supportId} at (${plan.support.x},${plan.support.y},${plan.support.z}) face=${plan.face}"
            else "SEND REJECTED for (${plan.tx},${plan.ty},${plan.tz}) " +
                 "(clicked ${plan.supportId} face=${plan.face})"
        )
        if (ok) markPending(plan.tx, plan.ty, plan.tz)
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
    }
}
