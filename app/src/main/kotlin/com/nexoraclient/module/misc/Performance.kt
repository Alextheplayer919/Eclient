
package com.rubidiumclient.module.misc

import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory

class Performance : BaseModule(
    name        = "Performance",
    category    = ModuleCategory.VISUAL,
    description = "Overlay'in fps tavanı ve boşta render etmeme ayarları — cihazı gereksiz yormasın diye"
) {
    val overlayFpsCap = int("Overlay FPS Cap", 60, 15, 144)
    val pauseWhenIdle = bool("Pause When Idle", true)
    val forceSoftwareLayer = bool("Force Software Layer", false)
    val staleEntityTimeoutMs    = int("Stale Entity Timeout (ms)", 30_000, 5_000, 120_000)
    val entityCleanupIntervalMs = int("Entity Cleanup Interval (ms)", 10_000, 2_000, 60_000)
    val showPerformanceHud = bool("Show FPS/Lag HUD", false)
    private val shortcut = bool("Shortcut", false)
}
