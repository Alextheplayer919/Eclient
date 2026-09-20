package com.rubidiumclient.core.schem

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket

/**
 * AutoBuilder — FIXED VERSION. v2a schematic printer for the Schematica module.
 *
 * NOT COMPILED OR TESTED (no Kotlin compiler was available when writing this).
 * Every change is tagged AB-<n>; the full explanation of each is in
 * AUTOBUILD_FIXES.md.
 *
 * Safety posture (unchanged in spirit, now actually enforced):
 *   - at most ONE placement per module tick; ticks that only SKIP cells cost nothing (AB-2),
 *   - reach is measured to the actual CLICK POINT, not the cell centre (AB-9),
 *   - at most [MAX_IN_FLIGHT] unconfirmed placements at once (AB-10),
 *   - the circuit breaker counts failures until a placement is CONFIRMED by the
 *     server — sending successfully no longer resets it (AB-1),
 *   - never places into a cell the player is standing in (AB-4), never tries to
 *     replace a non-replaceable block (AB-5), never clicks GUI blocks or
 *     non-full blocks as supports (AB-3, see BlockMapper.isSafeSupport),
 *   - all public entry points are @Synchronized because ticks and packets arrive
 *     on different threads (AB-6).
 *
 * Classes placed: SIMPLE, AXIS, AUTO_CONNECT, plus single slabs (half from click
 * height). Stairs/trapdoors/torches/plants/doors/etc stay by hand (Phase 2b).
 */
class AutoBuilder(private val announce: (String) -> Unit) {

    companion object {
        private const val REACH                 = 4.6f    // measured to the click point (AB-9)
        private const val SCAN_BUDGET           = 512     // max cells probed per tick
        private const val BREAKER_LIMIT         = 3       // consecutive unconfirmed failures before stop
        private const val MAX_IN_FLIGHT         = 3       // AB-10
        private const val VERIFY_TIMEOUT_TICKS  = 20
        private const val IDLE_NOTICE_MS        = 5_000L
        private const val MISSING_NOTICE_MS     = 8_000L
        private const val MISSING_COOLDOWN_MS   = 1_500L  // AB-15
        private const val OBSTRUCT_NOTICE_MS    = 5_000L
        private const val PROGRESS_EVERY        = 50
        private const val EYE_HEIGHT            = 1.62f
        private const val PLAYER_HALF_W         = 0.3f + 0.1f   // AB-4: hitbox + small safety margin
        private const val PLAYER_HEIGHT         = 1.8f + 0.1f
    }

    /** CONSUMED = this tick's placement budget is spent. SKIPPED = try the next cell. */
    private enum class Attempt { CONSUMED, SKIPPED }

    // All state below is guarded by the instance monitor (@Synchronized methods).
    private var started = false
    private var stopped = false
    private var finished = false
    private var model: SchematicModel? = null
    private var originX = 0; private var originY = 0; private var originZ = 0
    private var dimension = 0
    private var order: IntArray = IntArray(0)
    private var tracker: PlacementTracker? = null
    private var totalTargets = 0
    private var doneCount = 0
    private var obstructedCount = 0
    private var consecutiveFails = 0
    private var tickCount = 0
    private var cursor = 0
    private var skippedUnsupported = 0
    private var dimensionNoticeShown = false
    private var lastIdleNotice = 0L
    private var lastObstructNotice = 0L
    private var lastMissingKey = ""
    private var lastMissingMs = 0L
    private val missingUntil = HashMap<String, Long>()
    private val announcedUnsupported = HashSet<String>()
    /** cell → tick of last UpdateBlock that carried a block definition */
    private val richSeen = HashMap<Int, Int>()
    /** slab cells waiting for rich UpdateBlock evidence (ticks waited) */
    private val slabRichWait = HashMap<Int, Int>()

