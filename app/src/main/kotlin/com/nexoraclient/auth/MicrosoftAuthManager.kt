package com.rubidiumclient.auth

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import com.rubidiumclient.core.relay.TargetVersion

object MicrosoftAuthManager {

    private const val TAG = "MicrosoftAuth"

    private const val CLIENT_ID      = "0000000048183522"
    private const val REDIRECT_URI   = "https://login.live.com/oauth20_desktop.srf"
    private const val SCOPE          = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val TOKEN_URL      = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL        = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL       = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL    =
        "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"
    private const val REALMS_RELYING_PARTY = "https://pocket.realms.minecraft.net/"

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var initialized = false

    private data class RealmsTokenCache(val xbl: String, val expireAtMs: Long)

    @Volatile private var realmsTokenCache: RealmsTokenCache? = null

    @Volatile private var encryptionPrivateKey: java.security.PrivateKey? = null

    fun getPrivateKeyForEncryption(): java.security.PrivateKey? = encryptionPrivateKey

    fun setPrivateKeyForEncryption(key: java.security.PrivateKey) {
        encryptionPrivateKey = key
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)

        val saved = AccountManager.selectedAccount
        if (saved != null && saved.isRelayReady()) {
            _authState.value = AuthState.Success(saved.gamertag, saved.mcToken)

            try {
                if (saved.privateKeyB64.isNotBlank()) {
                    val keyBytes = android.util.Base64.decode(saved.privateKeyB64, android.util.Base64.NO_WRAP)
                    encryptionPrivateKey = java.security.KeyFactory.getInstance("EC")
                        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
                }
            } catch (e: Exception) {
            }
        } else if (saved != null && saved.isExpired()) {
            scope.launch { refreshTokenSilently(saved) }
        }
    }

    fun startSignIn() {
        if (_authState.value.isLoading) return
        _authState.value = AuthState.WaitingForWebView
    }

    fun exchangeCodeForToken(code: String) {
        if (activeJob?.isActive == true) return
        _authState.value = AuthState.Loading
        activeJob = scope.launch {
            runCatching { doTokenExchangeFlow(code) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        _authState.value = AuthState.Error(e.message ?: "Sign-in failed")
                    } else {
                        _authState.value = AuthState.Idle
                    }
                }
        }
    }

    fun cancelSignIn() {
        activeJob?.cancel()
        activeJob = null
        _authState.value = AuthState.Idle
    }

    fun signOut() {
        AccountManager.selectedAccount?.let { AccountManager.removeAccount(it) }
        AccountManager.clearSelectedAccount()
        realmsTokenCache = null
        _authState.value = AuthState.Idle
    }

    fun switchAccount(account: SavedAccount) {
        if (activeJob?.isActive == true) return
        realmsTokenCache = null
        AccountManager.selectAccount(account)

        if (account.isRelayReady()) {
            _authState.value = AuthState.Success(account.gamertag, account.mcToken)
            try {
                if (account.privateKeyB64.isNotBlank()) {
                    val keyBytes = android.util.Base64.decode(account.privateKeyB64, android.util.Base64.NO_WRAP)
                    encryptionPrivateKey = java.security.KeyFactory.getInstance("EC")
                        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
                }
            } catch (e: Exception) {
            }
        } else {
            _authState.value = AuthState.Loading
            activeJob = scope.launch {
                runCatching { refreshTokenSilently(account) }
                    .onFailure { e ->
                        if (e !is CancellationException) {
                            _authState.value = AuthState.Error(e.message ?: "Account could not be refreshed")
                        }
                    }
            }
        }
    }

    fun getActiveChainForRelay(): String? {
        val account = AccountManager.selectedAccount ?: run {
            return null
        }
        val remainingMs = account.expireTimeMs - System.currentTimeMillis()
        if (account.isExpired()) {
            scope.launch { refreshTokenSilently(account) }
        }
        return account.mcToken.takeIf { it.isNotBlank() }
    }

    fun getActivePrivateKeyForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null
        return account.privateKeyB64.takeIf { it.isNotBlank() }
    }

    fun getActivePublicKeyForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null
        return account.publicKeyB64.takeIf { it.isNotBlank() }
    }

    suspend fun getRealmsXblToken(): String? = withContext(Dispatchers.IO) {
        realmsTokenCache?.let { cache ->
            if (System.currentTimeMillis() < cache.expireAtMs) return@withContext cache.xbl
        }

        val account = AccountManager.selectedAccount ?: return@withContext null
        if (account.refreshToken.isBlank()) return@withContext null

        try {
            val tokenResp = postForm(TOKEN_URL, mapOf(
                "client_id"     to CLIENT_ID,
                "grant_type"    to "refresh_token",
                "refresh_token" to account.refreshToken,
                "redirect_uri"  to REDIRECT_URI,
                "scope"         to SCOPE
            ))
            val accessToken  = tokenResp.str("access_token")
            val refreshToken = tokenResp["refresh_token"] as? String ?: account.refreshToken

            if (refreshToken != account.refreshToken) {
                AccountManager.selectedAccount?.let { current ->
                    AccountManager.addAccount(current.copy(refreshToken = refreshToken))
                }
            }

            val xblResult  = fetchXblToken(accessToken)
            val xstsResult = fetchXsts(xblResult.token, REALMS_RELYING_PARTY)

            val xbl = "x=${xstsResult.userHash};${xstsResult.token}"
            realmsTokenCache = RealmsTokenCache(xbl, System.currentTimeMillis() + 6 * 3_600_000L)
            xbl
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun doTokenExchangeFlow(code: String) {

        val tokenResp = postForm(TOKEN_URL, mapOf(
            "client_id"    to CLIENT_ID,
            "grant_type"   to "authorization_code",
            "code"         to code,
            "redirect_uri" to REDIRECT_URI,
            "scope"        to SCOPE
        ))

        val accessToken  = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()

        val xblResult = fetchXblToken(accessToken)
        currentCoroutineContext().ensureActive()

        val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
        currentCoroutineContext().ensureActive()

        val mcResult = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)
        currentCoroutineContext().ensureActive()

        val gamertag = resolveGamertag(
            chainResult  = mcResult,
            xstsGamertag = xstsResult.gamertag,
            xblGamertag  = xblResult.gamertag,
            xblToken     = xblResult.token
        )

        val account = SavedAccount(
            gamertag     = gamertag,
            refreshToken = refreshToken,
            mcToken      = mcResult.chainJson,
            expireTimeMs = System.currentTimeMillis() + 6 * 3_600_000L,
            privateKeyB64 = mcResult.privateKeyB64,
            publicKeyB64  = mcResult.publicKeyB64
        )
        AccountManager.addAccount(account)
        AccountManager.selectAccount(account)

        try {
            if (mcResult.privateKeyB64.isNotBlank()) {
                val keyBytes = android.util.Base64.decode(mcResult.privateKeyB64, android.util.Base64.NO_WRAP)
                val privKey  = java.security.KeyFactory.getInstance("EC")
                    .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
                encryptionPrivateKey = privKey
            }
        } catch (e: Exception) {
        }

        withContext(Dispatchers.Main) {
            _authState.value = AuthState.Success(gamertag, mcResult.chainJson)
        }
    }

    private suspend fun refreshTokenSilently(account: SavedAccount) {
        if (account.refreshToken.isBlank()) {
            _authState.value = AuthState.Idle
            return
        }

        try {
            val tokenResp = postForm(TOKEN_URL, mapOf(
                "client_id"     to CLIENT_ID,
                "grant_type"    to "refresh_token",
                "refresh_token" to account.refreshToken,
                "redirect_uri"  to REDIRECT_URI,
                "scope"         to SCOPE
            ))
            val accessToken  = tokenResp.str("access_token")
            val refreshToken = tokenResp["refresh_token"] as? String ?: account.refreshToken

            val xblResult  = fetchXblToken(accessToken)
            val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
            val mcResult   = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)

            val newExpire = System.currentTimeMillis() + 6 * 3_600_000L
            AccountManager.refreshAccount(account.gamertag, mcResult.chainJson, newExpire, mcResult.privateKeyB64, mcResult.publicKeyB64)
            AccountManager.selectedAccount?.let { updated ->
                AccountManager.addAccount(updated.copy(refreshToken = refreshToken))
            }

            try {
                if (mcResult.privateKeyB64.isNotBlank()) {
                    val keyBytes = android.util.Base64.decode(mcResult.privateKeyB64, android.util.Base64.NO_WRAP)
                    encryptionPrivateKey = java.security.KeyFactory.getInstance("EC")
                        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
                }
            } catch (_: Exception) {}

            _authState.value = AuthState.Success(account.gamertag, mcResult.chainJson)

        } catch (e: Exception) {
            _authState.value = AuthState.Error("Token refresh failed: ${e.message}")
        }
    }

    private fun fetchXblToken(accessToken: String): XblAuthResult {
        val bodyJson = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName",   "user.auth.xboxlive.com")
                put("RpsTicket",  accessToken)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType",    "JWT")
        }.toString()

        val respText = postJsonRaw(XBL_URL, bodyJson)

        val resp = JSONObject(respText)

        if (resp.has("error")) {
            val err = resp.optString("error")
            val desc = resp.optString("error_description", "")
            error("XBL error: $err — $desc")
        }

        val token = resp.optString("Token").takeIf { it.isNotBlank() }
            ?: error("XBL Token field empty — response: ${respText.take(400)}")

        val xui      = resp.optJSONObject("DisplayClaims")
            ?.optJSONArray("xui")
            ?.optJSONObject(0)

        val uhs      = xui?.optString("uhs") ?: ""
        val gamertag = xui?.optString("gtg") ?: ""

        return XblAuthResult(token, uhs, gamertag)
    }

    private fun fetchXsts(xblToken: String, relyingParty: String): XblAuthResult {
        val bodyJson = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId",  "RETAIL")
                put("UserTokens", JSONArray().apply { put(xblToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType",    "JWT")
        }.toString()

        val respText = postJsonRaw(XSTS_URL, bodyJson)

        val resp = JSONObject(respText)

        if (resp.has("XErr")) {
            val xerr = resp.optLong("XErr")
            val msg = when (xerr) {
                2148916233L -> "No Xbox account. Please create one at xbox.com."
                2148916238L -> "Child account — parental consent required."
                else        -> "XSTS error: $xerr — ${resp.optString("Message")}"
            }
            error(msg)
        }

        val token = resp.optString("Token").takeIf { it.isNotBlank() }
            ?: error("XSTS Token field empty — response: ${respText.take(400)}")

        val xui      = resp.optJSONObject("DisplayClaims")
            ?.optJSONArray("xui")
            ?.optJSONObject(0)
            ?: error("XSTS DisplayClaims.xui[0] missing — response: ${respText.take(400)}")

        val uhs      = xui.optString("uhs").takeIf { it.isNotBlank() }
            ?: error("XSTS uhs empty")
        val gamertag = xui.optString("gtg") ?: ""

        return XblAuthResult(token, uhs, gamertag)
    }

    private fun logFetchedChainSummary(chain: List<String>, expectedPubKeyB64: String) {
        val nowSec = System.currentTimeMillis() / 1000L
        chain.forEachIndexed { idx, jwt ->
            val parts = jwt.split(".")
            if (parts.size != 3) return@forEachIndexed
            val payload = try {
                val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                JSONObject(String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            } catch (_: Exception) { null } ?: return@forEachIndexed

            val exp = payload.optLong("exp", -1L)
            val nbf = payload.optLong("nbf", -1L)
            val expIn = if (exp > 0) exp - nowSec else null

            if (idx == 0) {
                val idKey = payload.optString("identityPublicKey", "")
                if (idKey.isNotBlank() && idKey != expectedPubKeyB64) {
                }
            }
        }
    }

    private fun fetchMinecraftChain(xstsToken: String, userHash: String): McAuthResult {
        val keyGen  = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp384r1"))
        val keyPair = keyGen.generateKeyPair()
        val pubKey  = keyPair.public  as ECPublicKey
        val privKey = keyPair.private as ECPrivateKey
        val pubKeyB64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)

        val privKeyB64 = Base64.encodeToString(privKey.encoded, Base64.NO_WRAP)

        val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)

        val requestBody = JSONObject().apply {
            put("identityPublicKey", pubKeyB64)
            put("chain", JSONArray().apply { put(deviceJwt) })
        }.toString()

        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization",  "XBL3.0 x=$userHash;$xstsToken")
            .header("Content-Type",   "application/json")
            .header("Accept",         "application/json")
            .header("User-Agent",     "MCPE/UWP")
            .header("client-version", TargetVersion.MC_VERSION)
            .build()

        val responseText = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                error("MC /authentication HTTP ${resp.code} — $body")
            }
            body.ifBlank { error("MC /authentication response empty") }
        }

        val json             = JSONObject(responseText)
        val serverChainArray = json.optJSONArray("chain")

        if (serverChainArray != null && serverChainArray.length() > 0) {

            val firstServerJwt = serverChainArray.getString(0)
            val actualSigningKey = try {
                val parts = firstServerJwt.split(".")
                val headerJson = String(
                    Base64.decode(
                        parts[0] + "=".repeat((4 - parts[0].length % 4) % 4),
                        Base64.URL_SAFE or Base64.NO_WRAP
                    ), Charsets.UTF_8
                )
                JSONObject(headerJson).optString("x5u").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }

            val finalDeviceJwt = if (actualSigningKey != null && actualSigningKey != pubKeyB64) {
                buildDeviceJwt(privKey, pubKeyB64, identityPublicKeyClaim = actualSigningKey)
            } else {
                deviceJwt
            }

            val fullChain = buildList {
                add(finalDeviceJwt)
                for (i in 0 until serverChainArray.length()) add(serverChainArray.getString(i))
            }
            val chainWrapper = JSONObject().apply {
                put("chain", JSONArray(fullChain))
            }.toString()

            val gamertag = extractGamertagFromJwtList(fullChain)
            logFetchedChainSummary(fullChain, pubKeyB64)
            return McAuthResult(chainWrapper, gamertag, privKeyB64, pubKeyB64)
        }

        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: error("MC token could not be obtained: $responseText")

        val fallbackChain = JSONObject().apply {
            put("chain", JSONArray().apply { put(deviceJwt); put(token) })
        }.toString()
        return McAuthResult(fallbackChain, "", privKeyB64, pubKeyB64)
    }

    private fun resolveGamertag(
        chainResult: McAuthResult,
        xstsGamertag: String,
        xblGamertag: String,
        xblToken: String
    ): String {
        if (chainResult.gamertag.isNotBlank()) {
            return chainResult.gamertag
        }
        if (xstsGamertag.isNotBlank()) {
            return xstsGamertag
        }
        if (xblGamertag.isNotBlank()) {
            return xblGamertag
        }

        return try {
            val xboxXsts = fetchXsts(xblToken, "http://xboxlive.com")
            val profileReq = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=${xboxXsts.userHash};${xboxXsts.token}")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()

            val body     = http.newCall(profileReq).execute().use { it.body?.string() } ?: ""
            val root     = JSONObject(body)
            val users    = root.optJSONArray("profileUsers")
            val settings = users?.optJSONObject(0)?.optJSONArray("settings")
            if (settings != null) {
                for (i in 0 until settings.length()) {
                    val s = settings.getJSONObject(i)
                    if (s.optString("id") == "Gamertag") {
                        val gt = s.optString("value")
                        return gt
                    }
                }
            }
            "RubidiumPlayer"
        } catch (e: Exception) {
            "RubidiumPlayer"
        }
    }

    private fun buildDeviceJwt(
        privateKey: ECPrivateKey,
        pubKeyB64: String,
        identityPublicKeyClaim: String = pubKeyB64
    ): String {
        val now     = System.currentTimeMillis() / 1000L

        val header  = b64Url("""{"alg":"ES384","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url(
            ("""{"certificateAuthority":true,"identityPublicKey":"$identityPublicKeyClaim",""" +
             """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}""")
                .toByteArray()
        )
        val sigInput = "$header.$payload"
        val signer   = Signature.getInstance("SHA384withECDSA")
        signer.initSign(privateKey)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))
        val sig = derToRaw(signer.sign())
        return "$sigInput.${b64Url(sig)}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        check(der[i] == 0x02.toByte()) { "DER: expected r tag" }
        i++
        val rLen = der[i++].toInt() and 0xFF
        val r    = der.copyOfRange(i, i + rLen); i += rLen
        check(der[i] == 0x02.toByte()) { "DER: expected s tag" }
        i++
        val sLen = der[i++].toInt() and 0xFF
        val s    = der.copyOfRange(i, i + sLen)

        return padOrTrim(BigInteger(1, r).toByteArray(), 48) +
               padOrTrim(BigInteger(1, s).toByteArray(), 48)
    }

    private fun padOrTrim(b: ByteArray, size: Int) = when {
        b.size == size -> b
        b.size > size  -> b.copyOfRange(b.size - size, b.size)
        else           -> ByteArray(size - b.size) + b
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun extractGamertagFromJwtList(jwts: List<String>): String {
        for (jwt in jwts) {
            try {
                val parts  = jwt.split(".")
                if (parts.size < 2) continue
                val padded = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                    .replace('-', '+').replace('_', '/')
                val payload = JSONObject(
                    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                )
                val name = payload.optJSONObject("extraData")?.optString("displayName")
                    ?.takeIf { it.isNotBlank() }
                    ?: payload.optString("displayName").takeIf { it.isNotBlank() }
                    ?: payload.optString("username").takeIf { it.isNotBlank() }
                if (name != null) return name
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build())
            .execute().use { resp ->
                val b = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $b")
                }
                b
            }
        return parseJsonOrForm(text)
    }

    private fun postJsonRaw(url: String, json: String): String {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val isXstsError = text.contains("XErr")
            if (!resp.isSuccessful && !isXstsError) {
                error("HTTP ${resp.code}: ${text.take(300)}")
            }
            text
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonOrForm(text: String): Map<String, Any?> =
        try {
            gson.fromJson(text, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null else part.substring(0, eq) to part.substring(eq + 1)
            }
        }

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("'$key' field not found — response: ${this.keys}")
}
