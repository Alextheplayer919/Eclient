
package com.rubidiumclient.ui.overlay

import androidx.compose.runtime.*

enum class ToastType { MODULE_TOGGLE, WARNING, INFO, ERROR }

data class ModuleToast(
    val moduleName : String,
    val enabled    : Boolean,
    val type       : ToastType = ToastType.MODULE_TOGGLE,
    val customText : String    = ""
)

object OverlayState {

    const val TOAST_DURATION_MS   = 1800L
    const val WARNING_DURATION_MS = 2500L
    const val ERROR_DURATION_MS   = 3000L

    var isOverlayVisible by mutableStateOf(false)
        private set

    var isMenuOpen by mutableStateOf(false)
        private set

    var totemCount by mutableIntStateOf(0)
        private set

    var activeModuleCount by mutableIntStateOf(0)
        private set

    var currentFps by mutableIntStateOf(0)
        private set

    var currentPingMs by mutableIntStateOf(0)
        private set

    private val _toasts = mutableStateListOf<ModuleToast>()
    val toasts: List<ModuleToast> get() = _toasts

    internal fun setOverlayVisible(v: Boolean) { isOverlayVisible = v }
    internal fun setMenuOpen(v: Boolean)        { isMenuOpen = v }

    fun updateTotemCount(count: Int)       { totemCount = count }
    fun updateActiveModuleCount(count: Int) { activeModuleCount = count }
    fun updatePerformanceStats(fps: Int, pingMs: Int) {
        currentFps    = fps
        currentPingMs = pingMs
    }

    fun postModuleToast(toast: ModuleToast) {
        _toasts.removeAll { it.moduleName == toast.moduleName }
        _toasts.add(toast)
    }

    fun clearModuleToast(toast: ModuleToast) { _toasts.remove(toast) }
    fun clearAllToasts()                      { _toasts.clear() }

    fun postWarning(message: String) {
        _toasts.add(ModuleToast(
            moduleName = "warning_${System.currentTimeMillis()}",
            enabled    = false,
            type       = ToastType.WARNING,
            customText = message
        ))
    }

    fun postInfo(message: String) {
        _toasts.add(ModuleToast(
            moduleName = "info_${System.currentTimeMillis()}",
            enabled    = true,
            type       = ToastType.INFO,
            customText = message
        ))
    }
}
