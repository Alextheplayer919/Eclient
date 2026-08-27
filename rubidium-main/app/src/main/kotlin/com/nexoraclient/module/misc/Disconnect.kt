package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoDisconnect : BaseModule(
    name        = "AutoDisconnect",
    category    = ModuleCategory.PLAYER,
    description = "Void düşüşü, yüksek ping veya can+totem koşulunda otomatik bağlantıyı keser"
) {

    private companion object {
        const val TOTEM_IDENTIFIER = "minecraft:totem_of_undying"
        const val OFFHAND_SLOT = 119
    }

    private val enableVoid        = bool ("Void Check",        true)
    private val voidY             = float("Void Y",            -32f, -256f, 320f)

    private val enablePing        = bool ("Ping Check",        true)
    private val pingThreshold     = int  ("Ping Threshold",    600,  100,   5000)

    private val enableHealthTotem = bool ("Health+Totem Check", true)
    private val healthThreshold   = float("Health Threshold",  6f,   1f,    20f)

    private val checkIntervalMs   = int  ("Check Interval (ms)", 100, 50,   1000)
    private val shortcut          = bool ("Shortcut",            false)

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) check()
            delay(checkIntervalMs.value.toLong())
        }
    }

    private fun check() {
        if (enableVoid.value && EntityTracker.selfY < voidY.value) {
            trigger("Void")
            return
        }

        if (enablePing.value && EntityTracker.selfPingMs > pingThreshold.value) {
            trigger("Ping")
            return
        }

        if (enableHealthTotem.value && EntityTracker.selfHealth <= healthThreshold.value && !hasTotem()) {
            trigger("Health+Totem")
            return
        }
    }

    private fun hasTotem(): Boolean {
        for (slot in 0..35) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (InventoryUtil.resolveIdentifier(item) == TOTEM_IDENTIFIER) return true
        }
        val offhand = EntityTracker.getInventoryItem(OFFHAND_SLOT)
        if (offhand != null && InventoryUtil.resolveIdentifier(offhand) == TOTEM_IDENTIFIER) return true
        return false
    }

    private fun trigger(reason: String) {
        PacketEventBus.currentSession?.disconnect("RubidiumClient: AutoDisconnect ($reason)")
        setEnabled(false)
    }
}
