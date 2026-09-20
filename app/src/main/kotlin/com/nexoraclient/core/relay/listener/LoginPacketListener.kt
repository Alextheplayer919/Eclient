package com.rubidiumclient.core.relay.listener
import android.util.Base64
import com.rubidiumclient.auth.MicrosoftAuthManager
import com.rubidiumclient.core.relay.ClientIdentification
import com.rubidiumclient.core.relay.ConnectionManager
import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload
import org.cloudburstmc.protocol.bedrock.packet.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class LoginPacketListener : RubidiumPacketListener {

    companion object {
        private const val TAG = "LoginPacketListener"
    }

    override val priority: Int = 0

    @Volatile var clientIdentification: ClientIdentification? = null
        private set

    @Volatile private var loginProcessed = false
    @Volatile private var pendingLogin  : LoginPacket? = null
    @Volatile private var loginSentAtMs : Long = 0L

    private val resignClientJwtEnabled = true

    override fun onSessionStart(session: RubidiumRelaySession) {
        ConnectionManager.onHandshaking()
    }

    override fun onSessionEnd(session: RubidiumRelaySession) {
        pendingLogin = null
        loginSentAtMs = 0L
        ConnectionManager.onDisconnected()
    }

    override fun onClientPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
            }

            is LoginPacket -> {
                if (loginProcessed) {
                    return false
                }
                loginProcessed = true

                val chainJson = extractChainJson(packet)
                val extraJson = extractExtraJson(packet)
                try {
                    clientIdentification = ClientIdentification.fromLogin(chainJson, extraJson)
                } catch (e: Exception) {
                }

                injectAuthChain(packet, session)
                pendingLogin = packet

                session.connectToServer {
                    val reqNet = RequestNetworkSettingsPacket().apply {
                        protocolVersion = session.activeCodec.protocolVersion
                    }
                    session.sendToServer(reqNet)
                }

                return false
            }

            is ClientToServerHandshakePacket -> {
                return false
            }
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
        when (packet) {
            is NetworkSettingsPacket -> {
                try {
                    val algo = if (packet.compressionThreshold > 0)
                        packet.compressionAlgorithm
                    else
                        PacketCompressionAlgorithm.NONE

                    session.serverSession?.setCompression(algo)
                } catch (e: Exception) {
                }

                val login = pendingLogin
                if (login != null) {
                    loginSentAtMs = System.currentTimeMillis()
                    session.sendToServer(login)
                    pendingLogin = null
                } else {
                    session.disconnect("Login paketi kayboldu")
                }

                return false
            }

            is ServerToClientHandshakePacket -> {
                try {
                    enableEncryption(packet, session)
                } catch (e: Exception) {
                    session.sendToServer(ClientToServerHandshakePacket())
                }
                return false
            }

            is PlayStatusPacket -> {
                when (packet.status) {
                    PlayStatusPacket.Status.LOGIN_SUCCESS -> {
                        ConnectionManager.onHandshaking()
                    }
                    PlayStatusPacket.Status.PLAYER_SPAWN -> {
                        ConnectionManager.onGameStarted()
                    }
                    else -> {
                    }
                }
            }

            is StartGamePacket -> {
                ConnectionManager.onGameStarted()
            }

            is DisconnectPacket -> {
                loginSentAtMs = 0L
            }
        }
        return true
    }

    private fun enableEncryption(packet: ServerToClientHandshakePacket, session: RubidiumRelaySession) {
        val jwt    = packet.jwt ?: throw IllegalStateException("JWT null")
        val parts  = jwt.split(".")
        require(parts.size == 3) { "Geçersiz JWT format" }

        val decoder     = java.util.Base64.getUrlDecoder()
        val headerJson  = JSONObject(String(decoder.decode(parts[0])))
        val payloadJson = JSONObject(String(decoder.decode(parts[1])))

        val x5u  = headerJson.getString("x5u")
        val salt = java.util.Base64.getDecoder().decode(payloadJson.getString("salt"))

        val serverPubKey = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils.parseKey(x5u)
        val privateKey   = MicrosoftAuthManager.getPrivateKeyForEncryption()
            ?: throw IllegalStateException("Encryption private key yok")

        val secretKey = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
            .getSecretKey(privateKey, serverPubKey, salt)

        session.serverSession?.enableEncryption(secretKey)

        session.sendToServer(ClientToServerHandshakePacket())
    }

    private fun injectAuthChain(packet: LoginPacket, session: RubidiumRelaySession) {
        val savedChain = MicrosoftAuthManager.getActiveChainForRelay()
        if (savedChain.isNullOrBlank()) {
            return
        }

        val privKeyB64 = MicrosoftAuthManager.getActivePrivateKeyForRelay()
        if (privKeyB64.isNullOrBlank()) {
            return
        }

        val pubKeyB64 = MicrosoftAuthManager.getActivePublicKeyForRelay()
        if (pubKeyB64.isNullOrBlank()) {
            return
        }

        try {
            val savedJwts = parseSavedChain(savedChain)
            if (savedJwts.isEmpty()) {
                return
            }

            packet.authPayload = CertificateChainPayload(savedJwts, AuthType.FULL)

            val originalClientJwt = packet.clientJwt
            if (!originalClientJwt.isNullOrBlank()) {
                if (resignClientJwtEnabled) {
                    val resigned = resignClientJwt(originalClientJwt, privKeyB64, pubKeyB64, session)
                    if (resigned != null) {
                        packet.clientJwt = resigned
                    }
                }
            }

        } catch (e: Exception) {
        }
    }

    private fun parseSavedChain(json: String): List<String> = try {
        when {
            json.trimStart().startsWith("{") -> {
                val arr = JSONObject(json).optJSONArray("chain")
                if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                else emptyList()
            }
            json.trimStart().startsWith("[") -> {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            }
            else -> listOf(json)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun buildDeviceJwt(priv: ECPrivateKey, pubB64: String): String {
        val now     = System.currentTimeMillis() / 1000L
        val header  = b64Url("""{"alg":"ES384","x5u":"$pubB64"}""".toByteArray())
        val payload = b64Url(
            ("""{"certificateAuthority":true,"identityPublicKey":"$pubB64",""" +
             """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}""")
                .toByteArray()
        )
        val data = "$header.$payload"
        val sig  = Signature.getInstance("SHA384withECDSA").apply {
            initSign(priv)
            update(data.toByteArray(Charsets.US_ASCII))
        }.sign()

        return "$data.${b64Url(derToRaw(sig))}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        i++
        val rLen = der[i++].toInt() and 0xFF
        val r = der.copyOfRange(i, i + rLen)
        i += rLen
        i++
        val sLen = der[i++].toInt() and 0xFF
        val s = der.copyOfRange(i, i + sLen)

        return pad48(BigInteger(1, r).toByteArray()) + pad48(BigInteger(1, s).toByteArray())
    }

    private fun pad48(b: ByteArray): ByteArray = when {
        b.size == 48 -> b
        b.size >  48 -> b.copyOfRange(b.size - 48, b.size)
        else         -> ByteArray(48 - b.size) + b
    }

    private fun resignClientJwt(originalClientJwt: String, privKeyB64: String, pubKeyB64: String, session: RubidiumRelaySession): String? {
        try {
            val parts = originalClientJwt.split(".")
            if (parts.size != 3) {
                return null
            }

            val payloadJson = try {
                val padded  = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                val decoded = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                val obj = JSONObject(decoded)

                val newServerAddress = "${session.remoteHost}:${session.remotePort}"
                val newDeviceId      = java.util.UUID.randomUUID().toString()

                obj.put("ServerAddress", newServerAddress)
                obj.put("DeviceId", newDeviceId)

                if (obj.has("PlayFabId")) {
                    obj.remove("PlayFabId")
                }

                b64Url(obj.toString().toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                parts[1]
            }

            val keyBytes = Base64.decode(privKeyB64, Base64.NO_WRAP)
            val privKey  = KeyFactory.getInstance("EC")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes)) as ECPrivateKey

            val header = b64Url("""{"alg":"ES384","x5u":"$pubKeyB64"}""".toByteArray())
            val data   = "$header.$payloadJson"
            val sig    = Signature.getInstance("SHA384withECDSA").apply {
                initSign(privKey)
                update(data.toByteArray(Charsets.US_ASCII))
            }.sign()

            return "$data.${b64Url(derToRaw(sig))}"
        } catch (e: Exception) {
            return null
        }
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun extractChainJson(packet: LoginPacket): String = try {
        when (val auth = packet.authPayload) {
            is CertificateChainPayload -> JSONObject().apply {
                put("chain", JSONArray(auth.chain))
            }.toString()
            is TokenPayload -> JSONObject().apply {
                put("token", auth.token)
            }.toString()
            else -> "{}"
        }
    } catch (_: Exception) { "{}" }

    private fun extractExtraJson(packet: LoginPacket): String = try {
        packet.clientJwt ?: ""
    } catch (_: Exception) { "" }
}
