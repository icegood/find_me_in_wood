package com.icegood.findmeinwood.core.auth

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Identities picked from the device account list are upgradable to real Google IDs. */
const val DEVICE_PREFIX = "device:"

class AuthManager(private val context: Context) : AuthService {

    private val store by lazy { SQLiteAuthStore(context) }
    private var currentUserId: String? = null

    override suspend fun register(email: String, password: String, displayName: String): AuthResult =
        withContext(Dispatchers.IO) {
            if (email.isBlank() || password.length < 6) {
                return@withContext AuthResult.Error("Invalid email or password too short")
            }
            if (store.queryByEmail(email) != null) {
                return@withContext AuthResult.Error("Email already registered")
            }
            val hash = SQLiteAuthStore.hashPassword(password)
            val id = store.insertUser(email, hash, displayName)
            val profile = store.queryById(id.toString())
                ?: return@withContext AuthResult.Error("Registration failed")
            currentUserId = profile.uid
            AuthResult.Success(profile)
        }

    override suspend fun login(email: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            val profile = store.verifyPassword(email, password)
                ?: return@withContext AuthResult.Error("Invalid credentials")
            currentUserId = profile.uid
            AuthResult.Success(profile)
        }

    override suspend fun linkGoogle(uid: String, googleId: String): AuthResult =
        withContext(Dispatchers.IO) {
            val existing = store.queryById(uid)
                ?: return@withContext AuthResult.Error("User not found")
            if (existing.googleId != null) {
                return@withContext AuthResult.AlreadyLinked
            }
            store.updateGoogleId(uid, googleId)
            val updated = store.queryById(uid)!!
            AuthResult.Success(updated)
        }

    override suspend fun signInWithGoogle(
        googleId: String,
        email: String?,
        displayName: String?,
    ): AuthResult = withContext(Dispatchers.IO) {
        val byGoogle = store.queryByGoogleId(googleId)
        if (byGoogle != null) {
            currentUserId = byGoogle.uid
            return@withContext AuthResult.Success(byGoogle)
        }
        val mail = email?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return@withContext AuthResult.Error("Google account has no email; cannot sign in")
        val existing = store.queryByEmail(mail)
        if (existing != null) {
            val previous = existing.googleId
            if (previous != null && !previous.startsWith(DEVICE_PREFIX)) {
                return@withContext AuthResult.Error("This email is linked to another Google account")
            }
            store.updateGoogleId(existing.uid, googleId)
            currentUserId = existing.uid
            return@withContext AuthResult.Success(store.queryById(existing.uid)!!)
        }
        val hash = SQLiteAuthStore.hashPassword("google-${googleId}-${System.currentTimeMillis()}")
        val id = store.insertUser(mail, hash, displayName?.ifBlank { null } ?: mail.substringBefore("@"))
        store.updateGoogleId(id.toString(), googleId)
        val profile = store.queryById(id.toString())
            ?: return@withContext AuthResult.Error("Registration failed")
        currentUserId = profile.uid
        AuthResult.Success(profile)
    }

    override suspend fun getProfile(uid: String): UserProfile? =
        withContext(Dispatchers.IO) { store.queryById(uid) }

    override suspend fun getCurrentUser(): UserProfile? =
        withContext(Dispatchers.IO) { currentUserId?.let { store.queryById(it) } }

    override fun setCurrentUserId(uid: String?) {
        currentUserId = uid
    }
}
