package com.nuvio.tv.domain.model

sealed class AuthState {
    data object SignedOut : AuthState()
    data object Loading : AuthState()
    data class FullAccount(val userId: String, val email: String) : AuthState()
}
