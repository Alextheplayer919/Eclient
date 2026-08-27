package com.rubidiumclient.module.misc

import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import kotlin.random.Random

class ChatAdvertiser : BaseModule(
    name        = "ChatAdvertiser",
    category    = ModuleCategory.MISC,
    description = "Chat Spam (TPA/TpaHere/PVP/Ads)"
) {
    enum class Mode { TPA, TpaHere, PVP, Ads, Spammer }

    private val mode              = enum("Mode", Mode.PVP)
    private val shortcut          = bool("Shortcut", false)

    private val adsMessages = listOf(
        "> @here Use Best Mobile Client | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client v2.1",
        "> @here Best PvP Client for Bedrock | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client v2.1",
        "> @here Free Download Rubidium Client | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client v2.1",
        "> @here Join our Discord for updates | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client v2.1",
        "> @here Rubidium Client trusted by hundreds | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client v2.1"
    )

    private val pvpMessages = listOf(
        "> @here tpa pvp 1v1 little kiddos | %RANDOM% | Rubidium Client v2.1",
        "> @here 1v1 tpa pvp all ez | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa to pvp nns | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa for pvp all EZZ | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa 1v1 im bored fr | %RANDOM% | Rubidium Client v2.1",
        "> @here anyone tpa pvp cant be that scared | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa pvp free win here | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa 1v1 no crystal easy | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa pvp lets go who wants smoke | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa all cracked pvpers welcome | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa pvp best client wins obviously | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa 1v1 quick fight nobody scared right | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa pvp bring your best totem | %RANDOM% | Rubidium Client v2.1",
        "> @here tpa pvp all skill issue if you decline | %RANDOM% | Rubidium Client v2.1"
    )

    private val spammerMessages = listOf(
        "> Better than ur renamed client | Rubidium v2.1 | %RANDOM%",
        "> Why are u so ez  | Rubidium v2.1 | %RANDOM%",
        "> still a nn | Rubidium v2.1 | %RANDOM%",
        "> clean w keys clean hits | Rubidium v2.1 | %RANDOM%",
        "> Best mobile pvp client | Rubidium v2.1 | %RANDOM%",
        "> best pvp client on mobil users hand's down | Rubidium v2.1 | %RANDOM%",
        "> still ezz to me | Rubidium v2.1 | %RANDOM%",
        "> apollon players crying | Rubidium v2.1 | %RANDOM%",
        "> is lagback? still ez | Rubidium v2.1 | %RANDOM%",
        "> running this lobby with Rubidium | Rubidium v2.1 | %RANDOM%",
        "> do you think u can beat me with ur remodded client LOL | Rubidium v2.1 | %RANDOM%",
        "> tpa me if you think you can win | Rubidium v2.1 | %RANDOM%",
        "> dont cry kiddos | Rubidium v2.1 | %RANDOM%",
        "> rubidium better than ur cordlogger client  | Rubidium v2.1 | %RANDOM%",
        "> rubidium better than ur remodded client | Rubidium v2.1 | %RANDOM%",
        "> f c k ur ai client with rubidium | Rubidium v2.1 | %RANDOM%",
        "> u need more totem  | Rubidium v2.1 | %RANDOM%",
        "> Download rubidium and get best mobile experience  | Rubidium v2.1 | %RANDOM%",
        "> Rubidium users never lose to any mobile | Rubidium v2.1 | %RANDOM%",
        "> download rubidium and feel best mobil experience oday | Rubidium v2.1 | %RANDOM%"
    )

    private val junkChars = "abcdefghjklmnopqrstuvwxyz0123456789"
    private var tickJob: Job? = null
    private var lastSpammerMessageIndex = -1
    private var lastPvpMessageIndex = -1
    private var lastAdsMessageIndex = -1
    private var activeSession: RubidiumRelaySession? = null

    override fun onEnable() {
        super.onEnable()
        lastSpammerMessageIndex = -1
        lastPvpMessageIndex = -1
        lastAdsMessageIndex = -1

        val intervalMs = when (mode.value) {
            Mode.TPA      -> 2000L
            Mode.TpaHere  -> 2000L
            Mode.PVP      -> 22000L
            Mode.Ads      -> 10000L
            Mode.Spammer  -> 25000L
        }
        val action: () -> Unit = when (mode.value) {
            Mode.TPA      -> ::sendTpaCommand
            Mode.TpaHere  -> ::sendTpaHereCommand
            Mode.PVP      -> ::sendPvpMessage
            Mode.Ads      -> ::sendAdsMessage
            Mode.Spammer  -> ::sendSpammerMessage
        }

        tickJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                action()
                delay(intervalMs)
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session
    }

    private fun sendTpaCommand() {
        val session = activeSession ?: return
        val randomLetter = junkChars[Random.nextInt(junkChars.length)]
        val command = "/tpa $randomLetter"

        try {
            session.sendToServer(buildCommandPacket(command))
        } catch (e: Exception) {
        }
    }

    private fun sendTpaHereCommand() {
        val session = activeSession ?: return
        val randomLetter = junkChars[Random.nextInt(junkChars.length)]
        val command = "/tpahere $randomLetter"

        try {
            session.sendToServer(buildCommandPacket(command))
        } catch (e: Exception) {
        }
    }

    private fun sendPvpMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(pvpMessages.size)
        } while (index == lastPvpMessageIndex && pvpMessages.size > 1)
        lastPvpMessageIndex = index

        val message = pvpMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
    }

    private fun sendAdsMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(adsMessages.size)
        } while (index == lastAdsMessageIndex && adsMessages.size > 1)
        lastAdsMessageIndex = index

        val message = adsMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
    }

    private fun sendSpammerMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(spammerMessages.size)
        } while (index == lastSpammerMessageIndex && spammerMessages.size > 1)
        lastSpammerMessageIndex = index

        val message = spammerMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
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

    private fun buildCommandPacket(command: String): CommandRequestPacket = CommandRequestPacket().apply {
        this.command = command
        this.commandOriginData = CommandOriginData(
            CommandOriginType.PLAYER,
            UUID.randomUUID(),
            "",
            0L
        )
        isInternal = false
    }

    private fun randomJunk(): String {
        val len = Random.nextInt(12, 23)
        return buildString(len) {
            repeat(len) {
                append(junkChars[Random.nextInt(junkChars.length)])
            }
        }
    }
}