    /** (Re)build the queue against a freshly loaded model. Call on module enable / reload. */
    @Synchronized
    fun start(model: SchematicModel, originX: Int, originY: Int, originZ: Int): Boolean {
        stopInternal()
        this.model = model
        this.originX = originX; this.originY = originY; this.originZ = originZ
        dimension = EntityTracker.selfDimension          // AB-13: remember where we started
        tracker = PlacementTracker(model.cells.size)

        // AB-7: reset EVERYTHING, including the maps and flags the old start() forgot
        cursor = 0; doneCount = 0; obstructedCount = 0; consecutiveFails = 0
        skippedUnsupported = 0; tickCount = 0
        finished = false; stopped = false; dimensionNoticeShown = false
        lastIdleNotice = 0L; lastObstructNotice = 0L; lastMissingKey = ""; lastMissingMs = 0L
        announcedUnsupported.clear(); richSeen.clear(); slabRichWait.clear(); missingUntil.clear()

        val classes = setOf(PlaceClass.SIMPLE, PlaceClass.AXIS, PlaceClass.AUTO_CONNECT)
        val all = BlockMapper.buildOrder(model, model.states)
        val qs = ArrayList<Int>(all.size)
        for (i in all) {
            val s = model.states[model.cells[i]]
            // AB-8: check "unknown id" BEFORE the class filter (classify() returns SPECIAL for
            // unmapped ids, so the old order made this counter and notice dead code)
            if (BlockMapper.bedrockName(s) == null) {
                skippedUnsupported++
                announceOnceUnsupported(s.name)
                continue
            }
            val kind = BlockMapper.classify(s)
            if (kind !in classes && !(kind == PlaceClass.ORIENTED && isPlaceableSlab(s))) continue
            qs.add(i)
        }
        order = qs.toIntArray()
        totalTargets = order.size
        if (totalTargets == 0) {
            announce("§7[AutoBuild]§r nothing buildable in this schematic with the current rules " +
                "(SIMPLE/AXIS/AUTO_CONNECT + single slabs)")
            return false
        }
        started = true
        announce("§b[AutoBuild]§r queued §e$totalTargets§r blocks (bottom-up, reach ≤$REACH)" +
            (if (skippedUnsupported > 0) ", §7$skippedUnsupported skipped: unknown ids§r" else ""))
        return true
    }

    @Synchronized
    fun stop() = stopInternal()

    /** One-liner for the `.build status` chat command (core/commands/ChatCommands). */
    @Synchronized
    fun statusLine(): String {
        if (!started) return "not started"
        if (finished) return "finished — $doneCount/$totalTargets placed"
        val t = tracker
        val pending  = if (t != null) countOrder(t, CellStatus.PENDING) else 0
        val failed   = if (t != null) countOrder(t, CellStatus.FAILED) else 0
        val inflight = t?.inFlight ?: 0
        return "placed §a$doneCount§r/$totalTargets · pending §e$pending§r · in-flight §b$inflight§r · failed §6$failed§r · breaker $consecutiveFails/$BREAKER_LIMIT"
    }

    private fun stopInternal() {
        started = false
        model = null
        order = IntArray(0)
        tracker = null
    }

    /** AB-3/UI: lets the ghost renderer hide blocks that are already built. */
    @Synchronized
    fun isDone(i: Int): Boolean = tracker?.status?.getOrNull(i) == CellStatus.DONE

    /** Called once per module loop — verifies pending sends, then places at most ONE block. */
    @Synchronized
    fun tick(session: RubidiumRelaySession) {
        if (!started || stopped) return
        val m = model ?: return
        val t = tracker ?: return
        tickCount++

        // AB-10: a send the server never answered IS a failure; count it.
        val timedOut = t.expire(tickCount, VERIFY_TIMEOUT_TICKS)
        for (k in 0 until timedOut) { bumpFailure("server never confirmed a placement"); if (stopped) return }
        verifySent(m, t)
        if (stopped) return

        // AB-13: never place into another dimension that happens to share coordinates
        if (EntityTracker.selfDimension != dimension) {
            if (!dimensionNoticeShown) {
                dimensionNoticeShown = true
                announce("§7[AutoBuild]§r paused — you are in a different dimension than the build")
            }
            return
        }
        dimensionNoticeShown = false

        val n = order.size
        if (n == 0) { finishIfDone(t); return }
        if (t.inFlight >= MAX_IN_FLIGHT) return          // AB-10: wait for confirmations

        val eyeX = EntityTracker.selfX.toFloat()
        val eyeY = if (EntityTracker.selfYFrameIsEye) EntityTracker.selfY.toFloat()
                   else EntityTracker.selfY.toFloat() + EYE_HEIGHT
        val eyeZ = EntityTracker.selfZ.toFloat()
        val cull = (REACH + 1.5f) * (REACH + 1.5f)       // cheap prefilter on the cell centre

        var steps = 0
        while (steps < n && steps < SCAN_BUDGET) {
            steps++
            if (cursor >= n) cursor = 0
            val i = order[cursor]; cursor++
            if (t.status[i] != CellStatus.PENDING) continue

            val x = i % m.width
            val y = i / (m.width * m.length)
            val z = (i / m.width) % m.length
            val wx = originX + x; val wy = originY + y; val wz = originZ + z
            val dx = wx + 0.5f - eyeX; val dy = wy + 0.5f - eyeY; val dz = wz + 0.5f - eyeZ
            if (dx * dx + dy * dy + dz * dz > cull) continue

            // AB-2: only a real send (or a failure) ends the tick; skips keep scanning
            if (tryPlace(m, session, t, i, wx, wy, wz, eyeX, eyeY, eyeZ) == Attempt.CONSUMED) return
            if (stopped) return
        }
        idleIfNeeded(t)
    }

