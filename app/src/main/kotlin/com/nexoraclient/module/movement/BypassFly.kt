package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.proxy.CollisionGuard
import com.rubidiumclient.core.proxy.MovementCompliance
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.RubberbandGuard
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Her ortam (su/yer/hava) için AC'nin beklediği hareket paternine yakın kalacak
// şekilde ayrı motion profili uygular. Ability tabanlı native uçuş (CreativeFly)
// yerine MotionFly'daki gibi SetEntityMotionPacket enjeksiyonu kullanılıyor —
// gerçek istemci fiziği pozisyonu kendi hesapladığı için PlayerAuthInputPacket
// üzerinden sunucuya giden konum daima istemcinin kendi fiziğinden geliyor,
// TPAura'daki gibi ayrı bir MovePlayerPacket ile çakışma riski yok.
//
// GELİŞTİRMELER:
// - Auto mod: EntityTracker.selfInWater / selfOnGround'a bakıp Su/Yer/Hava
//   profilini kendisi seçiyor, sınırda (su yüzeyi, blok kenarı) titremesin
//   diye 120ms debounce ile geçiş yapıyor.
// - Pause On Damage: hasar alınca (selfHealthFlow düşüşü) bir süre motion
//   enjeksiyonunu durduruyor — gerçek knockback fiziğinin üstüne binip
//   "hasar aldı ama hiç sarsılmadı" gibi bariz bir AC tell'i oluşturmuyoruz.
// - Yumuşak hızlanma: yatay hız artık input başlar başlamaz anlık tepe
//   değere zıplamıyor, ~250ms'de üstel eğriyle tırmanıyor/düşüyor — sabit
//   büyüklükte periyodik motion paketleri bazı velocity-delta tabanlı
//   AC'lerde "çok düzenli" görünüp flag'lenebiliyordu.
// - Organik gürültü: eski flip() (±sabit değer, kare dalga) yerine rastgele
//   büyüklükte noise — istatistiksel olarak çok daha az periyodik/robotik.
// - Anti Rubber-band: yüksek Horizontal/Vertical Speed ile gerçek istemci
//   fiziğinin ürettiği PlayerAuthInputPacket.position tek tick'te "meşru"
//   sınırın çok üstünde sıçrayabiliyor -> sunucu geri atıyordu. FIX: eskiden
//   bu koruma ayrı bir "Timer" modülünün "PvP Timer" ayarına bağımlıydı,
//   Timer kapalıyken devre dışı kalıyordu. Artık BypassFly'ın kendi bağımsız
//   "Anti Rubber-band" ayarı var (paylaşılan RubberbandGuard sınıfı ile) —
//   Timer modülüne hiç ihtiyaç yok. O tick'te raporlanan pozisyon Max Step
//   ile kırpılıyor, kalan mesafe hemen arkasından küçük senkron
//   MovePlayerPacket adımlarıyla yetiştiriliyor — input/action verisi
//   (blok kırma, item kullanma vb.) taşıyan asıl pakete dokunulmuyor,
//   sadece pozisyon alanı küçültülüyor.
// - Ability koruması (CreativeFly portu): spoof ettiğimiz MAY_FLY/FLYING
//   durumunu, istemcinin kendi RequestAbilityPacket(FLYING) isteği ya da
//   sunucudan gelen bir UpdateAbilitiesPacket sessizce sıfırlayabiliyordu.
//   abilitiesOn iken ikisi de engelleniyor (FlyModule/CreativeFly ile aynı
//   davranış) — Ground modunda zaten ability verilmediğinden dokunulmuyor.
class BypassFly : BaseModule(
    name        = "BypassFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Ortama göre (Su/Yer/Hava/Auto) ayrı motion profiliyle uçuş"
) {
    enum class FlyMode { Water, Ground, Air, Auto }

    private val mode              = enum ("Mode",                 FlyMode.Auto)
    private val horizontalSpeed   = float("Horizontal Speed",     4.0f,  0.5f, 10.0f)
    private val airVerticalSpeed  = float("Air Vertical Speed",   1.6f,  0.2f, 5.0f)
    private val waterVerticalSpeed= float("Water Vertical Speed", 0.9f,  0.1f, 3.0f)
    private val waterBob          = float("Water Bob",            0.02f, 0.0f, 0.1f)
    private val airJitter         = float("Air Jitter",           0.05f, 0.0f, 0.3f)
    private val motionInterval    = int  ("Delay",                 55,   15,  150)
    private val grantAbilities    = bool ("Grant Fly Ability",     true)
    private val accelSmoothing    = bool ("Smooth Acceleration",   true)
    private val pauseOnDamage     = bool ("Pause On Damage",       true)
    private val pauseDurationMs   = int  ("Pause Duration (ms)",   400,  100, 2000)
    // FIX: eskiden bu koruma ayrı bir "Timer" modülünün "PvP Timer" ayarına
    // bağımlıydı — Timer kapalıyken (varsayılan durum) rubber-band koruması
    // tamamen devre dışı kalıyordu. Artık BypassFly kendi bağımsız ayarlarına
    // sahip, Timer modülüne hiç ihtiyaç yok.
    private val antiRubberband    = bool ("Anti Rubber-band",      true)
    private val rubberbandMaxStep = float("Max Step",              0.9f, 0.3f, 2.0f)
    private val rubberbandDelayMs = int  ("Step Delay (ms)",       12,   2,   40)
    private val shortcut          = bool ("Shortcut",              false)

    @Volatile private var lastMotionMs   = 0L
    @Volatile private var abilitiesOn    = false
    @Volatile private var lastSession    : RubidiumRelaySession? = null

    // Auto mod debounce
    @Volatile private var committedMode    = FlyMode.Air
    @Volatile private var candidateMode    = FlyMode.Air
    @Volatile private var candidateSinceMs = 0L

    // Yumuşak hızlanma
    @Volatile private var speedFactor = 0f

    // Hasar-duraklatma
    @Volatile private var lastKnownHealth = 20f
    @Volatile private var lastDamageMs    = 0L

    // Anti rubber-band: bağımsız RubberbandGuard, kendi state'ini kendi tutar.
    private val rubberbandGuard = RubberbandGuard(scope)

    override fun onEnable() {
        super.onEnable()
        lastMotionMs     = 0L
        abilitiesOn      = false
        committedMode    = FlyMode.Air
        candidateMode    = FlyMode.Air
        candidateSinceMs = 0L
        speedFactor      = 0f
        lastKnownHealth  = EntityTracker.selfHealth
        lastDamageMs     = 0L
        rubberbandGuard.reset()

        scope.launch {
            EntityTracker.selfHealthFlow.collect { hp ->
                if (hp < lastKnownHealth - 0.01f) lastDamageMs = System.currentTimeMillis()
                lastKnownHealth = hp
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { applyAbilities(it, false) }
        abilitiesOn = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet

        // Spoof ettiğimiz MAY_FLY/FLYING durumunu bozabilecek paketleri
        // engelliyoruz (CreativeFly portu). Ground modunda zaten ability
        // vermediğimizden abilitiesOn=false olur, bu blok devreye girmez.
        if (abilitiesOn) {
            if (pkt is RequestAbilityPacket && pkt.ability == Ability.FLYING) {
                event.cancel()
                return
            }
            if (pkt is UpdateAbilitiesPacket) {
                event.cancel()
                return
            }
        }

        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        val now = System.currentTimeMillis()

        // Anti rubber-band: her gerçek tick'te (throttle'dan bağımsız) çalışır.
        rubberbandGuard.guard(event, pkt, antiRubberband.value, rubberbandMaxStep.value, rubberbandDelayMs.value.toLong())

        if (pauseOnDamage.value && now - lastDamageMs < pauseDurationMs.value) {
            // Gerçek knockback fiziğinin üstüne binmiyoruz, bu tick'i atla.
            return
        }

        val effectiveMode = resolveMode(now)

        // KRİTİK: Ground modunda MAY_FLY/FLYING yetkisi ASLA verilmiyor.
        // Amaç sunucuya "uçuyor" durumu hiç bildirmemek — sadece yatay hız
        // artışı, dikey hiçbir müdahale yok. Su/Hava modunda gerçek istemci
        // WANT_UP/WANT_DOWN input'unu doğru yorumlasın diye yetki veriliyor.
        val wantAbilities = grantAbilities.value && effectiveMode != FlyMode.Ground
        if (wantAbilities != abilitiesOn) applyAbilities(event.session, wantAbilities)

        if (now - lastMotionMs < motionInterval.value) return
        lastMotionMs = now

        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val hasInput = inputX != 0f || inputZ != 0f

        if (accelSmoothing.value) {
            // ~250ms'de hedefe üstel yaklaşım — anlık tepe hız zıplaması yok.
            val target = if (hasInput) 1f else 0f
            val rate   = (motionInterval.value / 250f).coerceIn(0.05f, 0.9f)
            speedFactor += (target - speedFactor) * rate
        } else {
            speedFactor = if (hasInput) 1f else 0f
        }

        val governed = MovementCompliance.governedSpeed(horizontalSpeed.value)
        val strafe  = inputX * governed * speedFactor
        val forward = inputZ * governed * speedFactor
        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val wantUp   = pkt.inputData.contains(PlayerAuthInputData.WANT_UP)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN)

        val motionY = when (effectiveMode) {
            FlyMode.Ground -> 0f
            FlyMode.Water  -> when {
                wantUp   ->  waterVerticalSpeed.value
                wantDown -> -waterVerticalSpeed.value
                else     -> noise(waterBob.value)
            }
            FlyMode.Air, FlyMode.Auto -> when {
                wantUp   ->  airVerticalSpeed.value
                wantDown -> -airVerticalSpeed.value
                else     -> noise(airJitter.value)
            }
        }

        // Governor: no upward impulse right after a correction; sustained
        // climbs past the flag budget clamp to hover (flag prevention).
        val motionYFinal = MovementCompliance.governedVertical(
            if (MovementCompliance.shouldSettleVertical() && motionY > 0f) 0f else motionY
        )
        // Collision consistency: requested motion shrinks to what the world
        // allows (wall-slide / hover-stop vs slam — vanilla response shape).
        val finalMotion = CollisionGuard.clampedMotion(
            EntityTracker.selfX, CollisionGuard.feetY(), EntityTracker.selfZ,
            motionX, motionYFinal, motionZ
        )
        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = finalMotion
        }
        event.session.clientBound(motionPacket)
    }

    // Anti rubber-band mantığı artık paylaşılan RubberbandGuard sınıfında
    // (bkz. com.rubidiumclient.utils.RubberbandGuard) - Timer modülüne
    // bağımlılık yok, kendi bağımsız ayarlarımızla (antiRubberband/
    // rubberbandMaxStep/rubberbandDelayMs) çalışıyor.

    // Auto modda gerçek ortamı (EntityTracker.selfInWater / selfOnGround)
    // okuyup Su/Yer/Hava profiline çeviriyor. Su yüzeyi ya da blok kenarında
    // aday sürekli değişmesin diye 120ms boyunca aynı adayda kalınca commit
    // ediliyor — ability paketi spam'ini önlüyor.
    private fun resolveMode(now: Long): FlyMode {
        if (mode.value != FlyMode.Auto) {
            committedMode = mode.value
            return committedMode
        }

        val raw = when {
            EntityTracker.selfInWater  -> FlyMode.Water
            EntityTracker.selfOnGround -> FlyMode.Ground
            else                       -> FlyMode.Air
        }

        if (raw != candidateMode) {
            candidateMode = raw
            candidateSinceMs = now
        }
        if (now - candidateSinceMs >= 120L) committedMode = candidateMode

        return committedMode
    }

    private fun noise(magnitude: Float): Float =
        if (magnitude <= 0f) 0f else (Random.nextFloat() * 2f - 1f) * magnitude

    private fun applyAbilities(session: RubidiumRelaySession, enabled: Boolean) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
            commandPermission = if (enabled) CommandPermission.OWNER  else CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val values = mutableListOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED, Ability.WALK_SPEED, Ability.VERTICAL_FLY_SPEED
                )
                if (enabled) {
                    values += Ability.MAY_FLY
                    values += Ability.FLYING
                }
                abilityValues.addAll(values.toTypedArray())

                walkSpeed        = 0.1f
                flySpeed         = if (enabled) 0.3f else 0.05f
                verticalFlySpeed = if (enabled) 0.25f else 0.05f
            })
        }
        session.clientBound(packet)
        abilitiesOn = enabled
    }
}
