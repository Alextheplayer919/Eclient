package com.rubidiumclient.module.social

import android.content.Context
import android.content.SharedPreferences
import com.rubidiumclient.core.proxy.EntityTracker

/**
 * Arkadaş listesi yönetimi.
 * İsimler case-insensitive olarak lowercase saklanır.
 * SharedPreferences ile kalıcıdır (uygulama yeniden açılınca korunur).
 *
 * Kullanım: Application/MainActivity onCreate içinde bir kere:
 *   FriendManager.init(applicationContext)
 */
object FriendManager {

    private const val PREFS_NAME = "ox_friends"
    private const val KEY_FRIENDS = "friend_names"

    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false

    private val friends = linkedSetOf<String>()

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_FRIENDS, emptySet()) ?: emptySet()
        synchronized(friends) {
            friends.clear()
            friends.addAll(saved.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        }
        initialized = true
    }

    fun addFriend(name: String): Boolean {
        val key = normalize(name) ?: return false
        val added = synchronized(friends) { friends.add(key) }
        if (added) persist()
        return added
    }

    fun removeFriend(name: String): Boolean {
        val key = normalize(name) ?: return false
        val removed = synchronized(friends) { friends.remove(key) }
        if (removed) persist()
        return removed
    }

    /** Yoksa ekler, varsa çıkarır. Sonuç: işlem sonrası arkadaş mı değil mi. */
    fun toggleFriend(name: String): Boolean {
        val key = normalize(name) ?: return false
        val nowFriend = synchronized(friends) {
            if (friends.contains(key)) {
                friends.remove(key)
                false
            } else {
                friends.add(key)
                true
            }
        }
        persist()
        return nowFriend
    }

    fun isFriend(name: String): Boolean {
        val key = normalize(name) ?: return false
        return synchronized(friends) { friends.contains(key) }
    }

    fun getAll(): List<String> = synchronized(friends) { friends.toList().sorted() }

    fun count(): Int = synchronized(friends) { friends.size }

    /**
     * Mevcut listeyi tamamen verilen isimlerle değiştirir (Config profil
     * yükleme/import için). addFriend()'i döngüyle çağırmaktan farklı olarak
     * tek seferde persist eder, isim başına disk yazımı yapmaz.
     */
    fun setAll(names: Collection<String>) {
        synchronized(friends) {
            friends.clear()
            names.mapNotNullTo(friends) { normalize(it) }
        }
        persist()
    }

    fun clear() {
        synchronized(friends) { friends.clear() }
        persist()
    }

    private fun normalize(name: String): String? {
        val key = name.trim().lowercase()
        return if (key.isEmpty()) null else key
    }

    private fun persist() {
        if (!initialized) return
        prefs.edit().putStringSet(KEY_FRIENDS, synchronized(friends) { friends.toSet() }).apply()
    }
}

/**
 * TrackedEntity için kısayol: bu entity bir oyuncuysa ve ismi arkadaş listesindeyse true.
 * KillAura / TPAura gibi modüllerde `.filterNot { it.isFriendEntity }` şeklinde kullanılır.
 */
val EntityTracker.TrackedEntity.isFriendEntity: Boolean
    get() = isPlayer && name.isNotBlank() && FriendManager.isFriend(name)
