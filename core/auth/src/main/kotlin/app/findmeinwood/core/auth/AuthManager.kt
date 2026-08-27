package app.findmeinwood.core.auth

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun getProfile(uid: String): UserProfile? =
        withContext(Dispatchers.IO) { store.queryById(uid) }

    override suspend fun getCurrentUser(): UserProfile? =
        withContext(Dispatchers.IO) { currentUserId?.let { store.queryById(it) } }

    override fun setCurrentUserId(uid: String?) {
        currentUserId = uid
    }
}
