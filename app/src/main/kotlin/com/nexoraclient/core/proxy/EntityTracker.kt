package com.rubidiumclient.core.proxy

import com.rubidiumclient.auth.AccountManager
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.utils.MathUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object EntityTracker : PacketEventBus.PacketListener {

    private const val TAG = "EntityTracker"

    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class EntityType { PLAYER, MONSTER, ANIMAL, PASSIVE, PROJECTILE, ITEM, CRYSTAL, UNKNOWN }

    data class TrackedEntity(
        val runtimeId    : Long,
        val uniqueId     : Long,
        val identifier   : String,
        val type         : EntityType,
        var x            : Float,
        var y            : Float,
        var z            : Float,
        var yaw          : Float   = 0f,
        var pitch        : Float   = 0f,
        var headYaw      : Float   = 0f,
        var velX         : Float   = 0f,
        var velY         : Float   = 0f,
        var velZ         : Float   = 0f,
        var health       : Float   = 20f,
        var maxHealth    : Float   = 20f,
        var absorbHealth : Float   = 0f,
        var armorValue   : Float   = 0f,
        var movSpeed     : Float   = 0.1f,
        var attackDmg    : Float   = 2f,
        var isOnGround   : Boolean = true,
        var isRiding     : Boolean = false,
        var ridingId     : Long    = 0L,
        var isSneaking   : Boolean = false,
        var isSprinting  : Boolean = false,
        var isInvisible  : Boolean = false,
        var name         : String  = "",
        var pingMs       : Int     = 0,
        val spawnTime    : Long    = System.currentTimeMillis(),
        var lastUpdateMs : Long    = System.currentTimeMillis(),
        var prevX        : Float   = 0f,
        var prevY        : Float   = 0f,
        var prevZ        : Float   = 0f,
        var hurtTime     : Int     = 0,
        var lastHurtMs   : Long    = 0L,
        var deathAnim    : Boolean = false,
        var offHandItem  : ItemData? = null,
        var mainHandItem : ItemData? = null,
        var helmetItem   : ItemData? = null,
        var chestplateItem: ItemData? = null,
        var leggingsItem : ItemData? = null,
        var bootsItem    : ItemData? = null
    ) {
        val speedXZ     : Float   get() = MathUtil.dist2(x, z, prevX, prevZ)
        val isMoving    : Boolean get() = speedXZ > 0.01f
        val isCrystal   : Boolean get() = type == EntityType.CRYSTAL || identifier.contains("crystal", ignoreCase = true)
        val isPlayer    : Boolean get() = type == EntityType.PLAYER
        val isHostile   : Boolean get() = type == EntityType.MONSTER
        val healthPercent: Float  get() = if (maxHealth > 0f) health / maxHealth else 0f
        fun predictedPosition(t: Float) = Triple(x + velX * t, y + velY * t, z + velZ * t)
    }

    private val entities        = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val playerNames     = ConcurrentHashMap<Long, String>()

    private val playerCounter  = java.util.concurrent.atomic.AtomicInteger(0)
    private val hostileCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private fun trackAdd(e: TrackedEntity) {
        if (e.isPlayer) playerCounter.incrementAndGet()
        else if (e.isHostile) hostileCounter.incrementAndGet()
    }

    private fun trackRemove(e: TrackedEntity) {
        if (e.isPlayer) playerCounter.decrementAndGet()
        else if (e.isHostile) hostileCounter.decrementAndGet()
    }

    private val selfInventory = ConcurrentHashMap<Int, ItemData>()
    private val selfArmor     = ConcurrentHashMap<Int, ItemData>()

    private val netIdCache = ConcurrentHashMap<Int, ItemData>()

    private fun hasResolvableDefinition(item: ItemData): Boolean {
        val id = runCatching { item.definition?.identifier }.getOrElse { null }
        return !id.isNullOrBlank()
    }

    private fun cacheByNetId(item: ItemData?) {
        if (item == null || isEmptyItem(item)) return
        if (!hasResolvableDefinition(item)) return
        val netId = item.netId
        if (netId != 0) netIdCache[netId] = item
    }

    private fun resolveFull(item: ItemData?): ItemData? {
        if (item == null) return null
        if (isEmptyItem(item)) return item
        if (hasResolvableDefinition(item)) return item
        val cached = netIdCache[item.netId] ?: return item
        return cached.toBuilder().count(item.count).netId(item.netId).build()
    }

    @Volatile var selfRuntimeId  : Long    = 0L
    @Volatile var selfUniqueId   : Long    = 0L
    @Volatile var selfX          : Float   = 0f
    @Volatile var selfY          : Float   = 0f
    @Volatile var selfZ          : Float   = 0f
    @Volatile var selfPrevY      : Float   = 0f
    @Volatile var selfYaw        : Float   = 0f
    @Volatile var selfPitch      : Float   = 0f
    @Volatile var selfHealth     : Float   = 20f
    @Volatile var selfMaxHealth  : Float   = 20f
    @Volatile var selfAbsorb     : Float   = 0f
    @Volatile var selfArmorValue : Float   = 0f
    @Volatile var selfHunger     : Float   = 20f
    @Volatile var selfSaturation : Float   = 5f
    @Volatile var selfOnGround   : Boolean = true
    /** Y reference frame of selfY: true = eye frame (+1.62, AuthInput channel),
     *  false = feet frame (MovePlayer/Respawn/StartGame channels). V3 servers
     *  feed AuthInput only; PMMP-flavor feeds MovePlayer. CollisionGuard
     *  normalizes via this flag; consumers keep their current semantics. */
    @Volatile var selfYFrameIsEye: Boolean = false
    @Volatile var selfGameMode   : Int     = 0
    @Volatile var selfDimension  : Int     = 0
    @Volatile var selfSpeedXZ    : Float   = 0f

    @Volatile var selfHotbarSlot : Int     = 0
    @Volatile var selfIsRaining  : Boolean = false
    @Volatile var selfPingMs     : Int     = 0

    val selfInWater: Boolean get() = com.rubidiumclient.utils.WorldBlockTracker.isPlayerInWater()

    @Volatile var selfSprinting  : Boolean = false
    @Volatile var selfBlinded    : Boolean = false

    @Volatile var selfUsingItem       : Boolean = false
    @Volatile var selfItemUseStartMs  : Long    = 0L
    @Volatile var selfLastUseBitMs    : Long    = 0L

    val selfItemUseDurationMs: Long
        get() = if (selfUsingItem) System.currentTimeMillis() - selfItemUseStartMs else 0L

    @Volatile var inventoriesServerAuthoritative: Boolean = true

    private var prevSelfX = 0f; private var prevSelfZ = 0f

    private val _entityCountFlow  = MutableStateFlow(0)
    val entityCountFlow : StateFlow<Int>   = _entityCountFlow.asStateFlow()
    private val _selfHealthFlow   = MutableStateFlow(20f)
    val selfHealthFlow  : StateFlow<Float> = _selfHealthFlow.asStateFlow()
    private val _entityUpdateFlow = MutableStateFlow(0L)
    val entityUpdateFlow: StateFlow<Long>  = _entityUpdateFlow.asStateFlow()

    private var cleanupJob: kotlinx.coroutines.Job? = null

    fun init() {
        PacketEventBus.register(this)

        cleanupJob?.cancel()
        cleanupJob = metadataScope.launch {
            while (true) {
                val perf = com.rubidiumclient.module.ModuleManager.byName("Performance")
                    as? com.rubidiumclient.module.misc.Performance
                val intervalMs = perf?.entityCleanupIntervalMs?.value?.toLong() ?: 10_000L
                val timeoutMs  = perf?.staleEntityTimeoutMs?.value?.toLong() ?: 30_000L
                kotlinx.coroutines.delay(intervalMs)
                try { removeStale(timeoutMs) } catch (_: Exception) {}
                try {
                    if (selfUsingItem && System.currentTimeMillis() - selfItemUseStartMs > 8000L) {
                        selfUsingItem = false
                        selfItemUseStartMs = 0L
                        selfLastUseBitMs = 0L
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun getSelfName(): String =
        AccountManager.selectedAccount?.gamertag?.takeIf { it.isNotBlank() }
            ?: playerNames[selfUniqueId]
            ?: ""

    fun reset() {
        entities.clear(); uniqueToRuntime.clear(); playerNames.clear()
        playerCounter.set(0); hostileCounter.set(0)
        selfInventory.clear()
        selfArmor.clear()
        netIdCache.clear()
        selfRuntimeId = 0L; selfUniqueId = 0L
        selfX = 0f; selfY = 0f; selfZ = 0f; selfYaw = 0f; selfPitch = 0f
        selfHealth = 20f; selfMaxHealth = 20f; selfAbsorb = 0f; selfArmorValue = 0f
        selfHunger = 20f; selfSaturation = 5f; selfOnGround = true
        selfGameMode = 0; selfDimension = 0; selfSpeedXZ = 0f
        selfIsRaining = false
        selfPingMs = 0
        selfSprinting = false
        selfBlinded = false
        selfUsingItem = false
        selfItemUseStartMs = 0L
        selfLastUseBitMs = 0L
        prevSelfX = 0f; prevSelfZ = 0f
        inventoriesServerAuthoritative = true
        _entityCountFlow.value = 0; _selfHealthFlow.value = 20f
    } 
    override fun onPacket(event: PacketEvent) {
    when (val p = event.packet) {
        is StartGamePacket          -> handleStartGame(p)
        is AddEntityPacket          -> handleAddEntity(p)
        is AddPlayerPacket          -> handleAddPlayer(p)
        is AddItemEntityPacket      -> cacheByNetId(p.itemInHand)
        is RemoveEntityPacket       -> handleRemoveEntity(p)
        is MoveEntityAbsolutePacket -> handleMoveAbsolute(p)
        is MoveEntityDeltaPacket    -> handleMoveDelta(p)
        is MovePlayerPacket         -> handleMovePlayer(p, event.direction)
        is SetEntityDataPacket      -> handleEntityData(p)
        is SetEntityMotionPacket    -> handleEntityMotion(p)
        is UpdateAttributesPacket   -> handleAttributes(p)
        is PlayerListPacket         -> handlePlayerList(p)
        is EntityEventPacket        -> handleEntityEvent(p)
        is LevelEventPacket         -> handleLevelEvent(p)
        is SetPlayerGameTypePacket  -> selfGameMode = p.gamemode
        is RespawnPacket            -> if (p.state == RespawnPacket.State.SERVER_SEARCHING) { selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z }
        is ChangeDimensionPacket    -> handleDimension(p)
        is SetEntityLinkPacket      -> handleEntityLink(p)
        is PlayerAuthInputPacket    -> handleAuthInput(p, event.direction)
        is MobEquipmentPacket       -> handleMobEquipment(p, event.direction)
        is MobArmorEquipmentPacket  -> handleMobArmorEquipment(p)
        is PlayerHotbarPacket       -> handlePlayerHotbar(p, event.direction)
        is MobEffectPacket          -> handleMobEffect(p)
        is InventoryContentPacket   -> handleInventoryContent(p)
        is InventorySlotPacket      -> handleInventorySlot(p)
        is org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket -> handleItemStackResponse(p)
        is org.cloudburstmc.protocol.bedrock.packet.UnknownPacket -> {}
        else -> {}
    }
}

private fun handleStartGame(p: StartGamePacket) {
    selfRuntimeId = p.runtimeEntityId; selfUniqueId = p.uniqueEntityId
    selfX = p.playerPosition.x; selfY = p.playerPosition.y; selfZ = p.playerPosition.z
    selfYaw = p.rotation.y; selfPitch = p.rotation.x
    selfGameMode = p.playerGameType.ordinal
    inventoriesServerAuthoritative = p.isInventoriesServerAuthoritative
}

private fun handleLevelEvent(p: LevelEventPacket) {
    val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
    when {
        typeStr.contains("START_RAIN") || typeStr.contains("START_THUNDER") -> selfIsRaining = true
        typeStr.contains("STOP_RAIN")                                       -> selfIsRaining = false
    }
}

private fun handleAddEntity(p: AddEntityPacket) {
    if (p.runtimeEntityId == selfRuntimeId) return
    val e = TrackedEntity(
        runtimeId  = p.runtimeEntityId,
        uniqueId   = p.uniqueEntityId,
        identifier = p.identifier,
        type       = resolveType(p.identifier),
        x          = p.position.x,
        y          = p.position.y,
        z          = p.position.z,
        yaw        = p.rotation.y,
        pitch      = p.rotation.x,
        headYaw    = p.headRotation,
        velX       = p.motion.x,
        velY       = p.motion.y,
        velZ       = p.motion.z,
    )
    entities[p.runtimeEntityId] = e; uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
    trackAdd(e)
    notifyUpdate()
    val meta = try { p.metadata } catch (_: Exception) { null }
    if (meta != null) {
        metadataScope.launch { applyMetadata(e, meta) }
    }
}

private fun handleAddPlayer(p: AddPlayerPacket) {
    if (p.runtimeEntityId == selfRuntimeId) return
    val e = TrackedEntity(
        runtimeId  = p.runtimeEntityId,
        uniqueId   = p.uniqueEntityId,
        identifier = "minecraft:player",
        type       = EntityType.PLAYER,
        x          = p.position.x,
        y          = p.position.y,
        z          = p.position.z,
        yaw        = p.rotation.y,
        pitch      = p.rotation.x,
        headYaw    = p.rotation.z,
        velX       = p.motion.x,
        velY       = p.motion.y,
        velZ       = p.motion.z,
        name       = p.username ?: "",
    )
    entities[p.runtimeEntityId] = e; uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
    trackAdd(e)
    notifyUpdate()
    val meta = try { p.metadata } catch (_: Exception) { null }
    if (meta != null) {
        metadataScope.launch { applyMetadata(e, meta) }
    }
}

private fun handleRemoveEntity(p: RemoveEntityPacket) {
    val rid = uniqueToRuntime.remove(p.uniqueEntityId)
    if (rid != null) {
        entities.remove(rid)?.let { trackRemove(it) }
        notifyUpdate()
    }
}

private fun handleMoveAbsolute(p: MoveEntityAbsolutePacket) {
    val e = entities[p.runtimeEntityId] ?: return
    e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
    e.x = p.position.x; e.y = p.position.y; e.z = p.position.z
    e.yaw = p.rotation.y; e.pitch = p.rotation.x; e.headYaw = p.rotation.z
    e.isOnGround = p.isOnGround; e.lastUpdateMs = System.currentTimeMillis()
}

private fun handleMoveDelta(p: MoveEntityDeltaPacket) {
    val e = entities[p.runtimeEntityId] ?: return
    e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z

    val flags: Set<*>? = try { p.flags } catch (_: Exception) { null }

    if (flags != null && flags.isNotEmpty()) {
        for (flag in flags) {
            when (flag.toString()) {
                "HAS_X"        -> e.x       += p.x
                "HAS_Y"        -> e.y       += p.y
                "HAS_Z"        -> e.z       += p.z
                "HAS_YAW"      -> e.yaw      = p.yaw
                "HAS_PITCH"    -> e.pitch    = p.pitch
                "HAS_HEAD_YAW" -> e.headYaw  = p.headYaw
                "ON_GROUND"    -> e.isOnGround = true
            }
        }
    } else {
        try { e.x += p.x; e.y += p.y; e.z += p.z } catch (_: Exception) {}
    }
    e.lastUpdateMs = System.currentTimeMillis()
}

private fun handleMovePlayer(p: MovePlayerPacket, dir: PacketEvent.Direction) {
    if (p.runtimeEntityId == selfRuntimeId) {
        if (dir == PacketEvent.Direction.CLIENT_TO_SERVER) {
            prevSelfX = selfX; prevSelfZ = selfZ
            selfPrevY = selfY
            selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
            selfYaw = p.rotation.y; selfPitch = p.rotation.x
            selfYFrameIsEye = false   // MovePlayer.Position is feet frame
            selfOnGround = p.isOnGround
            selfSpeedXZ  = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
        }
        return
    }
    val e = entities[p.runtimeEntityId] ?: return
    e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
    e.x = p.position.x; e.y = p.position.y; e.z = p.position.z
    e.yaw = p.rotation.y; e.pitch = p.rotation.x
    e.isOnGround = p.isOnGround; e.lastUpdateMs = System.currentTimeMillis()
}

private fun handleAuthInput(p: PlayerAuthInputPacket, dir: PacketEvent.Direction) {
    if (dir != PacketEvent.Direction.CLIENT_TO_SERVER) return
    prevSelfX = selfX; prevSelfZ = selfZ
    selfPrevY = selfY
    selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        selfYaw = p.rotation.y; selfPitch = p.rotation.x
        selfYFrameIsEye = true   // AuthInput.Position is eye/head frame (+1.62)
        selfSpeedXZ = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
    if (p.inputData.contains(PlayerAuthInputData.START_SPRINTING)) selfSprinting = true
    if (p.inputData.contains(PlayerAuthInputData.STOP_SPRINTING)) selfSprinting = false

    val now = System.currentTimeMillis()
    if (p.inputData.contains(PlayerAuthInputData.START_USING_ITEM)) {
        selfLastUseBitMs = now
        if (!selfUsingItem) {
            selfUsingItem = true
            selfItemUseStartMs = now
        }
    } else if (selfUsingItem && now - selfLastUseBitMs > ITEM_USE_BIT_GRACE_MS) {
        selfUsingItem = false
        selfItemUseStartMs = 0L
    }
}
        private fun handleMobEffect(p: MobEffectPacket) {
        if (p.runtimeEntityId != selfRuntimeId) return
        if (p.effectId != 15) return
        when (p.event) {
            MobEffectPacket.Event.ADD, MobEffectPacket.Event.MODIFY -> selfBlinded = true
            MobEffectPacket.Event.REMOVE -> selfBlinded = false
            else -> {}
        }
    }

    private fun handleMobEquipment(p: MobEquipmentPacket, dir: PacketEvent.Direction) {
        if (dir == PacketEvent.Direction.CLIENT_TO_SERVER) {
            if (p.runtimeEntityId != selfRuntimeId && p.runtimeEntityId != 0L) return
            if (selfUsingItem) {
                selfUsingItem = false
                selfItemUseStartMs = 0L
                selfLastUseBitMs = 0L
            }
            if (p.containerId == org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.INVENTORY) {
                selfHotbarSlot = p.hotbarSlot
                val item = p.item
                if (item != null && !isEmptyItem(item)) {
                    selfInventory[p.hotbarSlot] = item
                } else {
                    selfInventory.remove(p.hotbarSlot)
                }
            }
            return
        }

        if (dir != PacketEvent.Direction.SERVER_TO_CLIENT) return
        if (p.runtimeEntityId == selfRuntimeId) return

        val e = entities[p.runtimeEntityId] ?: return
        val item = p.item
        val resolved = if (item != null && !isEmptyItem(item)) item else null

        when (p.containerId) {
            org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.OFFHAND -> e.offHandItem = resolved
            org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.INVENTORY -> e.mainHandItem = resolved
            else -> {}
        }
    }

    private fun handleMobArmorEquipment(p: MobArmorEquipmentPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = entities[p.runtimeEntityId] ?: return

        fun resolve(item: ItemData?) = if (item != null && !isEmptyItem(item)) item else null

        e.helmetItem     = resolve(p.helmet)
        e.chestplateItem = resolve(p.chestplate)
        e.leggingsItem   = resolve(p.leggings)
        e.bootsItem      = resolve(p.boots)
    }

    private fun handlePlayerHotbar(p: PlayerHotbarPacket, dir: PacketEvent.Direction) {
        if (dir != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (!p.isSelectHotbarSlot) return
        if (p.containerId == org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.INVENTORY) {
            selfHotbarSlot = p.selectedHotbarSlot
        }
    }

    private fun handleEntityMotion(p: SetEntityMotionPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = entities[p.runtimeEntityId] ?: return
        e.velX = p.motion.x; e.velY = p.motion.y; e.velZ = p.motion.z
    }

    private fun handleEntityData(p: SetEntityDataPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        applyMetadata(e, p.metadata); e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAttributes(p: UpdateAttributesPacket) {
        val isSelf = p.runtimeEntityId == selfRuntimeId
        p.attributes.forEach { attr ->
            when (attr.name) {
                "minecraft:health" -> {
                    if (isSelf) { selfHealth = attr.value; selfMaxHealth = attr.maximum; _selfHealthFlow.value = selfHealth }
                    else entities[p.runtimeEntityId]?.let { it.health = attr.value; it.maxHealth = attr.maximum }
                }
                "minecraft:absorption"    -> if (isSelf) selfAbsorb    = attr.value else entities[p.runtimeEntityId]?.absorbHealth = attr.value
                "minecraft:armor"         -> if (isSelf) selfArmorValue = attr.value else entities[p.runtimeEntityId]?.armorValue   = attr.value
                "minecraft:hunger"        -> if (isSelf) selfHunger    = attr.value
                "minecraft:saturation"    -> if (isSelf) selfSaturation = attr.value
                "minecraft:movement"      -> entities[p.runtimeEntityId]?.movSpeed  = attr.value
                "minecraft:attack_damage" -> entities[p.runtimeEntityId]?.attackDmg = attr.value
            }
        }
    }

    private object PingFieldCache {
        @Volatile private var resolved = false
        @Volatile private var field: java.lang.reflect.Field? = null

        fun getPing(entry: Any): Int {
            if (!resolved) {
                synchronized(this) {
                    if (!resolved) {
                        field = runCatching {
                            entry.javaClass.getDeclaredField("latencyMs").also { it.isAccessible = true }
                        }.getOrElse {
                            runCatching {
                                entry.javaClass.getDeclaredField("latency").also { it.isAccessible = true }
                            }.getOrNull()
                        }
                        resolved = true
                    }
                }
            }
            return runCatching { field?.getInt(entry) ?: 0 }.getOrElse { 0 }
        }
    }

    private fun handlePlayerList(p: PlayerListPacket) {
        if (p.action != PlayerListPacket.Action.ADD) {
            p.entries.forEach { playerNames.remove(it.entityId) }
            return
        }
        p.entries.forEach { entry ->
            val rid  = uniqueToRuntime[entry.entityId]
            val name = entry.name ?: return@forEach
            playerNames[entry.entityId] = name
            if (entry.entityId == selfUniqueId) {
                selfPingMs = PingFieldCache.getPing(entry)
            }
            if (rid != null) {
                entities[rid]?.let {
                    it.name   = name
                    it.pingMs = PingFieldCache.getPing(entry)
                }
            }
        }
    }

    private fun handleEntityEvent(p: EntityEventPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        try {
            val typeName = p.type?.toString() ?: return
            when {
                typeName.contains("HURT")  -> { e.hurtTime = 10; e.lastHurtMs = System.currentTimeMillis() }
                typeName.contains("DEATH") -> e.deathAnim = true
            }
        } catch (_: Exception) {}
    }

    private fun handleDimension(p: ChangeDimensionPacket) {
        selfDimension = p.dimension
        selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        entities.clear(); uniqueToRuntime.clear()
        playerCounter.set(0); hostileCounter.set(0)
        notifyUpdate()
    }

    private fun handleEntityLink(p: SetEntityLinkPacket) {
        try {
            val link = try { p.entityLink } catch (_: Exception) { null } ?: return

            val riderRid  = uniqueToRuntime[link.to]   ?: return
            val rider     = entities[riderRid]          ?: return
            val typeStr   = link.type?.toString() ?: ""

            when {
                typeStr.contains("RIDER") || typeStr.contains("PASSENGER") || typeStr.contains("VEHICLE") -> {
                    rider.isRiding = true
                    rider.ridingId = uniqueToRuntime[link.from] ?: 0L
                }
                typeStr.contains("REMOVE") -> {
                    rider.isRiding = false; rider.ridingId = 0L
                }
            }
        } catch (e: Exception) { }
    }

    private fun isEmptyItem(item: ItemData?): Boolean {
        if (item == null) return true
        if (item.count > 0) return false
        if (item.netId > 0) return false
        return true
    }

    private fun handleInventoryContent(p: InventoryContentPacket) {
        p.contents.forEach { cacheByNetId(it) }

        when (p.containerId) {
            0 -> {
                for (s in 0..35) selfInventory.remove(s)
                p.contents.forEachIndexed { slot, raw ->
                    val item = resolveFull(raw)
                    if (!isEmptyItem(item)) {
                        selfInventory[slot] = item!!
                    }
                }
            }
            119 -> {
                val item = resolveFull(p.contents.firstOrNull())
                if (item == null || isEmptyItem(item)) selfInventory.remove(119)
                else selfInventory[119] = item
            }
            org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.ARMOR -> {
                for (s in 0..3) selfArmor.remove(s)
                p.contents.forEachIndexed { slot, raw ->
                    val item = resolveFull(raw)
                    if (!isEmptyItem(item)) selfArmor[slot] = item!!
                }
            }
            else -> return
        }
    }

    private fun handleInventorySlot(p: InventorySlotPacket) {
        cacheByNetId(p.item)
        val item = resolveFull(p.item)

        if (p.containerId == org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId.ARMOR) {
            if (isEmptyItem(item)) selfArmor.remove(p.slot) else selfArmor[p.slot] = item!!
            return
        }

        if (p.containerId != 0 && p.containerId != 119) return
        val slotKey = if (p.containerId == 119) 119 else p.slot
        if (isEmptyItem(item)) {
            selfInventory.remove(slotKey)
        } else {
            selfInventory[slotKey] = item!!
        }
    }

    private fun handleItemStackResponse(p: org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket) {
        val csHOTBAR = org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.HOTBAR_AND_INVENTORY
        val csOFFHAND = org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.OFFHAND
        val csARMOR = org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.ARMOR

        for (response in p.entries) {
            if (response.result != org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus.OK) continue

            for (container in response.containers) {
                @Suppress("DEPRECATION")
                val containerType = container.containerName?.container ?: container.container
                if (containerType != csHOTBAR && containerType != csOFFHAND && containerType != csARMOR) continue

                for (slotInfo in container.items) {
                    val slotKey = if (containerType == csOFFHAND) 119 else slotInfo.slot
                    val targetMap = if (containerType == csARMOR) selfArmor else selfInventory

                    if (slotInfo.count <= 0) {
                        targetMap.remove(slotKey)
                        continue
                    }

                    val cached = netIdCache[slotInfo.stackNetworkId] ?: continue
                    targetMap[slotKey] = cached.toBuilder().count(slotInfo.count).build()
                }
            }
        }
    }

    fun getInventoryItem(slot: Int): ItemData? = selfInventory[slot]

    /**
     * True when the entity took a visible hit within the last [windowMs]
     * milliseconds (~500 ms = the bedrock hurt-invulnerability window).
     * KillAura's Hurttime Check uses this to skip swing targets whose hits
     * the server would discard anyway instead of burning packets on them.
     */
    fun wasRecentlyHurt(runtimeId: Long, windowMs: Long): Boolean {
        val last = entities[runtimeId]?.lastHurtMs ?: return false
        return last > 0L && System.currentTimeMillis() - last < windowMs
    }

    fun getHeldItem(): ItemData? = selfInventory[selfHotbarSlot]

    fun getInventorySnapshot(): Map<Int, ItemData> = selfInventory.toMap()

    fun getArmorItem(slot: Int): ItemData? = selfArmor[slot]

    fun getArmorSnapshot(): Map<Int, ItemData> = selfArmor.toMap()

    private fun applyMetadata(entity: TrackedEntity, metadata: Map<*, *>?) {
        if (metadata == null) return
        try {
            metadata.forEach { (key, value) ->
                val keyStr = key?.toString()?.uppercase() ?: return@forEach
                when {
                    keyStr.contains("SNEAKING")  -> entity.isSneaking  = value as? Boolean ?: false
                    keyStr.contains("SPRINTING") -> entity.isSprinting = value as? Boolean ?: false
                    keyStr.contains("INVISIBLE") -> entity.isInvisible = value as? Boolean ?: false
                    keyStr == "2" || keyStr.contains("NAMETAG") -> entity.name   = (value as? String) ?: entity.name
                    keyStr == "7" || keyStr.contains("HEALTH")  -> entity.health = (value as? Float) ?: entity.health
                }
            }
        } catch (e: Exception) { }
    }

    private fun resolveType(id: String): EntityType {
        val n = id.lowercase().removePrefix("minecraft:")
        return when {
            n == "player"           -> EntityType.PLAYER
            n.contains("crystal")  -> EntityType.CRYSTAL
            n in MONSTER_IDS        -> EntityType.MONSTER
            n in ANIMAL_IDS         -> EntityType.ANIMAL
            n in PASSIVE_IDS        -> EntityType.PASSIVE
            n in PROJECTILE_IDS     -> EntityType.PROJECTILE
            n == "item"             -> EntityType.ITEM
            else                    -> EntityType.UNKNOWN
        }
    }

    private val MONSTER_IDS = setOf(
        "zombie","skeleton","creeper","spider","cave_spider","enderman","witch","phantom",
        "drowned","husk","stray","wither_skeleton","blaze","ghast","magma_cube","slime",
        "guardian","elder_guardian","shulker","vindicator","evoker","vex","pillager",
        "ravager","hoglin","piglin","zoglin","piglin_brute","warden","zombie_villager",
        "zombie_pigman","zombified_piglin","endermite","silverfish"
    )
    private val ANIMAL_IDS = setOf(
        "cow","pig","sheep","chicken","horse","donkey","mule","rabbit","wolf","cat",
        "ocelot","panda","polar_bear","fox","bee","turtle","salmon","cod","pufferfish",
        "tropical_fish","dolphin","squid","glow_squid","axolotl","goat","frog","tadpole",
        "camel","sniffer","armadillo"
    )
    private val PASSIVE_IDS = setOf(
        "villager","wandering_trader","iron_golem","snow_golem","strider","bat","parrot",
        "mooshroom","llama","trader_llama","allay","chest_minecart","minecart","boat","chest_boat"
    )
    private val PROJECTILE_IDS = setOf(
        "arrow","spectral_arrow","thrown_trident","snowball","egg","ender_pearl",
        "eye_of_ender_signal","fireball","small_fireball","fishing_hook","llama_spit",
        "shulker_bullet","dragon_fireball","wither_skull","fireworks_rocket","wind_charge"
    )

    fun getAll()    : Collection<TrackedEntity> = entities.values
    fun getById(id: Long) = entities[id]
    fun getByUniqueId(uid: Long) = uniqueToRuntime[uid]?.let { entities[it] }
    fun getByName(name: String)  = entities.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Player-list authoritative name behind a runtime id (falls back to the entity record). */
    fun nameOf(runtimeId: Long): String? = playerNames[runtimeId] ?: entities[runtimeId]?.name

    /**
     * Canonical (player-list-cased) name for a case-insensitive input — searches
     * the PLAYER LIST first (authoritative casing on every server), then the
     * entity records. Some servers never send AddPlayerPacket for players out
     * of render distance, so entities-only lookups falsely miss online players.
     */
    fun onlineNameOf(name: String): String? =
        playerNames.values.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: getByName(name)?.name?.takeIf { it.isNotBlank() }

    fun getEntitiesInRange(range: Float, predicate: (TrackedEntity) -> Boolean = { true }): List<TrackedEntity> {
        val r2 = range * range
        val out = ArrayList<TrackedEntity>()
        for (e in entities.values) {
            if (MathUtil.dist3sq(e.x, e.y, e.z, selfX, selfY, selfZ) <= r2 && predicate(e)) out.add(e)
        }
        return out
    }

    fun getPlayers (range: Float = Float.MAX_VALUE) = getEntitiesInRange(range) { it.isPlayer }
    fun getHostiles(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range) { it.isHostile }
    fun getCrystals(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range) { it.isCrystal }

    private inline fun nearestInRange(range: Float, predicate: (TrackedEntity) -> Boolean): TrackedEntity? {
        val r2 = range * range
        var best: TrackedEntity? = null
        var bestD2 = Float.MAX_VALUE
        for (e in entities.values) {
            if (!predicate(e)) continue
            val d2 = MathUtil.dist3sq(e.x, e.y, e.z, selfX, selfY, selfZ)
            if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; best = e }
        }
        return best
    }

    fun getNearestPlayer (range: Float) = nearestInRange(range) { it.isPlayer }
    fun getNearestHostile(range: Float) = nearestInRange(range) { it.isHostile }

    fun distanceTo(e: TrackedEntity)             = MathUtil.dist3(e.x, e.y, e.z, selfX, selfY, selfZ)
    fun distanceTo(x: Float, y: Float, z: Float) = MathUtil.dist3(x, y, z, selfX, selfY, selfZ)
    fun distanceTo2D(e: TrackedEntity)           = MathUtil.dist2(e.x, e.z, selfX, selfZ)

    fun angleToEntity(e: TrackedEntity): Float {
        val yaw = Math.toDegrees(atan2(-(e.x - selfX).toDouble(), (e.z - selfZ).toDouble())).toFloat()
        return abs(((selfYaw - yaw) % 360f + 540f) % 360f - 180f)
    }

    fun isInFov(e: TrackedEntity, fov: Float) = fov >= 360f || angleToEntity(e) <= fov / 2f

    fun getHealthPercent() = if (selfMaxHealth > 0f) selfHealth / selfMaxHealth else 0f
    fun isLowHealth(t: Float = 6f)      = selfHealth <= t
    fun isCriticalHealth(t: Float = 3f) = selfHealth <= t

    fun count()        = entities.size
    fun playerCount()  = playerCounter.get()
    fun hostileCount() = hostileCounter.get()

    // ── Memory mode: ingest from the in-game agent (docs/MERGE_PLAN.md P1) ────
    //
    // This is the ONE additive entry point the whole merge rests on. 69 of 81
    // modules read this singleton rather than the network, so feeding it from
    // memory is what makes those modules work in memory mode without a single
    // edit to their code. The packet path (handleAddEntity/handleAddPlayer/...)
    // is untouched and keeps feeding the same map when the app is in PROXY mode.
    //
    // Only the agent feed calls this, and only in MEMORY mode: two writers on one
    // map would produce a world that is half packet-state and half memory-state.

    /** Self state as the agent reports it (feet frame). */
    data class RemoteSelf(
        val x: Float, val y: Float, val z: Float,
        val yaw: Float, val pitch: Float,
        val health: Float, val onGround: Boolean, val damaged: Boolean,
    )

    /** One actor as the agent reports it: already filtered, already masked. */
    data class RemoteActor(
        val runtimeId: Long,
        val isPlayer: Boolean,
        val x: Float, val y: Float, val z: Float,
        val health: Float,
        val hurt: Boolean,
    )

    /** Entries the memory feed owns, so it can never delete packet-path entities. */
    private val agentManaged = ConcurrentHashMap.newKeySet<Long>()

    /** Consecutive ingests an agent-managed entity was missing from (~3 s at 20 Hz). */
    private val agentMissing = ConcurrentHashMap<Long, Int>()

    private const val AGENT_DESPAWN_INGESTS = 60

    fun ingest(self: RemoteSelf, actors: List<RemoteActor>) {
        val now = System.currentTimeMillis()

        // ── self ──
        selfPrevY     = selfY            // fall-damage consumers need the previous Y
        selfX         = self.x
        selfY         = self.y
        selfZ         = self.z
        selfYaw       = self.yaw
        selfPitch     = self.pitch
        selfHealth    = self.health.coerceAtLeast(0f)
        selfOnGround  = self.onGround
        // botSelf() reports the actor's own position; treat it as the feet frame
        // unless the parity test against the packet path says otherwise (the flag
        // exists precisely so consumers normalize instead of guessing).
        selfYFrameIsEye = false
        _selfHealthFlow.value = selfHealth

        // ── actors ──
        val seen = HashSet<Long>(actors.size * 2)
        for (a in actors) {
            seen.add(a.runtimeId)
            val existing = entities[a.runtimeId]
            if (existing == null) {
                val e = TrackedEntity(
                    runtimeId  = a.runtimeId,
                    uniqueId   = 0L,                       // unknown from memory
                    identifier = if (a.isPlayer) "minecraft:player" else "",
                    type       = if (a.isPlayer) EntityType.PLAYER else EntityType.UNKNOWN,
                    x = a.x, y = a.y, z = a.z,
                    health = a.health,
                )
                e.lastUpdateMs = now
                if (a.hurt) { e.hurtTime = HURT_TICKS; e.lastHurtMs = now }
                entities[a.runtimeId] = e
                agentManaged.add(a.runtimeId)
                trackAdd(e)
            } else {
                existing.prevX = existing.x
                existing.prevY = existing.y
                existing.prevZ = existing.z
                existing.x = a.x
                existing.y = a.y
                existing.z = a.z
                existing.health = a.health
                existing.lastUpdateMs = now
                if (a.hurt) { existing.hurtTime = HURT_TICKS; existing.lastHurtMs = now }
                else if (existing.hurtTime > 0) existing.hurtTime -= 1
            }
            agentMissing.remove(a.runtimeId)
        }

        // ── despawn: only entries this feed created, only after a grace period,
        //    because botScan's range (<=32 m) means "absent" also means "walked
        //    away" — and flickering entities in and out of ESP would be worse
        //    than a three-second lag on a real despawn.
        for (rid in agentManaged) {
            if (rid in seen) continue
            val missed = (agentMissing[rid] ?: 0) + 1
            if (missed < AGENT_DESPAWN_INGESTS) {
                agentMissing[rid] = missed
            } else {
                agentMissing.remove(rid)
                agentManaged.remove(rid)
                entities.remove(rid)?.let { trackRemove(it) }
            }
        }

        notifyUpdate()
    }

    private const val HURT_TICKS = 10

    fun removeStale(maxAgeMs: Long = 30_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale  = entities.entries.filter { it.value.lastUpdateMs < cutoff }
        stale.forEach { (rid, e) -> entities.remove(rid); uniqueToRuntime.remove(e.uniqueId); trackRemove(e) }
        if (stale.isNotEmpty()) { notifyUpdate() }
    }

    private fun notifyUpdate() {
        _entityCountFlow.value  = entities.size
        _entityUpdateFlow.value = System.currentTimeMillis()
    }
    
    private const val ITEM_USE_BIT_GRACE_MS = 150L
    
    
}
