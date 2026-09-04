package com.rubidiumclient

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import com.rubidiumclient.auth.AccountManager
import com.rubidiumclient.auth.MicrosoftAuthManager
import com.rubidiumclient.config.Config
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.social.FriendManager

import com.rubidiumclient.module.combat.ACAModule
import com.rubidiumclient.module.combat.AnchorAura
import com.rubidiumclient.module.combat.AntiBed
import com.rubidiumclient.module.combat.AntiCrystal
import com.rubidiumclient.module.combat.AutoArmor
import com.rubidiumclient.module.combat.AutoHvHModule
import com.rubidiumclient.module.combat.AutoTotem


import com.rubidiumclient.module.combat.AutoTrapModule
import com.rubidiumclient.module.combat.BedAura
import com.rubidiumclient.module.combat.CTrapModule
import com.rubidiumclient.module.combat.ObsidianMinerModule
import com.rubidiumclient.module.combat.Criticals
import com.rubidiumclient.module.combat.CrystalAura
import com.rubidiumclient.module.combat.HitAndRunProModule
import com.rubidiumclient.module.combat.HitboxModule
import com.rubidiumclient.module.combat.HotbarSwitcherModule
import com.rubidiumclient.module.combat.InfiniteAuraModule
import com.rubidiumclient.module.combat.KillAura
import com.rubidiumclient.module.combat.KillAuraV3
import com.rubidiumclient.module.combat.LegitAura
import com.rubidiumclient.module.combat.PistonAura
import com.rubidiumclient.module.combat.SCRFighter
import com.rubidiumclient.module.combat.SelfTrapModule
import com.rubidiumclient.module.combat.TPAura
import com.rubidiumclient.module.combat.TriggerBotModule

import com.rubidiumclient.module.misc.AutoBaseFinder
import com.rubidiumclient.module.misc.AutoMine
import com.rubidiumclient.module.misc.AutoTravel
import com.rubidiumclient.module.misc.ChatAdvertiser
import com.rubidiumclient.module.misc.ChatSpammer
import com.rubidiumclient.module.misc.ComboShortcut
import com.rubidiumclient.module.misc.CommandHelper
import com.rubidiumclient.module.misc.AutoDisconnect
import com.rubidiumclient.module.misc.Performance
import com.rubidiumclient.module.misc.PopCounter
import com.rubidiumclient.module.misc.ShulkerDupe

import com.rubidiumclient.module.movement.AirJump
import com.rubidiumclient.module.movement.AntiKnockback
import com.rubidiumclient.module.movement.AntiPiston
import com.rubidiumclient.module.movement.AutoSprintModule
import com.rubidiumclient.module.movement.BypassFly
import com.rubidiumclient.module.movement.CreativeFly
import com.rubidiumclient.module.movement.ElytraFly
import com.rubidiumclient.module.movement.FreeCamera
import com.rubidiumclient.module.movement.FreeLook
import com.rubidiumclient.module.movement.Jetpack
import com.rubidiumclient.module.movement.LifeboatFly
import com.rubidiumclient.module.movement.MotionFly
import com.rubidiumclient.module.movement.NoClipModule
import com.rubidiumclient.module.movement.NoFallDamage
import com.rubidiumclient.module.movement.NoSlowdown
import com.rubidiumclient.module.movement.Scaffold
import com.rubidiumclient.module.movement.Speed
import com.rubidiumclient.module.movement.SpiderModule
import com.rubidiumclient.module.movement.Timer

import com.rubidiumclient.module.visual.AntiBlind
import com.rubidiumclient.module.visual.ArmorHide
import com.rubidiumclient.module.visual.ArrayListModule
import com.rubidiumclient.module.visual.ChunkFinder
import com.rubidiumclient.module.visual.ESP
import com.rubidiumclient.module.visual.FOVChanger
import com.rubidiumclient.module.visual.FullBright
import com.rubidiumclient.module.visual.NoFire
import com.rubidiumclient.module.visual.ArmorHudModule
import com.rubidiumclient.module.visual.NoHurtCam
import com.rubidiumclient.module.visual.TargetESP
import com.rubidiumclient.module.visual.Xray

import com.rubidiumclient.module.player.AntiAfkModule
import com.rubidiumclient.module.player.AntiLagModule
import com.rubidiumclient.module.player.GodModeModule

import com.rubidiumclient.module.world.NukerModule
import com.rubidiumclient.module.world.WeatherControllerModule

import com.rubidiumclient.utils.ItemIconProvider
import com.rubidiumclient.utils.WorldBlockTracker

class RubidiumClientApp : Application() {

    companion object {
        private const val TAG = "RubidiumClientApp"
        lateinit var instance: RubidiumClientApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        installCrashLogger()

        ServerConfig.init(applicationContext)
        Config.init(applicationContext)
        AccountManager.init(applicationContext)
        MicrosoftAuthManager.init(applicationContext)
        FriendManager.init(applicationContext)

        Thread({
            try {
                Definitions.init(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Definitions load error: ${e.message}", e)
            }
        }, "RubidiumDefinitionsLoader").apply {
            isDaemon = true
            start()
        }

        WorldBlockTracker.init()
        ItemIconProvider.init(applicationContext)
        registerModules()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val content = "${java.util.Date()}\n\n${Log.getStackTraceString(throwable)}"
                writeCrashToDownloads(content)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToDownloads(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "baba.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, "baba.txt").writeText(content)
        }
    }

    private fun registerModules() {
        ModuleManager.registerAll(
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
            CommandHelper()
        )
    }
}
