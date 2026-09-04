package com.rubidiumclient.core.relay

import android.util.Base64
import org.json.JSONObject

data class ClientIdentification(
    val displayName      : String,
    val xuid             : String,
    val identity         : String,
    val deviceOs         : Int,
    val languageCode     : String,
    val clientVersion    : String,
    val identityPublicKey: String,
    val chainJson        : String,
    val extraJson        : String
) {
    companion object {
        private const val TAG = "ClientIdentification"

        fun fromLogin(chainJson: String, extraJson: String): ClientIdentification? {
            return try {
                parseChain(chainJson, extraJson)
            } catch (e: Exception) {
                null
            }
        }

        private fun parseChain(chainJson: String, extraJson: String): ClientIdentification {
            val root  = JSONObject(chainJson)
            val chain = root.optJSONArray("chain")

            var displayName : String = if (chain == null) "Token-based" else "Unknown"
            var xuid        : String = ""
            var identity    : String = ""
            var idPubKey    : String = ""

            if (chain != null) {
                for (i in 0 until chain.length()) {
                    val jwt     = chain.getString(i)
                    val payload = decodeJwtPayload(jwt) ?: continue

                    val ipk = payload.optString("identityPublicKey", "")
                    if (ipk.isNotBlank()) idPubKey = ipk

                    if (payload.has("extraData")) {
                        val extra = payload.getJSONObject("extraData")
                        displayName = extra.optString("displayName", "Unknown")
                        xuid        = extra.optString("XUID", "")
                        identity    = extra.optString("identity", "")
                    }
                }
            }

            var deviceOs      = 0
            var languageCode  = "en_US"
            var clientVersion = ""

            val skinPayload = decodeJwtPayload(extraJson)
            if (skinPayload != null) {
                deviceOs      = skinPayload.optInt("DeviceOS", 0)
                languageCode  = skinPayload.optString("LanguageCode", "en_US")
                clientVersion = skinPayload.optString("GameVersion", "")
            }

            return ClientIdentification(
                displayName       = displayName,
                xuid              = xuid,
                identity          = identity,
                deviceOs          = deviceOs,
                languageCode      = languageCode,
                clientVersion     = clientVersion,
                identityPublicKey = idPubKey,
                chainJson         = chainJson,
                extraJson         = extraJson
            )
        }

        private fun decodeJwtPayload(jwt: String): JSONObject? {
            return try {
                val parts = jwt.split(".")
                if (parts.size < 2) return null
                val padded = parts[1]
                    .replace('-', '+').replace('_', '/')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                val bytes = Base64.decode(padded, Base64.DEFAULT)
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun toString() =
        "ClientIdentification(name=$displayName xuid=$xuid os=$deviceOs ver=$clientVersion)"
}
