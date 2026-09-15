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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
            error = "No Google account selected"
            return@rememberLauncherForActivityResult
        }
        val mail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (mail.isNullOrBlank()) {
            error = "No Google account selected"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            loading = true
            val res = authManager.signInWithGoogle(DEVICE_PREFIX + mail, mail, mail.substringBefore("@"))
            loading = false
            when (res) {
                is AuthResult.Success -> finish(res.profile)
                is AuthResult.Error -> error = res.message
                is AuthResult.AlreadyLinked -> error = "Account already linked"
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (isLogin) "Sign In" else "Register") }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isLogin) {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    label = { Text("Display name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
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
                            is AuthResult.Error -> error = result.message
                            is AuthResult.AlreadyLinked -> error = "Account already linked"
                        }
                    }
                },
                enabled = !loading && email.isNotBlank() && password.length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "Please wait…" else if (isLogin) "Sign In" else "Register")
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
                        }.onFailure { error = "No Google account on this device" }
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
                                        is AuthResult.Error -> error = res.message
                                        is AuthResult.AlreadyLinked -> error = "Account already linked"
                                        null -> error = "Could not read Google ID token"
                                    }
                                } else {
                                    error = "Unexpected credential type"
                                }
                            },
                            onFailure = { e ->
                                error = "Google sign-in failed: ${e.message ?: e.javaClass.simpleName}"
                            },
                        )
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue with Google")
            }
            TextButton(onClick = { isLogin = !isLogin; error = null }) {
                Text(if (isLogin) "Don't have an account? Register" else "Already have an account? Sign in")
            }
        }
    }
}
