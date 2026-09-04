package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

class ElytraFly : BaseModule(
    name        = "ElytraFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Roketsiz surekli elytra plan/ucus"
) {
    private val glideSpeed    = float("Forward Speed",           0.9f, 0.1f, 3f)
    private val thrustSpeed   = float("Ascend Thrust",           1.2f, 0.1f, 3f)
    private val requireJump   = bool ("Require Jump To Ascend",  true)
    private val requireElytra = bool ("Require Elytra Equipped", true)
    private val durabilityThreshold = int("Elytra Durability Threshold", 100, 1, 431)
    private val thrustInterval = int  ("Thrust Delay",           55,   15, 150)
    private val shortcut      = bool ("Shortcut",                false)

    companion object {
        // Vanilla elytra max durability
        private const val ELYTRA_MAX_DURABILITY = 432
        private const val DURABILITY_CHECK_MS   = 300L
        private const val SWAP_COOLDOWN_MS      = 300L
    }

    @Volatile private var lastThrustMs = 0L
    @Volatile private var glideStarted = false
    @Volatile private var durabilityTickJob: kotlinx.coroutines.Job? = null
    @Volatile private var chestplateItem: ItemData? = null
    private var lastSwapMs = 0L

    override fun onEnable() {
        super.onEnable()
        glideStarted = false
        chestplateItem = null
        lastThrustMs = 0L
        startGlide()
        durabilityTickJob = launchTickLoop(DURABILITY_CHECK_MS) { checkElytraDurability() }
    }

    override fun onDisable() {
        durabilityTickJob?.cancel()
        durabilityTickJob = null
        stopGlide()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                if (pkt.containerId == ContainerId.ARMOR) {
                    val item = pkt.contents.getOrNull(InventoryUtil.ArmorSlotType.CHESTPLATE.slotIndex)
                    chestplateItem = item?.takeUnless { InventoryUtil.isEmpty(it) }
                }
            }
            is InventorySlotPacket -> {
                if (pkt.containerId == ContainerId.ARMOR &&
                    pkt.slot == InventoryUtil.ArmorSlotType.CHESTPLATE.slotIndex
                ) {
                    chestplateItem = pkt.item.takeUnless { InventoryUtil.isEmpty(it) }
                }
            }
            is PlayerAuthInputPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

                if (requireElytra.value && !hasElytraEquipped()) {
                    if (glideStarted) stopGlide()
                    return
                }
                if (!glideStarted) startGlide()

                val boosting = pkt.inputData.contains(PlayerAuthInputData.JUMPING) ||
                               pkt.inputData.contains(PlayerAuthInputData.WANT_UP)

                if (requireJump.value && !boosting) return

                // FIX: throttle yoktu - boost basılı tutulurken HER
                // PlayerAuthInputPacket'te (saniyede onlarca kez) bir motion
                // paketi gönderiliyordu (Jetpack'teki aynı eksiklik).
                val now = System.currentTimeMillis()
                if (now - lastThrustMs < thrustInterval.value) return
                lastThrustMs = now

                val pitch = Math.toRadians(pkt.rotation.x.toDouble()).toFloat()
                val yaw   = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
                val cosPitch = cos(pitch)

                val dirX = -sin(yaw) * cosPitch
                val dirZ =  cos(yaw) * cosPitch
                // Dikey bileseni pitch'e tam bagimli birakmiyoruz — asagi bakarken
                // yere kazik gibi dalmasin diye sabit hafif bir yukari bilesen var.
                val liftY = -sin(pitch) * 0.3f + 0.25f

                event.session.clientBound(SetEntityMotionPacket().apply {
                    runtimeEntityId = EntityTracker.selfRuntimeId
                    motion = Vector3f.from(
                        dirX * glideSpeed.value,
                        liftY * thrustSpeed.value,
                        dirZ * glideSpeed.value
                    )
                })
            }
        }
    }

    private fun startGlide() {
        val session = PacketEventBus.currentSession ?: return
        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.START_GLIDE
        })
        spoofGlideFlag(session, true)
        glideStarted = true
    }

    private fun stopGlide() {
        val session = PacketEventBus.currentSession ?: return
        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.STOP_GLIDE
        })
        spoofGlideFlag(session, false)
        glideStarted = false
    }

    private fun spoofGlideFlag(session: RubidiumRelaySession, gliding: Boolean) {
        session.clientBound(SetEntityDataPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            metadata.setFlag(EntityFlag.GLIDING, gliding)
        })
    }

    // Zirh EntityTracker'da tutulmuyor — AutoArmor.kt/AutoTotem.kt'deki gibi,
    // ARMOR container'indan gelen paketleri dinleyerek chestplateItem'i
    // kendimiz guncel tutuyoruz; chestplate slotunda gercekten elytra var mi
    // diye ona bakiyoruz.
    private fun hasElytraEquipped(): Boolean {
        val item = chestplateItem ?: return false
        return InventoryUtil.resolveIdentifier(item) == "minecraft:elytra"
    }

    // Üstteki elytranın DAYANIKLILIĞI (can/durability — oyuncunun kendi canı
    // değil, item'in kalan kullanım ömrü) eşiğin altına düşünce, envanterdeki
    // daha yüksek dayanıklılığa sahip başka bir elytra varsa onunla değiştirir.
    // AutoArmor.kt'deki tier-swap mantığının aynısı, tier yerine kalan
    // durability kullanılıyor.
    private fun checkElytraDurability() {
        val session = PacketEventBus.currentSession ?: return
        val chest = chestplateItem ?: return
        val chestId = InventoryUtil.resolveIdentifier(chest) ?: return
        if (chestId != "minecraft:elytra") return

        val currentDurability = ELYTRA_MAX_DURABILITY - chest.damage
        if (currentDurability >= durabilityThreshold.value) return

        var bestSlot = -1
        var bestItem: ItemData? = null
        var bestDurability = currentDurability

        for ((slot, item) in EntityTracker.getInventorySnapshot()) {
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
            if (id != "minecraft:elytra") continue
            val durability = ELYTRA_MAX_DURABILITY - item.damage
            if (durability > bestDurability) {
                bestDurability = durability
                bestSlot = slot
                bestItem = item
            }
        }
        if (bestSlot < 0 || bestItem == null) return

        val now = System.currentTimeMillis()
        if (now - lastSwapMs < SWAP_COOLDOWN_MS) return
        lastSwapMs = now

        InventoryUtil.sendInventoryMove(
            session           = session,
            sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceContainerId = 0,
            sourceSlot        = bestSlot,
            sourceItem        = bestItem,
            destContainer     = ContainerSlotType.ARMOR,
            destContainerId   = ContainerId.ARMOR,
            destSlot          = InventoryUtil.ArmorSlotType.CHESTPLATE.slotIndex,
            destItem          = chest
        )
    }
}
