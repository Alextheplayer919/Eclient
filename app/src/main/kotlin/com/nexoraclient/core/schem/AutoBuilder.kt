package com.rubidiumclient.core.schem

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i

/**
 * AutoBuilder — v1 "orientation-free" schematic printer for the Schematica module.
 *
 * Deliberately conservative; mirrors the safety posture of the combat auras that
 * already share [PlacementUtil] (CrystalAura / AnchorAura / …):
 *
 *   - one placement attempt per module tick (delay is user-configurable),
 *   - reach limited to [REACH] around the player eye — the PLAYER moves, the
 *     builder only places what is in range (no movement hacks / fly assist),
 *   - placement is sent through the battle-tested [PlacementUtil.sendPlacementUseRaw]
 *     ITEM_USE transaction shape (same one the combat auras use successfully),
 *   - every sent placement is verified via [WorldBlockTracker] on the next ticks;
 *     unconfirmed sends expire through [PlacementTracker]
 *     (max [PlacementTracker.MAX_ATTEMPTS] attempts),
 *   - 3 consecutive failures trip a circuit breaker that stops the engine and
 *     announces why (re-enable = toggle the module off/on),
 *   - only orientation-insensitive classes are attempted in v1:
 *   [PlaceClass.SIMPLE], [PlaceClass.AXIS] (y-axis logs/pillars only),
 *   [PlaceClass.AUTO_CONNECT] (iron bars / walls that self-connect on placement).
 *   - missing inventory: [PlacementUtil.prepareItemForUse] already knows how to
 *     move an item into the hotbar (aura-grade); if the item simply isn't there,
 *     the engine back-offs for that item instead of spamming errors.
 *
 * NOT here (by design, see docs/AUTOBUILD.md): stairs/slabs/trapdoors/torches/
 * plants (Phase 2 orientation), restocking from chests (Phase 3), autorun.
 */
class AutoBuilder(private val announce: (String) -> Unit) {

    companion object {
        private const val REACH             = 4.6f   // survival reach + small slack
        private const val SCAN_BUDGET       = 512    // max cells probed per tick
        private const val BREAKER_LIMIT     = 3      // consecutive failures before stop
        private const val IDLE_NOTICE_MS    = 5_000L
        private const val MISSING_NOTICE_MS = 8_000L
        private const val PROGRESS_EVERY    = 50
        private const val VERIFY_TIMEOUT_TICKS = 20
    }

    @Volatile private var started          = false
    @Volatile private var stopped          = false
    @Volatile private var placedCount      = 0
    @Volatile private var consecutiveFails = 0

    private var model    : SchematicModel? = null
    private var originX  = 0; private var originY = 0; private var originZ = 0
    private var order    : IntArray = IntArray(0)
    private var tracker  : PlacementTracker? = null
    private var totalTargets = 0
    private var tickCount   = 0
    private var cursor      = 0
    private var skippedUnsupported = 0
    private var lastIdleNotice  = 0L
    private var lastMissingKey  = ""
    private var lastMissingMs   = 0L
    private val announcedUnsupported = HashSet<String>()

    /** (Re)build the queue against a freshly loaded model. Call on module enable / reload. */
    fun start(model: SchematicModel, originX: Int, originY: Int, originZ: Int): Boolean {
        stop()
        this.model   = model
        this.originX = originX; this.originY = originY; this.originZ = originZ
        tracker  = PlacementTracker(model.cells.size)
        cursor   = 0; placedCount = 0; consecutiveFails = 0; skippedUnsupported = 0
        announcedUnsupported.clear()
        lastMissingKey = ""; lastMissingMs = 0L; lastIdleNotice = 0L; tickCount = 0
        stopped = false

        val classes = setOf(PlaceClass.SIMPLE, PlaceClass.AXIS, PlaceClass.AUTO_CONNECT)
        val all     = BlockMapper.buildOrder(model, model.states)
        val qs      = ArrayList<Int>(all.size)
        for (i in all) {
            val s = model.states[model.cells[i]]
            if (BlockMapper.classify(s) !in classes) continue
            if (BlockMapper.bedrockName(s) == null) {
                skippedUnsupported++
                announceOnceUnsupported(s.name)
                continue
            }
            qs.add(i)
        }
        order = qs.toIntArray()
        totalTargets = order.size
        if (totalTargets == 0) {
            announce("§7[AutoBuild]§r nothing buildable in this schematic with v1 rules " +
                "(SIMPLE/AXIS/AUTO_CONNECT only) — orientation classes come in Phase 2")
            return false
        }
        started = true
        announce("§b[AutoBuild]§r queued §e$totalTargets§r blocks (bottom-up, reach ≤$REACH)" +
            (if (skippedUnsupported > 0) ", §7$skippedUnsupported skipped: unknown ids§r" else ""))
        return true
    }

