package com.rubidiumclient.auth

sealed class AuthState {

    object Idle : AuthState()

    object Loading : AuthState()

    object WaitingForWebView : AuthState()

    data class WaitingForUser(
        val userCode: String,
        val verificationUri: String
    ) : AuthState()

    data class Success(
        val gamertag: String,
        val mcToken: String
    ) : AuthState()

    data class Error(val message: String) : AuthState()

    val isLoggedIn: Boolean get() = this is Success
    val isLoading:  Boolean get() = this is Loading || this is WaitingForUser || this is WaitingForWebView

    fun isRelayReady(): Boolean =
        this is Success && mcToken.isNotBlank()
}
