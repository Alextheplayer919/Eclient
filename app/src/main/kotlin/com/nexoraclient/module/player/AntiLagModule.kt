package com.rubidiumclient.module.player

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEvent2Packet
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket
import java.util.concurrent.atomic.AtomicLong

class AntiLagModule : BaseModule(
    name        = "AntiLag",
    category    = ModuleCategory.PLAYER,
    description = "Lag'a yol açan gereksiz paketleri filtreler: partiküller, ses, yükleme animasyonları"
) {

    private val dropParticles  = bool("Drop Particles",   true)
    private val dropSounds     = bool("Drop Sounds",      false)
    private val dropChunkExtra = bool("Drop Chunk Extra", true)
    private val maxChunkPerSec = int ("Max Chunks/s",     40, 5, 200)
    private val shortcut       = bool("Shortcut",         false)

    private val chunkTokens = AtomicLong(0L)
    private var lastChunkRefillMs = 0L

    override fun onEnable() {
        super.onEnable()
        chunkTokens.set(maxChunkPerSec.value.toLong())
        lastChunkRefillMs = System.currentTimeMillis()
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isServerToClient) return

        when (event.packet) {
            is SpawnParticleEffectPacket -> {
                if (dropParticles.value) event.cancel()
            }
            is LevelSoundEvent2Packet -> {
                if (dropSounds.value) event.cancel()
            }
            is PlaySoundPacket -> {
                if (dropSounds.value) event.cancel()
            }
            is LevelEventPacket -> {
                if (!dropParticles.value) return
                val typeName = (event.packet as LevelEventPacket).type?.toString() ?: return
                if (typeName.contains("PARTICLE") || typeName.contains("BLOCK_CRACK")
                    || typeName.contains("BREAK") || typeName.contains("EXPLODE")) {
                    event.cancel()
                }
            }
            is LevelChunkPacket -> {
                if (!dropChunkExtra.value) return
                refillChunkTokens()
                if (chunkTokens.decrementAndGet() < 0) {
                    chunkTokens.set(0)
                    event.cancel()
                }
            }
            else -> {}
        }
    }

    private fun refillChunkTokens() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastChunkRefillMs
        if (elapsed >= 1000L) {
            chunkTokens.set(maxChunkPerSec.value.toLong())
            lastChunkRefillMs = now
        }
    }
}
