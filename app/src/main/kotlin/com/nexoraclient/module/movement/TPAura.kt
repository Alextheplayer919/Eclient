package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Rakip etrafında hareket eder"
), PacketEventBus.PacketListener {

    // FIX: Criticals modülü de her saldırıda kendi sahte MovePlayerPacket
    // dizisini (Y sıçraması) gönderiyor. TPAura ise her PlayerAuthInputPacket'te
    // (sn'de ~20 kez) kendi ham pozisyon paketini gönderiyordu — ikisi
    // birbirinden habersiz, aynı anda oyuncunun pozisyonunu farklı bazlardan
    // değiştirmeye çalışınca sunucu çelişen hareket verisi görüp rubber-band
    // (aşırı lagback) tetikliyordu. Bu zaman damgası Criticals'a "az önce
    // pozisyonu ben değiştirdim, üstüne binme" sinyali veriyor.
    companion object {
        @Volatile var lastPositionOverrideMs: Long = 0L
            private set

        // StrafeAuraV1 gibi başka modüller de kendi ham MovePlayerPacket'ini
        // gönderdiğinde bunu çağırıp TPAura'ya "az önce pozisyonu ben
        // değiştirdim, üstüne binme" sinyali versin diye public.
        fun notifyExternalPositionOverride() {
            lastPositionOverrideMs = System.currentTimeMillis()
        }
    }

    enum class MoveMode { Random, Strafe, Behind, Aggressive, Surround, Boost, Chaos }
    enum class PriorityMode { Closest, LowestHealth, Direction }

    private val moveMode        = enum ("Mode",             MoveMode.Aggressive)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val detectRange     = 256f
    private val range           = float("Range",            1.52f, 0.5f, 8f)
    private val horizontalSpeed = float("Horizontal Speed", 3.29f, 0.5f, 8f)
    private val verticalSpeed   = float("Vertical Speed",   1.8f, 0.1f, 8f)
    private val strafeSpeed     = float("Strafe Speed",     6.21f, 0.1f, 50f)
    private val yOffset         = float("Y Offset",         0.8f, -2f,  2f)
    private val rotateToTarget  = bool ("Rotate To Target", true)
    private val predict         = bool ("Predict",          true)
    private val phase           = bool ("Phase",            false)
    private val ignoreFriends   = bool ("Ignore Friends",   true)
    private val shortcut        = bool ("Shortcut",         false)

    private var strafeAngle     = 0.0
    private var surroundIndex   = 0
    private var boostPhase      = 0
    private var phaseActive     = false
    private var chaosAngle      = 0.0
    private var chaosVertOffset = 0f
    private var chaosVertDir    = 1f

    @Volatile private var lastTargetId  = 0L
    @Volatile private var lastFindMs    = 0L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null

    private val TARGET_CACHE_MS = 100L

    // Surround: hedefin 4 köşesi — crystal pvp'de en etkili pozisyonlar
    private val SURROUND_OFFSETS = arrayOf(
        floatArrayOf( 1.5f, 0f,  0f ),
        floatArrayOf(-1.5f, 0f,  0f ),
        floatArrayOf( 0f,   0f,  1.5f),
        floatArrayOf( 0f,   0f, -1.5f)
    )

    override fun onEnable() {
        super.onEnable()
        strafeAngle     = Random.nextDouble(0.0, Math.PI * 2)
        surroundIndex   = 0
        boostPhase      = 0
        lastTargetId    = 0L
        lastFindMs      = 0L
        cachedTarget    = null
        phaseActive     = false
        chaosAngle      = Random.nextDouble(0.0, Math.PI * 2)
        chaosVertOffset = 0f
        chaosVertDir    = 1f
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        if (phaseActive) {
            PacketEventBus.currentSession?.let { disablePhase(it) }
            phaseActive = false
        }
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        // Phase toggle — her pakette kontrol
        if (phase.value && !phaseActive) {
            enablePhase(event.session)
            phaseActive = true
        } else if (!phase.value && phaseActive) {
            disablePhase(event.session)
            phaseActive = false
        }

        val target = getCachedTarget() ?: return
        moveAroundTarget(target)
    }

    private fun getCachedTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        if (now - lastFindMs >= TARGET_CACHE_MS) {
            cachedTarget = findTarget()
            lastFindMs   = now
        }
        return cachedTarget
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val candidates = EntityTracker.getEntitiesInRange(detectRange)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }

        val target = when (priorityMode.value) {
            PriorityMode.Closest      -> candidates.minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
            PriorityMode.LowestHealth -> candidates.minByOrNull { it.health }
            PriorityMode.Direction    -> candidates.minByOrNull { EntityTracker.angleToEntity(it) }
        }

        if (target != null && target.runtimeId != lastTargetId) {
            lastTargetId  = target.runtimeId
            surroundIndex = 0
            boostPhase    = 0
        }
        return target
    }

    // Hedefin bir sonraki konumunu velocity'den tahmin et
    private fun predictedTargetPos(target: EntityTracker.TrackedEntity): Vector3f {
        if (!predict.value) return Vector3f.from(target.x, target.y, target.z)
        val vx = (target.x - target.prevX) * 3f
        val vz = (target.z - target.prevZ) * 3f
        return Vector3f.from(target.x + vx, target.y, target.z + vz)
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val predicted = predictedTargetPos(target)
        val targetPos = Vector3f.from(predicted.x, predicted.y + yOffset.value, predicted.z)
        val dist      = MathUtil.dist3(selfX, selfY, selfZ, predicted.x, predicted.y, predicted.z)

        val newPos = if (dist > range.value * 2f) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos)
        }

        val rot = if (rotateToTarget.value) RotationUtil.toEntity(target) else null
        val onGround = !phase.value
        val yaw   = rot?.yaw   ?: EntityTracker.selfYaw
        val pitch = rot?.pitch ?: EntityTracker.selfPitch

        lastPositionOverrideMs = System.currentTimeMillis()
        EntityTracker.selfX = newPos.x
        EntityTracker.selfY = newPos.y
        EntityTracker.selfZ = newPos.z
        if (rot != null) {
            EntityTracker.selfYaw   = rot.yaw
            EntityTracker.selfPitch = rot.pitch
        }

        // PvP Timer açıksa bu sıçrama TimerPvP üzerinden küçük, meşru
        // boyutlu adımlara bölünerek gönderilir (rubber-band engellenir).
        // Kapalıysa tek paket, anında — eski davranışla birebir aynı.
        scope.launch {
            try {
                com.rubidiumclient.utils.TimerPvP.dispatch(
                    session, selfX, selfY, selfZ, newPos.x, newPos.y, newPos.z, yaw, pitch, onGround
                )
            } catch (_: Exception) {}
        }
    }

    private fun stepTowardTarget(selfX: Float, selfY: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val direction = atan2(
            (targetPos.z - selfZ).toDouble(),
            (targetPos.x - selfX).toDouble()
        ) - Math.toRadians(90.0)

        val newX = selfX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = selfZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = targetPos.y.coerceIn(selfY - verticalSpeed.value, selfY + verticalSpeed.value)
        return Vector3f.from(newX, newY, newZ)
    }

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val radius = range.value

        return when (moveMode.value) {

            MoveMode.Aggressive -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.05
                val verticalWave = sin(strafeAngle * 0.7f).toFloat() * 0.25f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius).toFloat()
                )
            }

            MoveMode.Strafe -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.03
                val verticalWave = sin(strafeAngle * 0.5f).toFloat() * 0.3f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius * 1.3f).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius * 1.3f).toFloat()
                )
            }

            MoveMode.Behind -> {
                val dx    = targetPos.x - selfX
                val dz    = targetPos.z - selfZ
                val angle = atan2(dz.toDouble(), dx.toDouble()) + Math.PI
                Vector3f.from(
                    targetPos.x + (cos(angle) * radius * 1.2f).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * radius * 1.2f).toFloat()
                )
            }

            MoveMode.Random -> {
                val angle            = Random.nextDouble(0.0, Math.PI * 2)
                val horizontalOffset = radius * (0.7f + Random.nextFloat() * 0.3f)
                val verticalOffset   = (Random.nextFloat() - 0.5f) * 0.4f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(angle) * horizontalOffset).toFloat(),
                    targetPos.y + verticalOffset,
                    targetPos.z + (sin(angle) * horizontalOffset).toFloat()
                )
            }

            // Surround: hedefin 4 cardinal noktasını sırayla kapla
            // Crystal PvP'de hedefin etrafındaki kristal slot'larını bloklamak için ideal
            // Her pakette bir sonraki köşeye geç — 4 köşe tam bir tur
            MoveMode.Surround -> {
                val off = SURROUND_OFFSETS[surroundIndex % SURROUND_OFFSETS.size]
                surroundIndex++
                Vector3f.from(
                    targetPos.x + off[0],
                    targetPos.y + off[1],
                    targetPos.z + off[2]
                )
            }

            // Chaos: 50 sabit strafe speed + 3 blok yukarı/aşağı dikey salınım
            // Rakip ne yukarı ne aşağı bakmaya karar veremez
            MoveMode.Chaos -> {
                val CHAOS_STRAFE  = 50.0
                val CHAOS_RADIUS  = radius
                val VERT_RANGE    = 3f
                val VERT_STEP     = 0.25f * verticalSpeed.value

                chaosAngle += Math.toRadians(CHAOS_STRAFE) * 0.05

                chaosVertOffset += VERT_STEP * chaosVertDir
                if (chaosVertOffset >= VERT_RANGE) {
                    chaosVertOffset = VERT_RANGE
                    chaosVertDir    = -1f
                } else if (chaosVertOffset <= -VERT_RANGE) {
                    chaosVertOffset = -VERT_RANGE
                    chaosVertDir    = 1f
                }

                Vector3f.from(
                    targetPos.x + (cos(chaosAngle) * CHAOS_RADIUS).toFloat(),
                    targetPos.y + chaosVertOffset,
                    targetPos.z + (sin(chaosAngle) * CHAOS_RADIUS).toFloat()
                )
            }

            // Boost: AirPvP için figure-8 pattern
            // Hedefin üstüne → arkasına → önünden geç → tekrar
            // 3 fazlı döngü: rakip nereye bakacağını bilemez
            MoveMode.Boost -> {
                val phase = boostPhase % 3
                boostPhase++
                val yawRad = Math.toRadians(EntityTracker.selfYaw.toDouble())
                when (phase) {
                    0 -> {
                        // Üst: hedefin tam üstüne, rakibin görüş açısını kır
                        Vector3f.from(
                            targetPos.x,
                            targetPos.y + radius * 1.5f,
                            targetPos.z
                        )
                    }
                    1 -> {
                        // Arka: hedefin baktığı yönün tersine tp at
                        val behindAngle = atan2((targetPos.z - selfZ).toDouble(), (targetPos.x - selfX).toDouble()) + Math.PI
                        Vector3f.from(
                            targetPos.x + (cos(behindAngle) * radius).toFloat(),
                            targetPos.y,
                            targetPos.z + (sin(behindAngle) * radius).toFloat()
                        )
                    }
                    else -> {
                        // Ön: hızlı geçiş — rakip dönerken vur
                        val frontAngle = atan2((targetPos.z - selfZ).toDouble(), (targetPos.x - selfX).toDouble())
                        Vector3f.from(
                            targetPos.x + (cos(frontAngle) * radius * 0.8f).toFloat(),
                            targetPos.y + 0.3f,
                            targetPos.z + (sin(frontAngle) * radius * 0.8f).toFloat()
                        )
                    }
                }
            }
        }
    }

    private fun enablePhase(session: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        session.clientBound(UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(arrayOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.MAY_FLY, Ability.FLY_SPEED, Ability.WALK_SPEED,
                    Ability.OPERATOR_COMMANDS, Ability.NO_CLIP
                ))
                walkSpeed = 0.1f
                flySpeed  = 0.1f
            })
        })
    }

    private fun disablePhase(session: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        session.clientBound(UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(arrayOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED, Ability.WALK_SPEED, Ability.OPERATOR_COMMANDS
                ))
                walkSpeed = 0.1f
            })
        })
    }
}
