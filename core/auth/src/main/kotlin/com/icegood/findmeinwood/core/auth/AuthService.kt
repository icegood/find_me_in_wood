package com.icegood.findmeinwood.core.auth

interface AuthService {
    suspend fun register(email: String, password: String, displayName: String): AuthResult
    suspend fun login(email: String, password: String): AuthResult
    suspend fun linkGoogle(uid: String, googleId: String): AuthResult
    suspend fun signInWithGoogle(googleId: String, email: String?, displayName: String?): AuthResult
    suspend fun getProfile(uid: String): UserProfile?
    suspend fun getCurrentUser(): UserProfile?
    fun setCurrentUserId(uid: String?)
}
