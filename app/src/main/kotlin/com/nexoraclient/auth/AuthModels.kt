package com.rubidiumclient.auth

data class SavedAccount(
    val gamertag: String,
    val refreshToken: String,
    val mcToken: String,
    val expireTimeMs: Long,
    val xuid: String = "",

    val privateKeyB64: String = "",

    val publicKeyB64: String = ""
) {

    fun isExpired(bufferMs: Long = 5 * 60 * 1000L): Boolean =
        System.currentTimeMillis() >= expireTimeMs - bufferMs

    fun isRelayReady(): Boolean = mcToken.isNotBlank() && privateKeyB64.isNotBlank() && !isExpired()

    override fun toString(): String =
        "SavedAccount(gamertag=$gamertag, xuid=$xuid, expired=${isExpired()})"
}

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val tokenType: String = "bearer"
)

data class XblAuthResult(
    val token: String,
    val userHash: String,
    val gamertag: String = ""
)

data class McAuthResult(
    val chainJson: String,
    val gamertag: String = "",
    val privateKeyB64: String = "",
    val publicKeyB64: String = ""
)