    fun stop() {
        started = false
        model   = null
        order   = IntArray(0)
        tracker = null
    }

    /** Called once per module loop — verifies pending sends, then places at most ONE block. */
    fun tick(session: RubidiumRelaySession) {
        if (!started || stopped) return
        val m = model ?: return
        val t = tracker ?: return
        tickCount++

        t.expire(tickCount, VERIFY_TIMEOUT_TICKS)     // unconfirmed sends → retry/FAIL here
        verifySent(m, t)                              // confirm/fail via world state

        val n = order.size
        if (n == 0) { finishIfDone(m); return }
        val eyeX = EntityTracker.selfX
        val eyeY = if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY else EntityTracker.selfY + 1.62f
        val eyeZ = EntityTracker.selfZ

        var visited = 0
        while (visited < n && visited < SCAN_BUDGET) {
            if (cursor >= n) cursor = 0
            val i  = order[cursor]; cursor++
            visited++
            if (t.status[i] != CellStatus.PENDING) continue

            val x = i % m.width
            val y = (i / (m.width * m.length))
            val z = (i / m.width) % m.length
            val wx = originX + x; val wy = originY + y; val wz = originZ + z
            val dx = wx + 0.5f - eyeX; val dy = wy + 0.5f - eyeY; val dz = wz + 0.5f - eyeZ
            if (dx * dx + dy * dy + dz * dz > REACH * REACH) continue

            placeOne(m, session, t, i, wx, wy, wz)
            if (!stopped) return    // exactly one attempt per tick, success or fail
        }
        idleIfNeeded(m)
    }

    // ── one placement ────────────────────────────────────────────────────────

    private fun placeOne(
        m: SchematicModel, session: RubidiumRelaySession, t: PlacementTracker,
        i: Int, wx: Int, wy: Int, wz: Int
    ) {
        val state   = m.states[m.cells[i]]
        val bedrock = BlockMapper.bedrockName(state) ?: run {
            announceOnceUnsupported(state.name); return
        }
        // fast path: already right (server/another player placed it, or re-run)
        val worldId = WorldBlockTracker.getBlockIdentifier(wx, wy, wz)
        if (worldId != null && worldId == bedrock) { t.markDone(i); placedCount++; maybeProgress(); return }

        val plan = BlockMapper.planPlacement(state, wx, wy, wz) { sx, sy, sz ->
            isSupport(WorldBlockTracker.getBlockIdentifier(sx, sy, sz))
        } ?: run {
            // no safe clickable neighbor YET — neighbors may appear as the build
            // fills bottom-up; leave PENDING and let later ticks retry. Only
            // count as a breaker failure if this persists for many ticks.
            supportMiss[i] = supportMiss[i] + 1
            if (supportMiss[i] >= 40) bumpFailure("no support at ($wx,$wy,$wz) after 40 probes")
            return
        }
        supportMiss[i] = 0

        val itemId = BlockMapper.handItem(state) ?: bedrock
        val prepared = PlacementUtil.prepareItemForUse(session, itemId) ?: run {
            val now = System.currentTimeMillis()
            if (lastMissingKey != itemId || now - lastMissingMs > MISSING_NOTICE_MS) {
                lastMissingKey = itemId; lastMissingMs = now
                announce("§6[AutoBuild]§r can't find §e$itemId§r in inventory — waiting for restock")
            }
            return     // not a failure: nothing ever hit the wire
        }

        // click position is block-relative (0..1) — the combat-aura wire shape
        val click = plan.click
        val ok = PlacementUtil.sendPlacementUseRaw(
            session, prepared,
            Vector3i.from(plan.support.first, plan.support.second, plan.support.third),
            WorldBlockTracker.getBlockIdentifier(
                plan.support.first, plan.support.second, plan.support.third) ?: "minecraft:stone",
            plan.face.id,
            Vector3f.from(click.first, click.second, click.third)
        )
        prepared.revertTo?.let { PlacementUtil.revert(session, prepared) }

        if (ok) {
            t.markSent(i, tickCount)
            consecutiveFails = 0
        } else {
            bumpFailure("send path rejected the placement ($wx,$wy,$wz)")
            // leave status PENDING so the next tick retries naturally
        }
    }

