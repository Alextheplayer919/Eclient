package com.rubidiumclient.module.misc

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PacketCollector : BaseModule(
    name        = "PacketCollector",
    category    = ModuleCategory.MISC,
    description = "Tüm paketleri Downloads klasörüne log'lar"
), PacketEventBus.PacketListener {

    // ── Settings ──────────────────────────────────
    private val logDetailed   = bool("Detailed Log",     true)
    private val maxLines      = int ("Max Lines",        50000, 1000, 200000)
    private val autoFlush     = bool("Auto Flush",       true)
    private val flushInterval = int ("Flush Interval",   5000,  1000, 30000)

    // ── Path ──────────────────────────────────────
    private val logPath by lazy { getDefaultLogPath() }

    // ── State ─────────────────────────────────────
    private var writer: PrintWriter? = null
    private var lineCount = 0
    private var flushJob: Job? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        super.onEnable()
        try {
            val file = File(logPath)
            file.parentFile?.mkdirs()
            writer = PrintWriter(FileWriter(file, true), true)
            writer?.println("=== Packet Log started at ${Date()} ===")
            writer?.flush()
            lineCount = 0
            PacketEventBus.register(this)

            if (autoFlush.value) {
                flushJob = writeScope.launch {
                    while (isActive) {
                        delay(flushInterval.value.toLong())
                        writer?.flush()
                    }
                }
            }
        } catch (e: Exception) {
            println("PacketCollector: Failed to open log file: ${e.message}")
            setEnabled(false)
        }
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        flushJob?.cancel()
        writeScope.launch {
            writer?.apply {
                println("=== Packet Log ended at ${Date()} ===")
                flush()
                close()
            }
            writer = null
        }
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || writer == null) return

        val pkt = event.packet
        val dir = if (event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) ">>>" else "<<<"
        val ts = dateFormat.format(Date())
        val className = pkt.javaClass.simpleName

        val summary = if (logDetailed.value) {
            buildPacketSummary(pkt)
        } else {
            ""
        }

        val line = "[$ts] $dir $className$summary"
        writeLine(line)
    }

    private fun writeLine(line: String) {
        writeScope.launch {
            writer?.println(line)
            lineCount++
            if (lineCount % 10 == 0) {
                writer?.flush()
            }
            if (lineCount >= maxLines.value) {
                writer?.println("!!! Max lines reached, rotating log !!!")
                writer?.flush()
                lineCount = 0
            }
        }
    }

    private fun buildPacketSummary(pkt: BedrockPacket): String {
        return when (pkt) {
            is MovePlayerPacket -> {
                val pos = pkt.position
                val rot = pkt.rotation
                " pos=(${pos.x}, ${pos.y}, ${pos.z}) rot=(${rot.x}, ${rot.y}, ${rot.z}) mode=${pkt.mode}"
            }
            is PlayerAuthInputPacket -> {
                val pos = pkt.position
                val rot = pkt.rotation
                val motion = pkt.motion
                val tick = pkt.tick
                " pos=(${pos.x}, ${pos.y}, ${pos.z}) rot=(${rot.x}, ${rot.y}, ${rot.z}) motion=(${motion.x}, ${motion.y}) tick=$tick"
            }
            is SetEntityMotionPacket -> {
                val motion = pkt.motion
                " eid=${pkt.runtimeEntityId} motion=(${motion.x}, ${motion.y}, ${motion.z})"
            }
            is UpdateAbilitiesPacket -> {
                " perm=${pkt.playerPermission} cmd=${pkt.commandPermission} layers=${pkt.abilityLayers.size}"
            }
            else -> ""
        }
    }

    private fun getDefaultLogPath(): String {
        val androidDownload = "/storage/emulated/0/Download/packet_log.txt"
        if (File("/storage/emulated/0").exists()) {
            return androidDownload
        }
        val userHome = System.getProperty("user.home")
        return if (System.getProperty("os.name").startsWith("Windows")) {
            "$userHome\\Downloads\\packet_log.txt"
        } else {
            "$userHome/Downloads/packet_log.txt"
        }
    }
}
