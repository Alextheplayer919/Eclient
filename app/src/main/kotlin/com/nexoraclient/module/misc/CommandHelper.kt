package com.rubidiumclient.module.misc

import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

class CommandHelper : BaseModule(
    name        = "CommandHelper",
    category    = ModuleCategory.MISC,
    description = "Kaydettiğin komut veya yazıları tek dokunuşla chate gönder"
) {
    companion object {
        private const val ENTRY_DELIMITER = "||"
    }

    private val entriesSetting = string("Entries", "")
    private val shortcut       = bool  ("Shortcut", false)

    val entries: List<String>
        get() = entriesSetting.value
            .split(ENTRY_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalize(text: String): String =
        text.trim()
            .replace(ENTRY_DELIMITER, "|")
            .replace(Regex("\\s+"), " ")

    fun addEntry(text: String): Boolean {
        val trimmed = normalize(text)
        if (trimmed.isEmpty()) return false
        val current = entries
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        entriesSetting.value = (current + trimmed).joinToString(ENTRY_DELIMITER)
        return true
    }

    fun removeEntry(text: String) {
        val target = normalize(text)
        entriesSetting.value = entries.filterNot { it.equals(target, ignoreCase = true) }.joinToString(ENTRY_DELIMITER)
    }

    fun removeEntryAt(index: Int) {
        val current = entries
        if (index !in current.indices) return
        entriesSetting.value = current.filterIndexed { i, _ -> i != index }.joinToString(ENTRY_DELIMITER)
    }

    fun clearEntries() {
        entriesSetting.value = ""
    }

    enum class SendResult { SENT, EMPTY, NO_SESSION, FAILED }

    fun send(text: String): SendResult {
        val message = text.trim()
        if (message.isEmpty()) return SendResult.EMPTY
        val session = PacketEventBus.currentSession ?: return SendResult.NO_SESSION
        return runCatching { session.sendToServer(buildTextPacket(message)) }
            .fold({ SendResult.SENT }, { SendResult.FAILED })
    }

    fun sendAt(index: Int): SendResult {
        val current = entries
        if (index !in current.indices) return SendResult.EMPTY
        return send(current[index])
    }

    private fun buildTextPacket(message: String): TextPacket = TextPacket().apply {
        type               = TextPacket.Type.CHAT
        isNeedsTranslation = false
        sourceName         = "__ox_internal__"
        xuid               = ""
        platformChatId     = ""
        setMessage(message)
        setFilteredMessage("")
    }
}
