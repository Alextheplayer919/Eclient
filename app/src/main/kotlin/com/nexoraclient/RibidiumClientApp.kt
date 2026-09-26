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
import com.rubidiumclient.utils.ItemIconProvider      // <-- ADDED
import com.rubidiumclient.utils.WorldBlockTracker   // <-- ADDED

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
            // Which game version this build targets and whether its codec + the
            // definition files actually resolved. Logged at startup so a playtest
            // does not need adb: baba.txt says target/resolved/exact right away.
            try {
                com.rubidiumclient.core.relay.TargetVersion.verify()
            } catch (e: Exception) {
                Log.e(TAG, "TargetVersion check error: ${e.message}", e)
            }
        }, "RubidiumDefinitionsLoader").apply {
            isDaemon = true
            start()
        }

        WorldBlockTracker.init()    // <-- now resolved
        ItemIconProvider.init(applicationContext)  // <-- now resolved

        // Module registration is now handled inside ModuleManager.init
        // No need to call registerModules() here.
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
}
