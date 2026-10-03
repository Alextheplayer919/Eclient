package com.rubidiumclient.core.commands

import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.core.social.FriendLibrary
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Helper: unified friend command dispatch with automatic whisper notifications.
 * Ensures every combat module and the chat system stay in sync.
 */
object FriendCommandHelper {

    fun addFriend(session: RubidiumRelaySession, name: String): Boolean {
        val changed = FriendLibrary.addFriend(name)
        if (changed) {
            FriendLibrary.whisper(session, name, FriendLibrary.msgAdd)
        }
        return changed
    }

    fun removeFriend(session: RubidiumRelaySession, name: String): Boolean {
        val changed = FriendLibrary.removeFriend(name)
        if (changed) {
            FriendLibrary.whisper(session, name, FriendLibrary.msgRemove)
        }
        return changed
    }

    fun addEnemy(session: RubidiumRelaySession, name: String): Boolean {
        return FriendLibrary.addEnemy(name)
    }

    fun removeEnemy(session: RubidiumRelaySession, name: String): Boolean {
        return FriendLibrary.removeEnemy(name)
    }
}
