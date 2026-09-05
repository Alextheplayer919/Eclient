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
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PacketCollector : BaseModule(
    name        = "PacketCollector",
    category    = ModuleCategory.MISC,
    description = "Detailed packet logger – separate files per packet type"
), PacketEventBus.PacketListener {

    private val logDetailed     = bool("Detailed Log",       true)
    private val separateFiles   = bool("Separate Files",     true)
    private val excludeSpam     = bool("Exclude Spam",       true)
    private val maxLinesPerFile = int ("Max Lines/File",     50000, 1000, 200000)
    private val autoFlush       = bool("Auto Flush",         true)
    private val flushInterval   = int ("Flush Interval (ms)",5000,  1000, 30000)

    private val baseDir by lazy { getLogDirectory() }
    private val writers = ConcurrentHashMap<String, PrintWriter>()
    private val lineCounts = ConcurrentHashMap<String, Int>()
    private var flushJob: Job? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val spamPackets = setOf(
        "MoveEntityAbsolutePacket",
        "MoveEntityDeltaPacket",
        "UpdateAttributesPacket",
        "LevelEventPacket"
    )

    override fun onEnable() {
        super.onEnable()
        try {
            File(baseDir).mkdirs()
            PacketEventBus.register(this)
            if (autoFlush.value) {
                flushJob = writeScope.launch {
                    while (isActive) {
                        delay(flushInterval.value.toLong())
                        writers.values.forEach { it.flush() }
                    }
                }
            }
        } catch (e: Exception) {
            println("PacketCollector: Failed to init: ${e.message}")
            setEnabled(false)
        }
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        flushJob?.cancel()
        writeScope.launch {
            writers.values.forEach { it.close() }
            writers.clear()
            lineCounts.clear()
        }
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        val className = pkt.javaClass.simpleName

        if (excludeSpam.value && spamPackets.contains(className)) return

        val dir = if (event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) "CLIENT→SERVER" else "SERVER→CLIENT"
        val ts = dateFormat.format(Date())
        val details = if (logDetailed.value) buildDetailedSummary(pkt) else ""

        val line = buildString {
            append("[")
            append(ts)
            append("] ")
            append(dir)
            append(" | ")
            append(className)
            if (details.isNotEmpty()) {
                append(" | ")
                append(details)
            }
        }

        val fileName = if (separateFiles.value) "$className.txt" else "packet_log.txt"
        writeToFile(fileName, line)
    }

    private fun writeToFile(fileName: String, line: String) {
        writeScope.launch {
            val writer = writers.computeIfAbsent(fileName) {
                val file = File(baseDir, fileName)
                PrintWriter(FileWriter(file, true), true)
            }
            writer.println(line)
            val count = lineCounts.merge(fileName, 1, Int::plus) ?: 1
            if (count % 10 == 0) writer.flush()
            if (count >= maxLinesPerFile.value) {
                writer.println("!!! Max lines reached, rotating log !!!")
                writer.flush()
                writer.close()
                val rotatedName = fileName.replace(".txt", "_${System.currentTimeMillis()}.txt")
                val newWriter = PrintWriter(FileWriter(File(baseDir, rotatedName), true), true)
                writers[fileName] = newWriter
                lineCounts[fileName] = 0
            }
        }
    }

    private fun buildDetailedSummary(pkt: BedrockPacket): String {
        return when (pkt) {
            is MovePlayerPacket -> {
                val pos = pkt.position
                val rot = pkt.rotation
                "pos=(${pos.x}, ${pos.y}, ${pos.z}) | rot=(${rot.x}, ${rot.y}, ${rot.z}) | mode=${pkt.mode} | onGround=${pkt.isOnGround} | eid=${pkt.runtimeEntityId}"
            }
            is PlayerAuthInputPacket -> {
                val pos = pkt.position
                val rot = pkt.rotation
                val motion = pkt.motion
                "pos=(${pos.x}, ${pos.y}, ${pos.z}) | rot=(${rot.x}, ${rot.y}, ${rot.z}) | motion=(${motion.x}, ${motion.y}) | tick=${pkt.tick} | inputData=${pkt.inputData.joinToString()}"
            }
            is SetEntityMotionPacket -> {
                val motion = pkt.motion
                "eid=${pkt.runtimeEntityId} | motion=(${motion.x}, ${motion.y}, ${motion.z})"
            }
            is UpdateAbilitiesPacket -> {
                "perm=${pkt.playerPermission} | cmd=${pkt.commandPermission} | layers=${pkt.abilityLayers.size}"
            }
            is MoveEntityAbsolutePacket -> {
                val pos = pkt.position
                "eid=${pkt.runtimeEntityId} | pos=(${pos.x}, ${pos.y}, ${pos.z}) | rot=(${pkt.rotation.x}, ${pkt.rotation.y})"
            }
            is TextPacket -> {
                "type=${pkt.type} | message=${pkt.message.take(50)}"
            }
            is LevelChunkPacket -> {
                "chunkX=${pkt.chunkX} | chunkZ=${pkt.chunkZ}"
            }
            is UpdateBlockPacket -> {
                "pos=(${pkt.blockPosition.x}, ${pkt.blockPosition.y}, ${pkt.blockPosition.z}) | flags=${pkt.flags}"
            }
            is AddEntityPacket -> {
                "eid=${pkt.runtimeEntityId} | type=${pkt.entityType} | pos=(${pkt.position.x}, ${pkt.position.y}, ${pkt.position.z}) | rot=(${pkt.rotation.x}, ${pkt.rotation.y})"
            }
            is RemoveEntityPacket -> {
                // ✅ FIXED: use runtimeEntityId (not entityId)
                "eid=${pkt.runtimeEntityId}"
            }
            is SetEntityDataPacket -> {
                "eid=${pkt.runtimeEntityId} | metadata size=${pkt.metadata.size}"
            }
            else -> ""
        }
    }

    private fun getLogDirectory(): String {
        if (File("/storage/emulated/0").exists()) {
            return "/storage/emulated/0/Download/packet_logs"
        }
        val userHome = System.getProperty("user.home")
        return if (System.getProperty("os.name").startsWith("Windows")) {
            "$userHome\\Downloads\\packet_logs"
        } else {
            "$userHome/Downloads/packet_logs"
        }
    }
}
