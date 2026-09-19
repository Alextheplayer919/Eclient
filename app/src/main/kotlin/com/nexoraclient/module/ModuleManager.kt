package com.rubidiumclient.module

import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.utils.DiagLog
import com.rubidiumclient.module.combat.*
import com.rubidiumclient.module.misc.*
import com.rubidiumclient.module.movement.*
import com.rubidiumclient.module.visual.*
import com.rubidiumclient.module.player.*
import com.rubidiumclient.module.world.*
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
            // Previously this returned in silence, so any module registered
            // after first init simply vanished with no trace anywhere. The
            // behaviour is unchanged, but it is no longer invisible: the drop
            // is written to the diag log with the module names.
            runCatching {
                DiagLog.log(
                    "ModuleManager",
                    "registerAll ignored ${mods.size} module(s) after init: " +
                        mods.joinToString { it.name }
                )
            }
            return
        }
        initialized = true
        _modules.addAll(mods)
    }

    fun register(vararg mods: BaseModule) = registerAll(*mods)

    fun getAll(): List<BaseModule> = _modules

    // Called by ConnectionManager on every new relay session. Intentionally a
    // no-op today — modules pick up the session themselves via
    // PacketEventBus.currentSession / event.session. Kept because
    // ConnectionManager depends on it; do not delete without updating that
    // call site.
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

    // ── Auto‑register ALL modules on first access ──
    init {
        registerAll(
            // ── COMBAT ──────────────────────────────────────────────
            KillAura(),
            KillAuraV3(),
            LegitAura(),
            TPAura(),
            SCRFighter(),
            HitAndRunProModule(),
            TriggerBotModule(),
            HitboxModule(),
            AutoHvHModule(),
            InfiniteAuraModule(),
            ACAModule(),
            HotbarSwitcherModule(),
            Criticals(),
            CrystalAura(),
            AntiCrystal(),
            AnchorAura(),
            BedAura(),
            AntiBed(),
            AutoTrapModule(),
            SelfTrapModule(),
            CTrapModule(),
            ObsidianMinerModule(),
            AutoTotem(),
            AutoArmor(),

            // ── MOVEMENT ─────────────────────────────────────────────
            Speed(),
            MotionFly(),
            NoLagback(),
            CreativeFly(),
            BypassFly(),
            LifeboatFly(),
            ElytraFly(),
            Jetpack(),
            AirJump(),
            NoFallDamage(),
            NoSlowdown(),
            AntiKnockback(),
            AntiPiston(),
            NoClipModule(),
            FreeCamera(),
            Timer(),
            SpiderModule(),
            FreeLook(),
            Scaffold(),

            // ── VISUAL ───────────────────────────────────────────────
            ESP(),
            TargetESP(),
            Xray(),
            FullBright(),
            AntiBlind(),
            NoFire(),
            NoHurtCam(),
            FOVChanger(),
            ChunkFinder(),
            ArrayListModule(),
            ArmorHide(),

            // ── PLAYER ───────────────────────────────────────────────
            GodModeModule(),
            AntiAfkModule(),
            AntiLagModule(),

            // ── WORLD ────────────────────────────────────────────────
            WeatherControllerModule(),
            NukerModule(),

            // ── MISC ─────────────────────────────────────────────────
            AutoBaseFinder(),
            AutoTravel(),
            AutoMine(),
            ChatSpammer(),
            ChatAdvertiser(),
            PopCounter(),
            ArmorHudModule(),
            AutoSprintModule(),
            ComboShortcut(1),
            ComboShortcut(2),
            AutoDisconnect(),
            Performance(),
            FlightProbe(),
            CommandHelper(),
            PacketCollector()
        )
    }
}
