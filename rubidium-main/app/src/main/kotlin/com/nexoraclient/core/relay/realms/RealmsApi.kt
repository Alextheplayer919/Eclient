package com.rubidiumclient.core.relay.realms

import com.rubidiumclient.auth.MicrosoftAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RealmInfo(
    val id: Long,
    val name: String,
    val ownerXuid: String,
    val state: String,      // "OPEN" | "CLOSED"
    val expired: Boolean,
    val maxPlayers: Int
)

data class RealmAddress(
    val host: String,
    val port: Int,
    val networkProtocol: String // "DEFAULT" (RakNet/UDP) veya "NETHERNET" (WebRTC)
)

/**
 * Bedrock Realms REST API istemcisi (https://pocket.realms.minecraft.net).
 * Realm'lar sabit bir IP:port yayınlamaz -- gerçek sunucu adresi her
 * "join" isteğinde yeniden tahsis edilir ve oturumdan oturuma değişebilir,
 * bu yüzden relay'i başlatmadan hemen önce joinRealm() çağrılmalı.
 *
 * Kimlik doğrulama MicrosoftAuthManager.getRealmsXblToken() ile alınan
 * XBL3.0 token'ını kullanır (relying party: pocket.realms.minecraft.net).
 */
object RealmsApi {

    private const val BASE           = "https://pocket.realms.minecraft.net"
    private const val CLIENT_VERSION = "1.21.60" // aktif codec'in minecraftVersion'ıyla senkron tut
    private const val USER_AGENT     = "MCPE/UWP"

    class RealmsException(message: String, val httpCode: Int = -1) : Exception(message)

    /** Hesabın davetli olduğu / sahip olduğu tüm realm'leri listeler. */
    suspend fun listRealms(): List<RealmInfo> = withContext(Dispatchers.IO) {
        val body = request("GET", "/worlds")
        val arr  = JSONObject(body).optJSONArray("servers") ?: JSONArray()

        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RealmInfo(
                id         = o.optLong("id"),
                name       = o.optString("name"),
                ownerXuid  = o.optString("ownerUUID"),
                state      = o.optString("state"),
                expired    = o.optBoolean("expired"),
                maxPlayers = o.optInt("maxPlayers")
            )
        }
    }

    /**
     * Realm'a katılma isteği atar ve o anlık sunucu adresini döner.
     * NetherNet (P2P/WebRTC) realmler bu relay mimarisiyle desteklenmez --
     * RakChannelFactory tabanlı relay yalnızca DEFAULT (RakNet/UDP) adres
     * dönen realmlerle çalışabilir.
     */
    suspend fun joinRealm(realmId: Long): RealmAddress = withContext(Dispatchers.IO) {
        val json     = JSONObject(request("GET", "/worlds/$realmId/join"))
        val protocol = json.optString("networkProtocol", "DEFAULT")

        if (protocol != "DEFAULT") {
            throw RealmsException("Bu realm NetherNet (WebRTC) kullanıyor, RakNet relay ile desteklenmiyor")
        }

        val address = json.optString("address")
        val parts   = address.split(":")
        if (parts.size != 2) throw RealmsException("Beklenmeyen adres formatı: $address")

        RealmAddress(
            host            = parts[0],
            port            = parts[1].toIntOrNull() ?: 19132,
            networkProtocol = protocol
        )
    }

    private suspend fun request(method: String, path: String): String {
        val xbl = MicrosoftAuthManager.getRealmsXblToken()
            ?: throw RealmsException("Xbox Live oturumu yok - önce Microsoft hesabıyla giriş yapılmalı")

        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod   = method
            connectTimeout  = 10_000
            readTimeout     = 10_000
            setRequestProperty("Authorization", "XBL3.0 $xbl")
            setRequestProperty("Client-Version", CLIENT_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }

        try {
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text   = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""

            if (code !in 200..299) {
                throw RealmsException("Realms API $path -> HTTP $code: $text", code)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