    // ── one placement ────────────────────────────────────────────────────────

    private fun tryPlace(
        m: SchematicModel, session: RubidiumRelaySession, t: PlacementTracker,
        i: Int, wx: Int, wy: Int, wz: Int, eyeX: Float, eyeY: Float, eyeZ: Float
    ): Attempt {
        val state = m.states[m.cells[i]]
        val bedrock = BlockMapper.bedrockName(state)
        if (bedrock == null) {
            announceOnceUnsupported(state.name); t.markFailed(i); return Attempt.SKIPPED
        }

        val worldId = WorldBlockTracker.getBlockIdentifier(wx, wy, wz)

        // fast path: already right (server/another player placed it, or re-run).
        // For slabs, name-only match is NOT proof — half is invisible here; a
        // repeat click would compound into a double slab. Requires rich evidence.
        if (worldId != null && worldId == bedrock) {
            if (isPlaceableSlab(state) && richSeen[i] == null) {
                if (announcedUnsupported.add("slab-present@$i")) {
                    announce("§7[AutoBuild]§r slab already occupies ($wx,$wy,$wz) — half unknown, " +
                        "marked by-hand (verify it matches the schematic)")
                }
                t.markFailed(i)
                return Attempt.SKIPPED
            }
            t.markDone(i); doneCount++; maybeProgress()
            return Attempt.SKIPPED
        }

        // AB-5: something ELSE non-replaceable is already there (terrain, another block).
        // Retrying would only trip the circuit breaker, so park the cell and say so.
        if (worldId != null && !BlockMapper.isReplaceableId(worldId)) {
            t.markFailed(i)
            obstructedCount++
            val now = System.currentTimeMillis()
            if (now - lastObstructNotice > OBSTRUCT_NOTICE_MS) {
                lastObstructNotice = now
                announce("§6[AutoBuild]§r ($wx,$wy,$wz) already holds §f$worldId§r — skipped " +
                    "($obstructedCount occupied so far)")
            }
            return Attempt.SKIPPED
        }

        // AB-4: a block cannot be placed inside the player's own hitbox. Wait until they step away.
        if (intersectsPlayer(wx, wy, wz, eyeX, eyeY, eyeZ)) return Attempt.SKIPPED

        // AB-3/AB-14: only safe supports; no support yet is a WAIT, not a failure
        val plan = BlockMapper.planPlacement(state, wx, wy, wz) { sx, sy, sz ->
            BlockMapper.isSafeSupport(WorldBlockTracker.getBlockIdentifier(sx, sy, sz))
        } ?: return Attempt.SKIPPED

        // AB-9: reach applies to the point we actually click
        val cx = plan.support.first + plan.click.first
        val cy = plan.support.second + plan.click.second
        val cz = plan.support.third + plan.click.third
        val rx = cx - eyeX; val ry = cy - eyeY; val rz = cz - eyeZ
        if (rx * rx + ry * ry + rz * rz > REACH * REACH) return Attempt.SKIPPED

        // AB-15: don't re-search the inventory every tick for an item we just failed to find
        val itemId = BlockMapper.handItem(state) ?: bedrock
        val now = System.currentTimeMillis()
        if ((missingUntil[itemId] ?: 0L) > now) return Attempt.SKIPPED
        val prepared = PlacementUtil.prepareItemForUse(session, itemId)
        if (prepared == null) {
            missingUntil[itemId] = now + MISSING_COOLDOWN_MS
            if (lastMissingKey != itemId || now - lastMissingMs > MISSING_NOTICE_MS) {
                lastMissingKey = itemId; lastMissingMs = now
                announce("§6[AutoBuild]§r can't find §e$itemId§r in inventory — waiting for restock")
            }
            return Attempt.SKIPPED      // not a failure: nothing ever hit the wire
        }

        val ok = PlacementUtil.sendPlacementUseRaw(
            session, prepared,
            Vector3i.from(plan.support.first, plan.support.second, plan.support.third),
            WorldBlockTracker.getBlockIdentifier(
                plan.support.first, plan.support.second, plan.support.third) ?: "minecraft:stone",
            plan.face.id,
            Vector3f.from(plan.click.first, plan.click.second, plan.click.third)
        )
        prepared.revertTo?.let { PlacementUtil.revert(session, prepared) }

        if (ok) {
            // AB-1: do NOT reset consecutiveFails here. Only a server-confirmed
            // placement proves the last send worked (see confirm()).
            t.markSent(i, tickCount)
        } else {
            bumpFailure("send path rejected the placement ($wx,$wy,$wz)")
        }
        return Attempt.CONSUMED
    }

