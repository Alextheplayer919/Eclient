package com.rubidiumclient.module.social

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.social.FriendLibrary

/**
 * Combat modules use this extension to check if a target is a friend.
 * Delegates to FriendLibrary (the authoritative global list).
 *
 * Usage in KillAura, TPAura, etc:
 *   .filterNot { it.isFriendEntity }
 */
val EntityTracker.TrackedEntity.isFriendEntity: Boolean
    get() = isPlayer && name.isNotBlank() && FriendLibrary.isFriend(name)
