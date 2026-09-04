package com.rubidiumclient.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.BoolSetting
import com.rubidiumclient.module.EnumSetting
import com.rubidiumclient.module.FloatSetting
import com.rubidiumclient.module.IntSetting
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.StringSetting
import com.rubidiumclient.module.social.FriendManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_configs")

data class ConfigProfile(val name: String, val savedAt: Long)

/** Bir overlay elemanının ekrandaki x/y konumu. */
data class Pos(val x: Float, val y: Float)

/**
 * Overlay elemanlarının (totem, target, fab, shortcut butonları, komut kısayolları)
 * canlı konum durumu. OverlayService bunu okuyup sürüklerken günceller; Config,
 * profil kaydederken/yüklerken bunu JSON'a çevirir ya da JSON'dan doldurur.
 * Bu ayrım OverlayService ile Config'i birbirine sıkı bağımlı kılmadan haberleştirir.
 */
object OverlayPositions {
    var totem: Pos = Pos(16f, 16f)
    var target: Pos = Pos(16f, 64f)
    var fab: Pos = Pos(16f, 120f)
    var perfHud: Pos = Pos(24f, 100f)
    val shortcuts = mutableMapOf<String, Pos>()
    val commandShortcuts = mutableMapOf<String, Pos>()

    /** Config bir profil yüklediğinde tetiklenir; overlay açık halde konumlarını tazeleyebilsin diye. */
    var onChanged: (() -> Unit)? = null

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("totem", posToJson(totem))
        put("target", posToJson(target))
        put("fab", posToJson(fab))
        put("perfHud", posToJson(perfHud))
        put("shortcuts", mapToJson(shortcuts))
        put("commandShortcuts", mapToJson(commandShortcuts))
    }

    internal fun applyFrom(json: JSONObject?) {
        json ?: return
        posFromJson(json.optJSONObject("totem"))?.let { totem = it }
        posFromJson(json.optJSONObject("target"))?.let { target = it }
        posFromJson(json.optJSONObject("fab"))?.let { fab = it }
        posFromJson(json.optJSONObject("perfHud"))?.let { perfHud = it }
        applyMap(shortcuts, json.optJSONObject("shortcuts"))
        applyMap(commandShortcuts, json.optJSONObject("commandShortcuts"))
        onChanged?.invoke()
    }

    private fun applyMap(target: MutableMap<String, Pos>, json: JSONObject?) {
        json ?: return
        target.clear()
        json.keys().forEach { key -> posFromJson(json.optJSONObject(key))?.let { target[key] = it } }
    }

    private fun posToJson(pos: Pos): JSONObject =
        JSONObject().put("x", pos.x.toDouble()).put("y", pos.y.toDouble())

    private fun posFromJson(obj: JSONObject?): Pos? {
        obj ?: return null
        val x = obj.optDouble("x", Double.NaN)
        val y = obj.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) return null
        return Pos(x.toFloat(), y.toFloat())
    }

    private fun mapToJson(map: Map<String, Pos>): JSONObject =
        JSONObject().apply { map.forEach { (key, pos) -> put(key, posToJson(pos)) } }
}

object Config {

    private const val CONFIG_VERSION = 4 // v4: "server" (host/port) alanı eklendi
    private const val MAX_NAME_LENGTH = 40

    private val KEY_CONFIGS = stringPreferencesKey("config_profiles")

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("Config.init() çağrılmamış!")

    // ---- Profil listesi / aktif profil ----

    val profiles: Flow<List<ConfigProfile>>
        get() = try {
            safeCtx().configDataStore.data.map { prefs -> parseProfileList(prefs[KEY_CONFIGS]) }
        } catch (_: Exception) { flowOf(emptyList()) }

    val activeProfile: Flow<String?>
        get() = try {
            safeCtx().configDataStore.data.map { prefs -> readRoot(prefs[KEY_CONFIGS]).optString("active").ifBlank { null } }
        } catch (_: Exception) { flowOf(null) }

    fun getProfilesBlocking(): List<ConfigProfile> = try {
        val raw = runBlocking { safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first() }
        parseProfileList(raw)
    } catch (_: Exception) { emptyList() }

