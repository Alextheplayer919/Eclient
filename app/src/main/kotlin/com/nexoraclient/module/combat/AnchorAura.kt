package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3i
import kotlin.math.floor
import java.util.concurrent.CopyOnWriteArrayList

class AnchorAura : BaseModule(
    name        = "AnchorAura",
    category    = ModuleCategory.COMBAT,
    description = "Rapid anchor bomber – place, charge with glowstone, detonate"
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
    
    // Track original hotbar slot for restoration
    private var originalHotbarSlot = -1

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
                if (now - attempt.placedAt > 3000L) {
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
                        activeAttempts.remove(attempt)
                        continue
                    }
                    VerifyResult.PENDING -> {
                        attempt.nextCheckAt = now + 20L
                        continue
                    }
                }
            }

            // Charge phase: send glowstone once when verified
            if (attempt.verified && !attempt.charged && !attempt.activated) {
                if (canSendPacket()) {
                    charge(session, attempt)
                    attempt.charged = true
                    attempt.chargedAt = now
                }
                continue
            }

            // Detonate phase: wait chargeDelayMs, then detonate
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

    // ── Enhanced Target Selection ──────────────────────────────────
    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        val busy = activeAttempts.map { it.targetId }.toSet()
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.runtimeId !in busy }
            .filter { !friendSkip.value || !it.isFriendEntity }
            // ✅ Improved detection: filter out dead/invalid players
            .filter { it.x != 0f || it.y != 0f || it.z != 0f }  // exclude null position
            .minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) }
    }

    // ── Placement logic with validation ───────────────────────────────────
    private fun findPlacementSpot(target: EntityTracker.TrackedEntity): Vector3i? {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        
        // Scan a 5x5x5 region around target, prioritize closer positions
        val candidates = mutableListOf<Pair<Vector3i, Float>>()
        
        for (dy in -2..2) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val px = tx + dx
                    val py = ty + dy
                    val pz = tz + dz
                    
                    // ✅ VALIDATION: Must have world data
                    if (!WorldBlockTracker.hasData(px, py, pz)) continue
                    
                    val block = WorldBlockTracker.getBlockIdentifier(px, py, pz) ?: "air"
                    
                    // ✅ VALIDATION: placement spot must be air/liquid
                    if (block !in NON_SOLID) continue
                    
                    // ✅ VALIDATION: check for solid below
                    val below = WorldBlockTracker.getBlockIdentifier(px, py - 1, pz) ?: "air"
                    if (below in NON_SOLID) continue
                    
                    // ✅ VALIDATION: Must be within placeRange
                    val dist = MathUtil.dist3(px + 0.5f, py + 0.5f, pz + 0.5f,
                        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
                    if (dist > placeRange.value && !forceMode.value) continue
                    
                    // ✅ VALIDATION: ensure anchor is actually placeable (not against void/bedrock)
                    val neighbor = PlacementUtil.findClickableNeighbor(px, py, pz)
                    if (neighbor == null && !forceMode.value) continue
                    
                    candidates.add(Pair(Vector3i.from(px, py, pz), dist))
                }
            }
        }
        
        // Return closest valid spot
        return candidates.minByOrNull { it.second }?.first
            ?: if (forceMode.value) Vector3i.from(tx, ty, tz) else null
    }

    private fun attemptPlace(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val pos = findPlacementSpot(target) ?: return
        if (!canSendPacket()) return

        // ✅ STEP 1: Check if anchor is available
        val anchorSlot = PlacementUtil.findItemInInventory(ANCHOR)
        if (anchorSlot == null) return

        // ✅ STEP 2: Switch to anchor (save original slot)
        originalHotbarSlot = EntityTracker.selfHotbarSlot
        val prepared = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = ANCHOR,
            noSwitch = false  // ✅ MUST switch to place silently
        ) ?: return

        // ✅ STEP 3: Send placement packet
        val success = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = prepared,
            blockPos = pos,
            blockId = ANCHOR,
            blockFace = 1
        )
        PlacementUtil.revert(session, prepared)
        if (!success) return

        lastAttemptMs = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        activeAttempts.add(
            Attempt(
                pos = pos,
                placedAt = now,
                verifyDeadline = now + 300L,
                targetId = target.runtimeId,
                nextCheckAt = now + 20L
            )
        )
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

    // ── Charge: apply glowstone to anchor ──
    private fun charge(session: RubidiumRelaySession, attempt: Attempt) {
        // ✅ STEP 1: Check if glowstone exists
        val glowstoneSlot = PlacementUtil.findItemInInventory(GLOWSTONE)
        if (glowstoneSlot == null) {
            attempt.charged = false
            return
        }

        // ✅ STEP 2: Switch to glowstone
        val originalSlot = EntityTracker.selfHotbarSlot
        val glowstone = PlacementUtil.prepareItemForUse(
            session = session,
            identifier = GLOWSTONE,
            noSwitch = false  // ✅ MUST switch to place silently
        ) ?: return

        // ✅ STEP 3: Use glowstone on the anchor to charge it
        val chargeSuccess = PlacementUtil.sendPlacementUseRaw(
            session = session,
            prepared = glowstone,
            blockPos = attempt.pos,
            blockId = ANCHOR,
            blockFace = 1
        )
        PlacementUtil.revert(session, glowstone)
        
        // ✅ STEP 4: Switch back to original item before charge
        InventoryUtil.sendHotbarSelect(session, originalSlot)
        EntityTracker.selfHotbarSlot = originalSlot
        
        if (!chargeSuccess) {
            attempt.charged = false
        }
    }

    // ── Detonate: right-click charged anchor ──
    private fun detonate(session: RubidiumRelaySession, attempt: Attempt) {
        // ✅ STEP 1: Save current slot
        val beforeDetonateSlot = EntityTracker.selfHotbarSlot
        
        // ✅ STEP 2: Right-click the anchor to detonate it
        PlacementUtil.sendInteract(session, attempt.pos, ANCHOR)
        
        // ✅ STEP 3: Restore to original slot if needed
        if (beforeDetonateSlot != originalHotbarSlot) {
            InventoryUtil.sendHotbarSelect(session, originalHotbarSlot)
            EntityTracker.selfHotbarSlot = originalHotbarSlot
        }
    }

    companion object {
        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }
}
