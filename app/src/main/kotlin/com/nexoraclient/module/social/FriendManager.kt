package com.rubidiumclient.module.social

import android.content.Context
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.social.FriendLibrary

/**
 * UI/config-facing compatibility wrapper around FriendLibrary.
 *
 * The project used a FriendManager symbol in the overlay and config code, but the
 * actual implementation lived in FriendLibrary. Keep a stable FriendManager API so
 * view code and persistence layers compile without changing all call sites.
 */
object FriendManager {
    fun init(context: Context) {
        // FriendLibrary is application-scoped and does not need a Context today,
        // but preserving this API keeps startup code and older call sites stable.
        FriendLibrary.load()
    }

    fun getAll(): List<String> = FriendLibrary.friendList()

    fun addFriend(name: String): Boolean = FriendLibrary.addFriend(name)

    fun removeFriend(name: String): Boolean = FriendLibrary.removeFriend(name)

    fun clear() {
        val names = FriendLibrary.friendList()
        for (name in names) {
            FriendLibrary.removeFriend(name)
        }
    }

    fun setAll(names: List<String>) {
        val existing = FriendLibrary.friendList()
        for (name in existing) {
            FriendLibrary.removeFriend(name)
        }
        names
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { FriendLibrary.addFriend(it) }
    }

    fun contains(name: String?): Boolean = FriendLibrary.isFriend(name)

    fun isFriend(name: String?): Boolean = FriendLibrary.isFriend(name)

    fun save() = FriendLibrary.save()
}

/**
 * Combat modules use this extension to check if a target is a friend.
 * Delegates to FriendLibrary (the authoritative global list).
 *
 * Usage in KillAura, TPAura, etc:
 *   .filterNot { it.isFriendEntity }
 */
val EntityTracker.TrackedEntity.isFriendEntity: Boolean
    get() = isPlayer && name.isNotBlank() && FriendLibrary.isFriend(name)
