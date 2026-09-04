package com.rubidiumclient.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_server")

object ServerConfig {

    const val DEFAULT_HOST     = "2b2tpe.org"
    const val DEFAULT_PORT     = 19132
    const val LOCAL_PROXY_PORT = 19150

    private val KEY_HOST    = stringPreferencesKey("server_host")
    private val KEY_PORT    = intPreferencesKey   ("server_port")
    private val KEY_RECENTS = stringPreferencesKey("server_recents")

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("ServerConfig.init() çağrılmamış!")

    val host: Flow<String>
        get() = try {
            safeCtx().dataStore.data.map { it[KEY_HOST] ?: DEFAULT_HOST }
        } catch (_: Exception) { flowOf(DEFAULT_HOST) }

    val port: Flow<Int>
        get() = try {
            safeCtx().dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }
        } catch (_: Exception) { flowOf(DEFAULT_PORT) }

    fun getHostBlocking(): String = try {
        runBlocking { safeCtx().dataStore.data.map { it[KEY_HOST] ?: DEFAULT_HOST }.first() }
    } catch (_: Exception) { DEFAULT_HOST }

    fun getPortBlocking(): Int = try {
        runBlocking { safeCtx().dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }.first() }
    } catch (_: Exception) { DEFAULT_PORT }

    suspend fun save(host: String, port: Int) {
        val h = host.trim()
        val p = port.coerceIn(1, 65535)
        try {
            safeCtx().dataStore.edit { prefs ->
                prefs[KEY_HOST] = h
                prefs[KEY_PORT] = p
            }
            addRecent(h, p)
        } catch (_: Exception) {}
    }

    suspend fun reset() {
        try {
            safeCtx().dataStore.edit { prefs ->
                prefs[KEY_HOST] = DEFAULT_HOST
                prefs[KEY_PORT] = DEFAULT_PORT
            }
        } catch (_: Exception) {}
    }

    private suspend fun addRecent(host: String, port: Int) {
        try {
            val existing = getRecentsBlocking().toMutableList()
            existing.removeAll { it.first == host && it.second == port }
            existing.add(0, host to port)
            val trimmed = existing.take(5)
            val json = JSONArray()
            trimmed.forEach { (h, p) ->
                json.put(JSONObject().apply { put("host", h); put("port", p) })
            }
            safeCtx().dataStore.edit { prefs -> prefs[KEY_RECENTS] = json.toString() }
        } catch (_: Exception) {}
    }

    fun getRecentsBlocking(): List<Pair<String, Int>> = try {
        val raw = runBlocking {
            safeCtx().dataStore.data.map { it[KEY_RECENTS] ?: "[]" }.first()
        }
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            obj.getString("host") to obj.getInt("port")
        }
    } catch (_: Exception) { emptyList() }

    val recents: Flow<List<Pair<String, Int>>>
        get() = try {
            safeCtx().dataStore.data.map { prefs ->
                try {
                    val raw = prefs[KEY_RECENTS] ?: "[]"
                    val arr = JSONArray(raw)
                    (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        obj.getString("host") to obj.getInt("port")
                    }
                } catch (_: Exception) { emptyList() }
            }
        } catch (_: Exception) { flowOf(emptyList()) }

    fun isValidHost(host: String): Boolean =
        host.isNotBlank() && host.length <= 255 && !host.contains(" ")

    fun isValidPort(port: Int): Boolean = port in 1..65535
}