    /** A single slab we can place without yaw assist. `type=double` excluded (by-hand). */
    private fun isPlaceableSlab(s: JavaState): Boolean =
        s.id.endsWith("_slab") && s.props["type"] != "double"

    /** AB-4: does the player's box overlap the block cell? (eyeY − eye height = feet) */
    private fun intersectsPlayer(wx: Int, wy: Int, wz: Int, px: Float, eyeY: Float, pz: Float): Boolean {
        val feet = eyeY - EYE_HEIGHT
        return px + PLAYER_HALF_W > wx && px - PLAYER_HALF_W < wx + 1 &&
            pz + PLAYER_HALF_W > wz && pz - PLAYER_HALF_W < wz + 1 &&
            feet + PLAYER_HEIGHT > wy && feet < wy + 1
    }

    // ── confirmation ─────────────────────────────────────────────────────────

    /** AB-1: the ONLY place the circuit breaker's counter is reset. */
    private fun confirm(t: PlacementTracker, i: Int) {
        t.markDone(i)
        doneCount++
        consecutiveFails = 0
        maybeProgress()
    }

    // ── rich verification: UpdateBlockPacket definition capture ──────────────

    /**
     * Forwarded from the module's onPacket (server→client). Confirms SENT cells
     * by name **and** block states (this is how a wrong-half slab gets caught).
     */
    @Synchronized
    fun onUpdateBlock(p: UpdateBlockPacket) {
        if (!started || stopped) return
        val m = model ?: return
        val t = tracker ?: return
        // AB-11: layer 1 is the liquid/waterlog layer; reading it as the block gives false mismatches.
        // (If your Cloudburst build names this field differently, adjust here.)
        if (p.dataLayer != 0) return
        val pos = p.blockPosition ?: return
        val def = p.definition ?: return
        val lx = pos.x - originX; val ly = pos.y - originY; val lz = pos.z - originZ
        if (lx !in 0 until m.width || ly !in 0 until m.height || lz !in 0 until m.length) return
        val i = lx + m.width * (lz + m.length * ly)
        richSeen[i] = tickCount
        if (t.status[i] != CellStatus.SENT) return

        val actual = bedrockBlockOf(def) ?: return
        val expected = m.states[m.cells[i]]
        if (BlockMapper.matches(expected, actual)) {
            confirm(t, i)
        } else if (BlockMapper.classify(expected) == PlaceClass.ORIENTED) {
            // a wrongly-halfed slab: placing again would compound into a
            // double slab — fail the cell immediately and let the player fix it
            t.markFailed(i)
            announce("§c[AutoBuild]§r slab half mismatch at (${pos.x},${pos.y},${pos.z}): " +
                "server shows ${actual.states}, schematic wants ${expected.props} — fix by hand")
            bumpFailure("slab state mismatch")
        } else {
            t.markRetry(i)   // BB-5: retry without double-counting the attempt
            bumpFailure("placed ${actual.name}${actual.states} but schematic wants ${expected.name}${expected.props}")
        }
    }

