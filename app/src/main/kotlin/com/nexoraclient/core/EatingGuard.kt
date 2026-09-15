package com.rubidiumclient.core

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.utils.InventoryUtil

/**
 * Eating guard.
 *
 * Combat interrupts eating in Minecraft — the eat is cancelled the moment an
 * attack lands or a rotation is forced. This object answers one question, "is
 * the player eating right now?", so combat can hold off until the bite finishes
 * and then resume on its own.
 *
 * Detection reuses state EntityTracker already maintains from
 * PlayerAuthInputPacket:
 *
 *   selfUsingItem        set while the START_USING_ITEM input bit is present,
 *                        cleared after a 150ms grace, with an 8s watchdog
 *   selfItemUseDurationMs how long the current use has been going
 *
 * That bit is true for ANY item use (bows, shields, buckets), so it is combined
 * with a food-or-potion check on the held item — eating and drinking potions
 * (e.g. a strength potion) both pause combat. Item identity is resolved through
 * InventoryUtil.resolveIdentifier, which reads the connection's item
 * definitions — Bedrock runtime IDs are negotiated per connection, so the
 * legacy numeric IDs in InventoryUtil.FOOD_NET_IDS are not usable here.
 *
 * Failure mode is deliberately fail-open: if identity cannot be resolved the
 * guard reports "not eating" and combat keeps running. Guessing wrong in the
 * other direction would freeze the player mid-fight.
 */
object EatingGuard {

    private const val TAG = "EatingGuard"

    /** Master switch. Turning this off restores exactly the old behaviour. */
    @Volatile var enabled: Boolean = true

    /**
     * Pause on any item use rather than food only — also covers drawing a bow,
     * blocking with a shield and drinking potions.
     */
    @Volatile var pauseOnAnyItemUse: Boolean = false

    /**
     * Ignore uses shorter than this. A single stray tap should not freeze
     * combat; real eating takes ~1.6s (32 ticks at 20 tps).
     */
    @Volatile var minUseMs: Long = 150L

    /** Upper bound: past this the use is not eating (a drawn bow, a held block). */
    @Volatile var maxUseMs: Long = 4000L

    @Volatile private var lastReported: Boolean = false

    /** True while the player is mid-eat and combat should hold. */
    val isEating: Boolean
        get() {
            if (!enabled) return false

            return try {
                val using = EntityTracker.selfUsingItem
                if (!using) return false

                val heldFor = EntityTracker.selfItemUseDurationMs
                if (heldFor < minUseMs) return false

                // EntityTracker's own watchdog clears selfUsingItem after 8s,
                // but bail out earlier so a stuck bit cannot freeze combat.
                if (heldFor > maxUseMs) return false

                pauseOnAnyItemUse || isHoldingFood() || isHoldingPotion()
            } catch (e: Exception) {
                // Never let a guard break the packet path.
                DiagLog.log(TAG, "isEating threw: ${e.message}")
                false
            }
        }

    /** How long the current eat has been running, 0 if not eating. */
    val eatingForMs: Long
        get() = if (isEating) EntityTracker.selfItemUseDurationMs else 0L

    private fun isHoldingFood(): Boolean =
        try {
            InventoryUtil.isFood(EntityTracker.getHeldItem())
        } catch (e: Exception) {
            DiagLog.log(TAG, "held-item food check threw: ${e.message}")
            false
        }

    private fun isHoldingPotion(): Boolean =
        try {
            InventoryUtil.isPotion(EntityTracker.getHeldItem())
        } catch (e: Exception) {
            DiagLog.log(TAG, "held-item potion check threw: ${e.message}")
            false
        }

    /**
     * Called from PacketEventBus when the eating state flips, so the transition
     * shows up once in the diag log instead of on every packet.
     */
    internal fun noteTransition(now: Boolean) {
        if (now == lastReported) return
        lastReported = now
        DiagLog.log(TAG, if (now) "eating started — combat paused" else "eating ended — combat resumed")
    }
}