    // ── verification via world state (UpdateBlock is digested by WorldBlockTracker) ──

    private fun verifySent(m: SchematicModel, t: PlacementTracker) {
        for (i in order) {
            if (t.status[i] != CellStatus.SENT) continue
            val x = i % m.width
            val y = i / (m.width * m.length)
            val z = (i / m.width) % m.length
            val worldId = WorldBlockTracker.getBlockIdentifier(originX + x, originY + y, originZ + z) ?: continue
            if (worldId.endsWith(":air") || worldId == "minecraft:air") continue
            val bedrock = BlockMapper.bedrockName(m.states[m.cells[i]]) ?: continue
            if (worldId == bedrock) {
                t.markDone(i); placedCount++; maybeProgress()
            } else {
                // server accepted but put something else there (or an override) —
                // force this attempt through the tracker retry/FAIL path:
                // backdate sentTick so the very next expire() converts it to
                // PENDING (attempts < MAX) or FAILED (attempts exhausted).
                t.markSent(i, tickCount - VERIFY_TIMEOUT_TICKS - 1)
                bumpFailure("world holds $worldId where $bedrock was expected")
            }
        }
    }

    // ── support filter ───────────────────────────────────────────────────────

    private val supportMissHash = HashMap<Int, Int>()
    private val supportMiss = object {
        operator fun get(i: Int) = supportMissHash[i] ?: 0
        operator fun set(i: Int, v: Int) { if (v == 0) supportMissHash.remove(i) else supportMissHash[i] = v }
    }

    private fun isSupport(id: String?): Boolean {
        if (id == null) return false
        if (id.endsWith(":air")) return false
        if (id.endsWith(":water") || id.endsWith(":flowing_water") ||
            id.endsWith(":lava")  || id.endsWith(":flowing_lava")) return false
        if (id.endsWith(":fire") || id.endsWith(":soul_fire")) return false
        // plants/torches are not clickable supports (they pop on contact)
        val short = id.removePrefix("minecraft:")
        if (short.endsWith("_torch") || short == "torch" || short.endsWith("grass") ||
            short.endsWith("dandelion") || short.endsWith("poppy") || short.endsWith("fern") ||
            short.endsWith("tall_grass") || short.endsWith("carpet")) return false
        return true
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun finishIfDone(m: SchematicModel) {
        if (finished) return
        finished = true
        announce("§a[AutoBuild]§r done — §e$placedCount§r/$totalTargets§r blocks placed. " +
            "Orientation classes (stairs/slabs/trapdoors/torches/plants) remain manual until Phase 2.")
    }
    private var finished = false

    private fun idleIfNeeded(m: SchematicModel) {
        val now = System.currentTimeMillis()
        if (now - lastIdleNotice > IDLE_NOTICE_MS) {
            lastIdleNotice = now
            val remaining = order.count { tracker?.status?.get(it) != CellStatus.DONE }
            if (remaining == 0) { finishIfDone(m); return }
            announce("§7[AutoBuild]§r idle — §e$remaining§r blocks pending but none within reach " +
                "(walk the build outline; the engine places what you touch)")
        }
    }

    private fun maybeProgress() {
        if (placedCount > 0 && placedCount % PROGRESS_EVERY == 0) {
            val pct = if (totalTargets > 0) placedCount * 100 / totalTargets else 0
            announce("§b[AutoBuild]§r placed §e$placedCount§r/$totalTargets §7($pct%)§r")
        }
    }

    private fun bumpFailure(why: String) {
        consecutiveFails++
        if (consecutiveFails >= BREAKER_LIMIT && !stopped) {
            stopped = true
            announce("§c[AutoBuild]§r circuit breaker: $BREAKER_LIMIT consecutive failures ($why). " +
                "Engine stopped — toggle Schematica off/on to retry.")
        }
    }

    private fun announceOnceUnsupported(javaId: String) {
        if (announcedUnsupported.add(javaId)) {
            announce("§7[AutoBuild]§r skipping §f$javaId§r — not in BlockIdMap yet")
        }
    }
}
