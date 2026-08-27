package com.rubidiumclient.module.misc

import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.ModuleManager

class ComboShortcut(index: Int) : BaseModule(
    name        = "ComboShortcut$index",
    category    = ModuleCategory.MISC,
    description = "Toggle multiple modules together with one shortcut"
) {
    val comboName = string("Name",    "Combo $index")
    val targets   = string("Modules", "")
    private val shortcut  = bool  ("Shortcut", false)

    override fun onEnable() {
        super.onEnable()
        applyToTargets(true)
    }

    override fun onDisable() {
        applyToTargets(false)
        super.onDisable()
    }

    private fun applyToTargets(enabled: Boolean) {
        targets.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { targetName ->
                val target = ModuleManager.byName(targetName) ?: return@forEach
                if (target === this) return@forEach
                if (enabled) ModuleManager.enable(target) else ModuleManager.disable(target)
            }
    }
}
