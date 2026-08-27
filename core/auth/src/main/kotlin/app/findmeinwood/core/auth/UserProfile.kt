package app.findmeinwood.core.auth

data class UserProfile(
    val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String? = null,
    val googleId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
