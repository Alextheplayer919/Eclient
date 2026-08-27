package com.rubidiumclient.module

import com.rubidiumclient.core.relay.RubidiumRelaySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ModuleManager {

    private val _modules = mutableListOf<BaseModule>()
    val modules: List<BaseModule> get() = _modules

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var initialized = false

    fun registerAll(vararg mods: BaseModule) {
        if (initialized) {
            return
        }
        initialized = true
        _modules.addAll(mods)
    }

    fun register(vararg mods: BaseModule) = registerAll(*mods)

    fun getAll(): List<BaseModule> = _modules

    fun registerToSession(session: RubidiumRelaySession) {
    }

    fun shortcutModules(): List<BaseModule> =
        _modules.filter { m ->
            m.settings.filterIsInstance<BoolSetting>()
                .any { it.name == "Shortcut" && it.value }
        }

    fun toggle(module: BaseModule) {
        module.setEnabled(!module.isEnabled)
        _version.value++
    }

    fun enable(module: BaseModule) {
        if (!module.isEnabled) { module.setEnabled(true); _version.value++ }
    }

    fun disable(module: BaseModule) {
        if (module.isEnabled) { module.setEnabled(false); _version.value++ }
    }

    fun disableAll() {
        _modules.filter { it.isEnabled }.forEach { it.setEnabled(false) }
        _version.value++
    }

    fun byName(name: String): BaseModule? =
        _modules.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun byCategory(cat: ModuleCategory): List<BaseModule> =
        _modules.filter { it.category == cat }

    fun enabledCount(): Int = _modules.count { it.isEnabled }

    fun combatModules()   = byCategory(ModuleCategory.COMBAT)
    fun movementModules() = byCategory(ModuleCategory.MOVEMENT)
    fun visualModules()   = byCategory(ModuleCategory.VISUAL)
    fun playerModules()   = byCategory(ModuleCategory.PLAYER)
    fun worldModules()    = byCategory(ModuleCategory.WORLD)
    fun miscModules()     = byCategory(ModuleCategory.MISC)
}
