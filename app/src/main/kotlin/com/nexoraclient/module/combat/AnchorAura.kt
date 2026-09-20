package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.math.floor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AnchorAura — the Overworld anchor loop, per real Bedrock mechanics:
 *
 *   1. PLACE the anchor by clicking a SOLID neighbor block with a face
 *      (vanilla semantics — the server rejects clicked-air placements, which
 *      is why the old "click the air cell" version silently never placed).
 *   2. RIGHT-CLICK THE ANCHOR WITH GLOWSTONE. In the Overworld/End this is
 *      the detonation itself: the anchor explodes on that click. In the
 *      Nether that click instead ADDS A CHARGE, so…
 *   3. if the anchor is STILL THERE after the glowstone click (Nether case),
 *      punch it once with any non-glowstone hand to trigger the boom.
 *   4. Loop back to 1 on a 300 ms drop of the completed attempt.
 *
 * Switching is EXPLICIT (owner request, no silent-switch games): we select
 * the item slot, send the use, and restore the slot with a plain
 * MobEquipmentPacket afterwards.
 */
class AnchorAura : BaseModule(
    name        = "AnchorAura",
    category    = ModuleCategory.COMBAT,
    description = "Rapid anchor bomber – place, glowstone-click (boom), repeat"
) {

    // ── Settings ─────────────────────────────────────────
    private val targetRange     = int  ("Target Range",     8,   2,  16)
    private val placeRange      = int  ("Place Range",      9,   2,  16)
    private val cooldownMs      = int  ("Cooldown (ms)",   80,  20, 500)
    private val maxAttempts     = int  ("Max Simultaneous", 3,   1,  5)
    private val packetGapMs     = int  ("Packet Gap (ms)", 50,  20, 300)
    private val chargeDelayMs   = int  ("Charge Delay (ms)", 150, 50, 500)
    private val tickMs          = int  ("Tick Speed (ms)", 50,  10, 150)
    private val friendSkip      = bool("Skip Friends",    true)
    private val noSwitch        = bool("No Switch",       false)
    private val forceMode       = bool("Force",           false)
    private val shortcut        = bool("Shortcut",        false)
    private val log             = bool("Log",             false)

    // ── Block constants ──────────────────────────────────
    private val ANCHOR    = "minecraft:respawn_anchor"
    private val GLOWSTONE = "minecraft:glowstone"

    // ── State ────────────────────────────────────────────
    private data class Attempt(
        val pos: Vector3i,
        val placedAt: Long,
        val verifyDeadline: Long,
        val targetId: Long,
        var nextCheckAt: Long = 0L,
        var verified: Boolean = false,
        var charged: Boolean = false,
        var chargedAt: Long = 0L,
        var activated: Boolean = false
    )

    private val activeAttempts = CopyOnWriteArrayList<Attempt>()
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var lastPacketMs = 0L
    private var tickJob: Job? = null

    private var originalHotbarSlot = -1

    @Volatile private var lastFailLogMs = 0L
    @Volatile private var lastChatFailMs = 0L

    override fun onEnable() {
        super.onEnable()
        activeAttempts.clear()
        lastPacketMs = 0L
        originalHotbarSlot = EntityTracker.selfHotbarSlot
        tickJob = scope.launch {
            while (isActive) {
                if (isEnabled) tick()
                delay(tickMs.value.toLong())
            }
        }
    }

    override fun onDisable() {
        tickJob?.cancel()
        activeAttempts.clear()
        super.onDisable()
    }

    // ── Packet throttling ─────────────────────────────────
    private fun canSendPacket(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPacketMs < packetGapMs.value) return false
        lastPacketMs = now
        return true
    }

    // ── Main tick ─────────────────────────────────────────
    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()

        // 1) Process ongoing attempts
        for (attempt in activeAttempts.toList()) {
            if (attempt.activated) {
                // The anchor no longer exists post-detonation — drop the
                // attempt quickly so the loop restarts on the same spot.
                if (now - attempt.placedAt > 300L) {
                    activeAttempts.remove(attempt)
                }
                continue
            }

            // Verification phase
            if (!attempt.verified && !forceMode.value) {
                if (now < attempt.nextCheckAt) continue
                when (verifyPlacement(attempt)) {
                    VerifyResult.CONFIRMED -> {
                        attempt.verified = true
                        attempt.nextCheckAt = now + 20L
                    }
                    VerifyResult.REJECTED -> {
                        logFail(session, "anchor rejected \u0040 ${attempt.pos.x},${attempt.pos.y},${attempt.pos.z} (block never appeared)")
                        activeAttempts.remove(attempt)
                        continue
                    }
                    VerifyResult.PENDING -> {
                        attempt.nextCheckAt = now + 20L
                        continue
                    }
                }
            }

            // Glowstone-click phase: in the Overworld this IS the detonation.
            if (attempt.verified && !attempt.charged && !attempt.activated) {
                if (canSendPacket()) {
                    charge(session, attempt)
                    attempt.charged = true
                    attempt.chargedAt = now
                }
                continue
            }

            // Nether-only punch phase: if the anchor still exists after the
            // glowstone click, that click was a CHARGE; punch once to pop it.
            if (attempt.charged && !attempt.activated) {
                if (now - attempt.chargedAt >= chargeDelayMs.value.toLong()) {
                    if (canSendPacket()) {
                        detonate(session, attempt)
                        attempt.activated = true
                    }
                }
            }
        }

        // 2) Place a new anchor if we have room
        if (activeAttempts.size >= maxAttempts.value) return
        if (now - lastAttemptMs < cooldownMs.value) return

        val target = nearestEnemy() ?: return
        attemptPlace(session, target)
    }

    // ── Target selection ──────────────────────────────────
    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        val busy = activeAttempts.map { it.targetId }.toSet()
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.runtimeId !in busy }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .filter { it.x != 0f || it.y != 0f || it.z != 0f }
            .minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) }
    }

    // ── Placement spot (air cell + the solid neighbor we click) ──
    private data class Spot(val air: Vector3i, val nPos: Vector3i, val nId: String, val nFace: Int)

    private fun findPlacementSpot(target: EntityTracker.TrackedEntity): Spot? {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        val candidates = mutableListOf<Pair<Spot, Float>>()

        for (dy in -2..2) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val px = tx + dx
                    val py = ty + dy
                    val pz = tz + dz

                    if (!WorldBlockTracker.hasData(px, py, pz)) continue
                    val block = WorldBlockTracker.getBlockIdentifier(px, py, pz) ?: "air"
                    if (block !in NON_SOLID) continue

                    val below = WorldBlockTracker.getBlockIdentifier(px, py - 1, pz) ?: "air"
                    if (below in NON_SOLID) continue

                    val dist = MathUtil.dist3(px + 0.5f, py + 0.5f, pz + 0.5f,
                        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
                    if (dist > placeRange.value && !forceMode.value) continue

                    // The magic part the old module missed: the placement
                    // packet must click a SOLID NEIGHBOR (pos+face), not the
                    // air cell — vanilla sends "I clicked THIS block on THIS
                    // face", and the server drops the result into the air cell.
                    val neighbor = PlacementUtil.findClickableNeighbor(px, py, pz)
                    if (neighbor == null && !forceMode.value) continue
                    val (nPos, nId, nFace) = neighbor ?: Triple(Vector3i.from(px, py - 1, pz), "minecraft:obsidian", 1)

                    candidates.add(Pair(Spot(Vector3i.from(px, py, pz), nPos, nId, nFace), dist))
                }
            }
        }

        return candidates.minByOrNull { it.second }?.first
            ?: if (forceMode.value) {
                Spot(Vector3i.from(tx, ty, tz), Vector3i.from(tx, ty - 1, tz), "minecraft:obsidian", 1)
            } else null
    }

    private fun attemptPlace(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val spot = findPlacementSpot(target) ?: run {
            logFail(session, "no spot (need world data / blocks nearby; Force bypasses)")
            return
        }
        if (!canSendPacket()) return

        val anchorSlot = PlacementUtil.findItemInInventory(ANCHOR) ?: run {
            logFail(session, "no respawn anchor in inventory")
            return
        }

        val originalSlot = EntityTracker.selfHotbarSlot
        val prepared = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = ANCHOR,
            noSwitch = false  // explicit: select, use, plain restore after
        ) ?: run {
            logFail(session, "could not prepare anchor item")
            return
        }

        // Click the solid neighbor — THIS is what vanilla actually sends.
        val success = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = prepared,
            blockPos = spot.nPos,
            blockId = spot.nId,
            blockFace = spot.nFace
        )
        InventoryUtil.sendHotbarSelect(session, originalSlot)
        EntityTracker.selfHotbarSlot = originalSlot
        if (!success) {
            logFail(session, "placement send failed")
            return
        }

        val now = System.currentTimeMillis()
        lastAttemptMs = now
        activeAttempts.add(
            Attempt(
                pos = spot.air,
                placedAt = now,
                verifyDeadline = now + 300L,
                targetId = target.runtimeId,
                nextCheckAt = now + 20L
            )
        )
        sendLog(session, "placed @ ${spot.air.x},${spot.air.y},${spot.air.z}")
    }

    // ── Verification ──────────────────────────────────────
    private enum class VerifyResult { CONFIRMED, REJECTED, PENDING }

    private fun verifyPlacement(attempt: Attempt): VerifyResult {
        if (forceMode.value) return VerifyResult.CONFIRMED
        if (!WorldBlockTracker.hasData(attempt.pos.x, attempt.pos.y, attempt.pos.z)) {
            return if (System.currentTimeMillis() < attempt.verifyDeadline) VerifyResult.PENDING else VerifyResult.CONFIRMED
        }
        val id = WorldBlockTracker.getBlockIdentifier(attempt.pos.x, attempt.pos.y, attempt.pos.z)
        return when {
            id == ANCHOR -> VerifyResult.CONFIRMED
            id == null || id in NON_SOLID -> VerifyResult.PENDING
            else -> VerifyResult.REJECTED
        }
    }

    // ── Charge = glowstone click = Overworld detonation ──
    private fun charge(session: RubidiumRelaySession, attempt: Attempt) {
        val glowstoneSlot = PlacementUtil.findItemInInventory(GLOWSTONE) ?: run {
            logFail(session, "no glowstone in inventory")
            attempt.charged = false
            return
        }

        val originalSlot = EntityTracker.selfHotbarSlot
        val glowstone = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = GLOWSTONE,
            noSwitch = false
        ) ?: run {
            attempt.charged = false
            return
        }

        val chargeSuccess = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = glowstone,
            blockPos = attempt.pos,
            blockId = ANCHOR,
            blockFace = 1
        )
        InventoryUtil.sendHotbarSelect(session, originalSlot)
        EntityTracker.selfHotbarSlot = originalSlot

        if (!chargeSuccess) {
            attempt.charged = false
        } else {
            sendLog(session, "glowstone-click @ ${attempt.pos.x},${attempt.pos.y},${attempt.pos.z}")
        }
    }

    // ── Nether-only punch: anchor still there -> pop it ──
    private fun detonate(session: RubidiumRelaySession, attempt: Attempt) {
        // Overworld check first: if world data says the anchor is already
        // gone, the glowstone click already blew it — nothing to punch.
        if (WorldBlockTracker.hasData(attempt.pos.x, attempt.pos.y, attempt.pos.z)) {
            val id = WorldBlockTracker.getBlockIdentifier(attempt.pos.x, attempt.pos.y, attempt.pos.z)
            if (id != null && id != ANCHOR) {
                sendLog(session, "boom (glowstone click did it)")
                return
            }
        }

        // Hands check: clicking a charged anchor WITH glowstone would add
        // another charge instead of detonating. Swap off glowstone first.
        val heldId = EntityTracker.getHeldItem()?.let { InventoryUtil.resolveIdentifier(it) }
        if (heldId == GLOWSTONE) {
            for (s in 0..8) {
                val id = EntityTracker.getInventoryItem(s)?.let { InventoryUtil.resolveIdentifier(it) }
                if (id != GLOWSTONE) {
                    InventoryUtil.sendHotbarSelect(session, s)
                    EntityTracker.selfHotbarSlot = s
                    break
                }
            }
        }

        PlacementUtil.sendInteract(session, attempt.pos, ANCHOR)

        if (EntityTracker.selfHotbarSlot != originalHotbarSlot) {
            InventoryUtil.sendHotbarSelect(session, originalHotbarSlot)
            EntityTracker.selfHotbarSlot = originalHotbarSlot
        }
        sendLog(session, "punch @ ${attempt.pos.x},${attempt.pos.y},${attempt.pos.z}")
    }

    // ── Logging (so "it doesn't react" always explains itself) ──
    private fun sendLog(session: RubidiumRelaySession, message: String) {
        if (!log.value) return
        try {
            session.sendToClient(TextPacket().apply {
                type               = TextPacket.Type.RAW
                isNeedsTranslation = false
                sourceName         = ""
                xuid               = ""
                platformChatId     = ""
                setMessage("§6[AnchorAura]§f $message")
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }

    private fun logFail(session: RubidiumRelaySession, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailLogMs >= 1000L) {
            lastFailLogMs = now
            DiagLog.log("AnchorAura", "⚠ $message")
        }
        if (!log.value) return
        if (now - lastChatFailMs < 10000L) return
        lastChatFailMs = now
        sendLog(session, "⚠ $message")
    }

    companion object {
        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }
}
