package com.icegood.findmeinwood.core.auth

sealed interface AuthResult {
    data class Success(val profile: UserProfile) : AuthResult
    /** The screen words [reason] in the phone's language. */
    data class Error(val reason: AuthError) : AuthResult
    data object AlreadyLinked : AuthResult
}

enum class AuthError {
    INVALID_INPUT,
    EMAIL_TAKEN,
    REGISTRATION_FAILED,
    INVALID_CREDENTIALS,
    USER_NOT_FOUND,
    GOOGLE_NO_EMAIL,
    EMAIL_LINKED_ELSEWHERE,
}
