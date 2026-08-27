package app.findmeinwood.core.auth

sealed interface AuthResult {
    data class Success(val profile: UserProfile) : AuthResult
    data class Error(val message: String) : AuthResult
    data object AlreadyLinked : AuthResult
}