    suspend fun exists(name: String): Boolean = try {
        val raw = safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first()
        readRoot(raw).optJSONObject("profiles")?.has(name) == true
    } catch (_: Exception) { false }

    private fun parseProfileList(raw: String?): List<ConfigProfile> {
        val profilesJson = readRoot(raw).optJSONObject("profiles") ?: JSONObject()
        return profilesJson.keys().asSequence().map { name ->
            val entry = profilesJson.optJSONObject(name)
            ConfigProfile(name, entry?.optLong("savedAt", 0L) ?: 0L)
        }.sortedByDescending { it.savedAt }.toList()
    }

    private fun readRoot(raw: String?): JSONObject =
        try { JSONObject(raw ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun sanitizeName(name: String): String = name.trim().take(MAX_NAME_LENGTH)

    private fun profileEntry(state: JSONObject): JSONObject =
        JSONObject().put("savedAt", System.currentTimeMillis()).put("state", state)

    // ---- Kaydet / Yükle / Sil ----

    suspend fun save(name: String): Boolean {
        val trimmed = sanitizeName(name)
        if (trimmed.isBlank()) return false

        return try {
            val state = serializeCurrentState()
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

                profilesJson.put(trimmed, profileEntry(state))

                root.put("active", trimmed)
                prefs[KEY_CONFIGS] = root.toString()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun load(name: String): Boolean {
        return try {
            val raw = safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first()
            val root = readRoot(raw)
            val profilesJson = root.optJSONObject("profiles") ?: return false
            val entry = profilesJson.optJSONObject(name) ?: return false
            val state = entry.optJSONObject("state") ?: return false

            applyState(state)

            safeCtx().configDataStore.edit { prefs ->
                val r = readRoot(prefs[KEY_CONFIGS])
                r.put("active", name)
                prefs[KEY_CONFIGS] = r.toString()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun delete(name: String) {
        try {
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: return@edit
                profilesJson.remove(name)
                if (root.optString("active") == name) root.remove("active")
                prefs[KEY_CONFIGS] = root.toString()
            }
        } catch (_: Exception) {}
    }

    suspend fun rename(oldName: String, newName: String): Boolean {
        val trimmed = sanitizeName(newName)
        if (trimmed.isBlank() || trimmed == oldName) return false

        return try {
            var didRename = false
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: return@edit
                val entry = profilesJson.optJSONObject(oldName) ?: return@edit
                if (profilesJson.has(trimmed)) return@edit

                profilesJson.put(trimmed, entry)
                profilesJson.remove(oldName)
                if (root.optString("active") == oldName) root.put("active", trimmed)
                prefs[KEY_CONFIGS] = root.toString()
                didRename = true
            }
            didRename
        } catch (_: Exception) { false }
    }

    // ---- Modül durumunu JSON'a çevir / JSON'dan uygula ----

    private fun serializeCurrentState(): JSONObject {
        val modulesJson = JSONObject()

        ModuleManager.getAll().forEach { module ->
            val moduleJson = JSONObject()
            moduleJson.put("enabled", module.isEnabled)

            val settingsJson = JSONObject()
            module.settings.forEach { setting ->
                val value: Any = when (setting) {
                    is BoolSetting    -> setting.value
                    is IntSetting     -> setting.value
                    is FloatSetting   -> setting.value.toDouble()
                    is StringSetting  -> setting.value
                    is EnumSetting<*> -> setting.value.name
                    else              -> return@forEach
                }
                settingsJson.put(setting.name, value)
            }
            moduleJson.put("settings", settingsJson)
            modulesJson.put(module.name, moduleJson)
        }

        val root = JSONObject()
        root.put("version", CONFIG_VERSION)
        root.put("modules", modulesJson)
        root.put("positions", OverlayPositions.toJson())
        root.put("friends", JSONArray(FriendManager.getAll()))
        root.put("server", JSONObject().apply {
            put("host", ServerConfig.getHostBlocking())
            put("port", ServerConfig.getPortBlocking())
        })
        return root
    }

    private suspend fun applyState(root: JSONObject) {
        val modulesJson = root.optJSONObject("modules")
        if (modulesJson != null) {
            ModuleManager.getAll().forEach { module ->
                val moduleJson = modulesJson.optJSONObject(module.name) ?: return@forEach
                applySettingsTo(module, moduleJson.optJSONObject("settings"))

                val shouldEnable = moduleJson.optBoolean("enabled", module.isEnabled)
                if (shouldEnable != module.isEnabled) {
                    if (shouldEnable) ModuleManager.enable(module) else ModuleManager.disable(module)
                }
            }
        }
        // Eski (v1/v2) profillerde "positions"/"friends" yok — bu durumda mevcut
        // konumlar ve arkadaş listesi korunur (silinmez).
        OverlayPositions.applyFrom(root.optJSONObject("positions"))

        val friendsJson = root.optJSONArray("friends")
        if (friendsJson != null) {
            val names = (0 until friendsJson.length()).map { i -> friendsJson.optString(i) }
            FriendManager.setAll(names)
        }

        // Eski profillerde "server" yoksa mevcut host/port korunur.
        val serverJson = root.optJSONObject("server")
        if (serverJson != null) {
            val host = serverJson.optString("host", ServerConfig.DEFAULT_HOST)
            val port = serverJson.optInt("port", ServerConfig.DEFAULT_PORT)
            if (ServerConfig.isValidHost(host) && ServerConfig.isValidPort(port)) {
                ServerConfig.save(host, port)
            }
        }
    }

    private fun applySettingsTo(module: BaseModule, settingsJson: JSONObject?) {
        settingsJson ?: return
        module.settings.forEach { setting ->
            if (!settingsJson.has(setting.name)) return@forEach
            try {
                when (setting) {
                    is BoolSetting    -> setting.value = settingsJson.getBoolean(setting.name)
                    is IntSetting     -> setting.value = settingsJson.getInt(setting.name).coerceIn(setting.min, setting.max)
                    is FloatSetting   -> setting.value = settingsJson.getDouble(setting.name).toFloat().coerceIn(setting.min, setting.max)
                    is StringSetting  -> setting.value = settingsJson.getString(setting.name)
                    is EnumSetting<*> -> setting.setByName(settingsJson.getString(setting.name))
                    else              -> Unit
                }
            } catch (_: Exception) {}
        }
    }

    // ---- Dışa aktarma (indirilebilir dosya) / İçe aktarma (daha önce indirilmiş dosya) ----

    /**
     * Kayıtlı bir profili, kullanıcının cihaza dosya olarak kaydedebileceği
     * (paylaşabileceği / yedekleyebileceği) bir JSON string'ine çevirir.
     */
    suspend fun exportJson(name: String): String? {
        return try {
            val raw = safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first()
            val root = readRoot(raw)
            val profilesJson = root.optJSONObject("profiles") ?: return null
            val entry = profilesJson.optJSONObject(name) ?: return null
            val state = entry.optJSONObject("state") ?: return null

            val export = JSONObject()
            export.put("rubidiumclientConfig", true)
            export.put("name", name)
            export.put("exportedAt", System.currentTimeMillis())
            export.put("state", state)
            export.toString(2)
        } catch (_: Exception) { null }
    }

    /**
     * Daha önce exportJson ile indirilmiş bir dosyanın içeriğini (veya doğrudan
     * "modules" içeren ham bir state JSON'unu) yeni bir profil olarak kaydeder.
     * @return kaydedilen profilin adı, başarısızsa null
     */
    suspend fun importJson(json: String, nameOverride: String? = null): String? {
        return try {
            val parsed = JSONObject(json)
            // Hem bizim export formatımızı ("state" içinde) hem de doğrudan
            // ham state JSON'unu (modules en üst seviyede) kabul et.
            val state = if (parsed.has("state")) parsed.getJSONObject("state") else parsed
            if (!state.has("modules")) return null // geçerli bir RubidiumClient config'i değil

            var name = (nameOverride?.let { sanitizeName(it) }?.takeIf { it.isNotEmpty() })
                ?: sanitizeName(parsed.optString("name"))
            if (name.isBlank()) name = "Imported ${System.currentTimeMillis()}"

            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

                profilesJson.put(name, profileEntry(state))

                root.put("active", name)
                prefs[KEY_CONFIGS] = root.toString()
            }
            name
        } catch (_: Exception) { null }
    }
}
