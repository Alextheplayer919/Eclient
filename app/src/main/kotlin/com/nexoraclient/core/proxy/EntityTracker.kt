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

    // Metadata decode IO thread dışında yapılsın diye ayrı scope
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

    // ItemStackResponsePacket sadece stackNetworkId + count taşıyor, tam ItemData
    // (definition/identifier) taşımıyor — çünkü gerçek client zaten o item'ı daha
    // önce başka bir yerde (chest/shulker InventoryContentPacket'i, mob equipment vs.)
    // görmüş oluyor. Relay bu bilgiye sahip olmadığı için ItemStackResponsePacket'i
    // uygulayamıyordu ve manuel container->envanter taşımaları (shulker'dan elle item
    // çekmek gibi) selfInventory'e hiç yansımıyordu — AutoTotem/AutoArmor'ın "ghost item"
    // görmesinin asıl sebebi buydu. Artık hangi container'dan geldiğine bakmaksızın
    // görülen HER item netId'siyle burada cache'leniyor; response geldiğinde bu cache'ten
    // çözülüyor.
    private val netIdCache = ConcurrentHashMap<Int, ItemData>()

    private fun hasResolvableDefinition(item: ItemData): Boolean {
        val id = runCatching { item.definition?.identifier }.getOrElse { null }
        return !id.isNullOrBlank()
    }

    private fun cacheByNetId(item: ItemData?) {
        if (item == null || isEmptyItem(item)) return
        // FIX: netId-only referans item'lar (definition/identifier taşımayan, örn. yerden
        // pickup paketleri) cache'e yazılmasın — yoksa daha önce başka bir yerden (chest/
        // shulker/AddItemEntityPacket) öğrenilmiş DOLU bir definition'ın üzerine boş bir
        // referansla yazıp cache'i bozabilirler.
        if (!hasResolvableDefinition(item)) return
        val netId = item.netId
        if (netId != 0) netIdCache[netId] = item
    }

    // FIX (ghost item): InventoryContentPacket/InventorySlotPacket ile gelen bir item
    // definition/identifier taşımıyorsa (server, client'ın bu netId'yi zaten bir yerden
    // — mesela yerdeki AddItemEntityPacket'ten — gördüğünü varsayıp sadece netId+count
    // gönderiyor), artık ham veriyi selfInventory/selfArmor'a öylece yazmıyoruz.
    // netIdCache'te bu netId için daha önce görülmüş dolu bir kayıt varsa onun
    // definition'ını, paketin güncel count'uyla birleştirip kullanıyoruz. Bu, yerden
    // alınan item'ların AutoTotem/AutoArmor tarafından hemen tanınmasını sağlıyor —
    // önceden sadece chest/shulker'dan tam veri içeren bir InventoryContentPacket
    // gelince (o slotu ezerek) "düzeliyormuş" gibi görünüyordu.
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
    @Volatile var selfGameMode   : Int     = 0
    @Volatile var selfDimension  : Int     = 0
    @Volatile var selfSpeedXZ    : Float   = 0f

    @Volatile var selfHotbarSlot : Int     = 0
    @Volatile var selfIsRaining  : Boolean = false
    @Volatile var selfPingMs     : Int     = 0

    // KillAuraPro gibi modüller artık doğrudan EntityTracker.selfInWater bekliyor.
    // Kendi su-tespit mantığını burada tekrar yazmak yerine, zaten kanıtlanmış
    // WorldBlockTracker.isPlayerInWater() fonksiyonuna delege ediyoruz.
    val selfInWater: Boolean get() = com.rubidiumclient.utils.WorldBlockTracker.isPlayerInWater()
    // Kritik hasar bloklayıcıları: sprint atarken veya kör iken (Blindness)
    // vanilla mekaniği kritiği tamamen iptal eder. KillAura/KillAuraPro bu
    // bayrakları saldırı anında kontrol edip crit girişimini boşa harcamıyor.
    @Volatile var selfSprinting  : Boolean = false
    @Volatile var selfBlinded    : Boolean = false

    // Item use (yeme/içme/bow/potion) durumu. Proxy client→server paketlerini
    // gördüğü için, bunu PlayerAuthInputPacket'taki START_USING_ITEM bitinden
    // türetiyoruz. Bu bit bir tick yanlışlıkla düşebildiği için (özellikle lag
    // veya tick jitter'da) hemen "false" yapmıyoruz — BİT_GRACE_MS boyunca
    // bit gelmediyse bitmiş sayıyoruz. MobEquipmentPacket (hotbar değişimi)
    // client→server gelirse use iptal olur, anında false'a çekiyoruz.
    // Modüller (CrystalAura vs) artık EntityTracker.selfUsingItem okuyor —
    // eski `selfItemUseDuration > 0` gibi tahmin yürütmeye gerek yok.
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

        // FIX: removeStale() tanımlıydı ama hiçbir yerden çağrılmıyordu — sunucudan
        // bir RemoveEntityPacket kaçırılırsa (paket kaybı, chunk unload vb.) entity
        // sonsuza kadar map'te kalıyordu. Uzun oturumlarda bu, her ESP/KillAura/
        // ArrayList taramasının gittikçe daha fazla hayalet entity üzerinden dönmesine
        // ve zamanla artan GC baskısı/FPS düşüşüne yol açıyordu. Artık Performance
        // modülündeki aralıklarla periyodik olarak temizleniyor.
        cleanupJob?.cancel()
        cleanupJob = metadataScope.launch {
            while (true) {
                val perf = com.rubidiumclient.module.ModuleManager.byName("Performance")
                    as? com.rubidiumclient.module.misc.Performance
                val intervalMs = perf?.entityCleanupIntervalMs?.value?.toLong() ?: 10_000L
                val timeoutMs  = perf?.staleEntityTimeoutMs?.value?.toLong() ?: 30_000L
                kotlinx.coroutines.delay(intervalMs)
                try { removeStale(timeoutMs) } catch (_: Exception) {}
                // Item use güvenlik tavanı: item use bitinin düşmesini kaçırdıysak
                // (disconnect, paket kaybı), 8 saniyeden uzun süren bir use gerçek
                // değildir — bayrağı zorla indir.
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
        // FIX: netIdCache burada hiç temizlenmiyordu. Bedrock netId'leri her
        // oturumda küçük sayılardan yeniden başladığı için, reconnect/yeni
        // sunucu sonrası önceki oturumdan kalan cache'teki bir item, yeni
        // sunucunun ürettiği aynı numaralı FARKLI bir netId ile çakışıp
        // handleItemStackResponse()'ın slot'a yanlış item verisi yazmasına
        // yol açabiliyordu (sessizce, hatasız). Ayrıca temizlenmeden
        // biriktiği için bellek sızıntısıydı.
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
            is InventoryContentPacket   -> {
                handleInventoryContent(p)
            }
            is InventorySlotPacket      -> {
                handleInventorySlot(p)
            }
            is org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket -> {
                handleItemStackResponse(p)
            }
            is org.cloudburstmc.protocol.bedrock.packet.UnknownPacket -> {
            }
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

    // START_RAIN / STOP_RAIN (ve fırtına) LevelEventPacket ile geliyor.
    // Trident/riptide gibi yağmura bağlı mekanikler bu bayrağı okuyabilsin diye
    // sadece global bir durum bayrağı tutuyoruz, entity ile ilgisi yok.
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
        // Önce entity'yi kaydet, metadata'yı IO thread dışında decode et
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
        // Önce entity'yi kaydet, metadata'yı IO thread dışında decode et
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
            // Bu paket her hareket eden entity için her tick'te geliyor - flag başına
            // toString()+uppercase() eskiden her tick her entity için yeni bir String
            // allocate ediyordu (GC baskısı = FPS düşüşü). 
