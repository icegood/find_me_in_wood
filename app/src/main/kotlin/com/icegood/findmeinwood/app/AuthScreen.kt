package com.icegood.findmeinwood.app

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.icegood.findmeinwood.core.auth.AuthError
import com.icegood.findmeinwood.core.auth.AuthManager
import com.icegood.findmeinwood.core.auth.AuthResult
import com.icegood.findmeinwood.core.auth.UserProfile
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

private const val GOOGLE_ID_TYPE = "type.googleapis.com/googleid"

/** Marks an identity chosen from the device account list (no ID token, local-only). */
internal const val DEVICE_PREFIX = "device:"

internal fun idTokenClaim(idToken: String, claim: String): String? = runCatching {
    val payload = idToken.split('.').drop(1).first()
        .let { Base64.getUrlDecoder().decode(it.padEnd(it.length + (4 - it.length % 4) % 4, '=')) }
        .decodeToString()
    Json.parseToJsonElement(payload).jsonObject[claim]?.jsonPrimitive?.content
}.getOrNull()

/** A sign-in failure in the phone's language. */
internal fun authErrorText(ctx: android.content.Context, e: AuthError): String = ctx.getString(
    when (e) {
        AuthError.INVALID_INPUT -> R.string.auth_invalid_input
        AuthError.EMAIL_TAKEN -> R.string.auth_email_taken
        AuthError.REGISTRATION_FAILED -> R.string.auth_registration_failed
        AuthError.INVALID_CREDENTIALS -> R.string.auth_invalid_credentials
        AuthError.USER_NOT_FOUND -> R.string.auth_user_not_found
        AuthError.GOOGLE_NO_EMAIL -> R.string.auth_google_no_email
        AuthError.EMAIL_LINKED_ELSEWHERE -> R.string.auth_email_linked_elsewhere
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onAuthenticated: (UserProfile) -> Unit,
    authManager: AuthManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isLogin by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val credentialManager = remember { CredentialManager.create(context) }

    suspend fun handleGoogleCredential(cred: GoogleIdTokenCredential): AuthResult? {
        val googleId = cred.id.ifBlank { idTokenClaim(cred.idToken, "sub") ?: return null }
        val mail = idTokenClaim(cred.idToken, "email")
        val name = cred.displayName ?: mail?.substringBefore("@")
        return authManager.signInWithGoogle(googleId, mail, name)
    }

    fun finish(profile: UserProfile) {
        authManager.setCurrentUserId(profile.uid)
        onAuthenticated(profile)
    }

    val accountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            error = context.getString(R.string.auth_no_account_selected)
            return@rememberLauncherForActivityResult
        }
        val mail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (mail.isNullOrBlank()) {
            error = context.getString(R.string.auth_no_account_selected)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            loading = true
            val res = authManager.signInWithGoogle(DEVICE_PREFIX + mail, mail, mail.substringBefore("@"))
            loading = false
            when (res) {
                is AuthResult.Success -> finish(res.profile)
                is AuthResult.Error -> error = authErrorText(context, res.reason)
                is AuthResult.AlreadyLinked -> error = context.getString(R.string.auth_already_linked)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (isLogin) R.string.auth_sign_in else R.string.auth_register)) }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isLogin) {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.auth_display_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.auth_email)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        val result = if (isLogin) {
                            authManager.login(email, password)
                        } else {
                            authManager.register(email, password, displayName.ifBlank { email.substringBefore("@") })
                        }
                        loading = false
                        when (result) {
                            is AuthResult.Success -> onAuthenticated(result.profile)
                            is AuthResult.Error -> error = authErrorText(context, result.reason)
                            is AuthResult.AlreadyLinked -> error = context.getString(R.string.auth_already_linked)
                        }
                    }
                },
                enabled = !loading && email.isNotBlank() && password.length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (loading) R.string.please_wait else if (isLogin) R.string.auth_sign_in else R.string.auth_register))
            }
            OutlinedButton(
                onClick = {
                    error = null
                    if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                        // No OAuth client configured: pick the Google account already on the device.
                        runCatching {
                            accountLauncher.launch(
                                AccountManager.newChooseAccountIntent(
                                    null, null, arrayOf("com.google"), null, null, null, null,
                                ),
                            )
                        }.onFailure { error = context.getString(R.string.auth_no_google_account) }
                        return@OutlinedButton
                    }
                    scope.launch {
                        loading = true
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(
                                GetGoogleIdOption.Builder()
                                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                    .setFilterByAuthorizedAccounts(false)
                                    .setAutoSelectEnabled(false)
                                    .build(),
                            )
                            .build()
                        val result = runCatching {
                            credentialManager.getCredential(context = context, request = request)
                        }
                        loading = false
                        result.fold(
                            onSuccess = { getCred ->
                                val cred = getCred.credential
                                if (cred is CustomCredential && cred.type == GOOGLE_ID_TYPE) {
                                    val g = GoogleIdTokenCredential.createFrom(cred.data)
                                    when (val res = handleGoogleCredential(g)) {
                                        is AuthResult.Success -> {
                                            authManager.setCurrentUserId(res.profile.uid)
                                            onAuthenticated(res.profile)
                                        }
                                        is AuthResult.Error -> error = authErrorText(context, res.reason)
                                        is AuthResult.AlreadyLinked -> error = context.getString(R.string.auth_already_linked)
                                        null -> error = context.getString(R.string.auth_no_id_token)
                                    }
                                } else {
                                    error = context.getString(R.string.auth_unexpected_credential)
                                }
                            },
                            onFailure = { e ->
                                error = context.getString(R.string.auth_google_failed, e.message ?: e.javaClass.simpleName)
                            },
                        )
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.auth_continue_google))
            }
            TextButton(onClick = { isLogin = !isLogin; error = null }) {
                Text(stringResource(if (isLogin) R.string.auth_to_register else R.string.auth_to_sign_in))
            }
        }
    }
}