    private fun bedrockBlockOf(def: org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition?): BedrockBlock? {
        def ?: return null
        return when (def) {
            is org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition ->
                BedrockBlock(def.identifier, emptyMap())
            is com.rubidiumclient.core.relay.Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> {
                val name = def.tag.getString("name") ?: return null
                val states = runCatching {
                    val sm = def.tag.getCompound("states") ?: return@runCatching mutableMapOf<String, Any?>()
                    LinkedHashMap<String, Any?>().also { out -> for (k in sm.keys) out[k] = sm[k] }
                }.getOrElse { mutableMapOf<String, Any?>() }
                BedrockBlock(name, states)
            }
            else -> null
        }
    }

    // ── verification via world state (UpdateBlock is digested by WorldBlockTracker) ──

    private fun verifySent(m: SchematicModel, t: PlacementTracker) {
        for (i in t.sentCells()) {                        // BB-5: only in-flight cells, not every cell
            if (t.status[i] != CellStatus.SENT) continue
            val x = i % m.width
            val y = i / (m.width * m.length)
            val z = (i / m.width) % m.length
            val wx = originX + x; val wy = originY + y; val wz = originZ + z
            val worldId = WorldBlockTracker.getBlockIdentifier(wx, wy, wz) ?: continue
            // AB-12: still showing the OLD block (air, water, tall grass, snow...) means
            // "no answer yet", not a mismatch. The old code only skipped air, so placing
            // into water or grass produced an instant false failure.
            if (BlockMapper.isReplaceableId(worldId)) continue

            val state = m.states[m.cells[i]]
            val bedrock = BlockMapper.bedrockName(state) ?: continue
            if (worldId == bedrock) {
                if (isPlaceableSlab(state)) {
                    // name-only CANNOT tell slab halves apart. Trust only rich UpdateBlock
                    // evidence for slabs; if no rich stream shows up for 60 ticks (definition
                    // stripped by the relay), accept the name match once and say so.
                    if (richSeen[i] != null) continue                       // rich path decides
                    val waited = (slabRichWait[i] ?: 0) + 1
                    slabRichWait[i] = waited
                    if (waited < 60) continue
                    announce("§7[AutoBuild]§r no state stream for slab at ($wx,$wy,$wz) — accepting name match")
                }
                confirm(t, i)
            } else {
                // server accepted but put something else there (or an override)
                t.markRetry(i)
                bumpFailure("world holds $worldId where $bedrock was expected")
                if (stopped) return
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun countOrder(t: PlacementTracker, s: CellStatus): Int = order.count { t.status[it] == s }

    /** AB-12: done means nothing is pending or in flight; failed/obstructed cells are reported, not waited on. */
    private fun finishIfDone(t: PlacementTracker) {
        if (finished) return
        finished = true
        val failed = countOrder(t, CellStatus.FAILED)
        announce("§a[AutoBuild]§r finished — §e$doneCount§r/$totalTargets§r done" +
            (if (failed > 0) ", §6$failed§r need attention (occupied / failed / slabs to check)" else "") +
            ". Stairs/trapdoors/torches/plants/doors stay manual until Phase 2b.")
    }

    private fun idleIfNeeded(t: PlacementTracker) {
        val now = System.currentTimeMillis()
        if (now - lastIdleNotice <= IDLE_NOTICE_MS) return
        lastIdleNotice = now
        val pending = countOrder(t, CellStatus.PENDING)
        val inFlight = t.inFlight
        if (pending + inFlight == 0) { finishIfDone(t); return }
        announce("§7[AutoBuild]§r idle — §e$pending§r pending, none placeable right now " +
            "(out of reach, no clickable support yet, you're standing in the cell, or waiting for items). " +
            "Walk along the build outline.")
    }

    private fun maybeProgress() {
        if (doneCount > 0 && doneCount % PROGRESS_EVERY == 0) {
            val pct = if (totalTargets > 0) doneCount * 100 / totalTargets else 0
            announce("§b[AutoBuild]§r done §e$doneCount§r/$totalTargets §7($pct%)§r")
        }
    }

    private fun bumpFailure(why: String) {
        consecutiveFails++
        if (consecutiveFails >= BREAKER_LIMIT && !stopped) {
            stopped = true
            announce("§c[AutoBuild]§r circuit breaker: $BREAKER_LIMIT failures in a row without a " +
                "confirmed placement ($why). Engine stopped — toggle Schematica off/on to retry.")
        }
    }

    private fun announceOnceUnsupported(javaId: String) {
        if (announcedUnsupported.add(javaId)) {
            announce("§7[AutoBuild]§r skipping §f$javaId§r — not in BlockIdMap yet")
        }
    }
}
