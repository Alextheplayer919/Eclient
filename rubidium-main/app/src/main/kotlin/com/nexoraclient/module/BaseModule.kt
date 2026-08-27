package com.rubidiumclient.module

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ModuleCategory(val displayName: String) {
    COMBAT("Combat"), MOVEMENT("Movement"), VISUAL("Visual"), PLAYER("Player"), WORLD("World"), MISC("Misc")
}

sealed class ModuleSetting<T>(val name: String) {
    abstract var value: T
}

class BoolSetting(name: String, default: Boolean = false) : ModuleSetting<Boolean>(name) {
    override var value: Boolean = default
}

class FloatSetting(name: String, val min: Float, val max: Float, default: Float) : ModuleSetting<Float>(name) {
    override var value: Float = if (min <= max) default.coerceIn(min, max) else default
}

class IntSetting(name: String, default: Int, val min: Int, val max: Int) : ModuleSetting<Int>(name) {
    override var value: Int = if (min <= max) default.coerceIn(min, max) else default
}

class EnumSetting<T : Enum<T>>(name: String, default: T, val values: Array<T>) : ModuleSetting<T>(name) {
    override var value: T = default
    fun next(): T {
        val idx = (values.indexOf(value) + 1) % values.size
        value = values[idx]
        return value
    }

    fun setByName(name: String): Boolean {
        val match = values.firstOrNull { it.name == name } ?: return false
        value = match
        return true
    }
}

class StringSetting(name: String, default: String) : ModuleSetting<String>(name) {
    override var value: String = default
}

abstract class BaseModule(
    val name       : String,
    val category   : ModuleCategory,
    val description: String = ""
) : PacketEventBus.PacketListener {

    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val settings: MutableList<ModuleSetting<*>> = mutableListOf()

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()
    val isEnabled: Boolean get() = _enabledFlow.value

    fun setEnabled(v: Boolean) {
        if (_enabledFlow.value == v) return
        _enabledFlow.value = v
        if (v) onEnable() else onDisable()
    }

    fun toggle() = setEnabled(!isEnabled)

    protected open fun onEnable()  { PacketEventBus.register(this) }
    protected open fun onDisable() { PacketEventBus.unregister(this) }

    override fun onPacket(event: PacketEvent) {}

    protected fun launchTickLoop(intervalMs: Long, block: suspend () -> Unit): Job =
        scope.launch {
            while (currentCoroutineContext().isActive) {
                if (isEnabled) {
                    try {
                        block()
                    } catch (e: Exception) {
                    }
                }
                delay(intervalMs)
            }
        }

    protected fun bool(name: String, default: Boolean = false) =
        BoolSetting(name, default).also { settings.add(it) }

    protected fun float(name: String, default: Float, min: Float, max: Float) =
        FloatSetting(name, min, max, default).also { settings.add(it) }

    protected fun int(name: String, default: Int, min: Int, max: Int) =
        IntSetting(name, default, min, max).also { settings.add(it) }

    protected inline fun <reified T : Enum<T>> enum(name: String, default: T) =
        EnumSetting(name, default, enumValues<T>()).also { settings.add(it) }

    protected fun string(name: String, default: String) =
        StringSetting(name, default).also { settings.add(it) }

    fun getSetting(name: String): ModuleSetting<*>? =
        settings.firstOrNull { it.name.equals(name, ignoreCase = true) }

    @Suppress("UNCHECKED_CAST")
    fun <T> getSettingValue(name: String): T? =
        getSetting(name)?.value as? T
}
