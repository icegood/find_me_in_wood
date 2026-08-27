package app.findmeinwood.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.findmeinwood.core.auth.AuthManager
import app.findmeinwood.core.auth.AuthResult
import app.findmeinwood.core.auth.UserProfile
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

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

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("YOUR_WEB_CLIENT_ID") // TODO: replace with real client ID
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                scope.launch {
                    val profile = authManager.getCurrentUser()
                    if (profile != null && account.id != null) {
                        authManager.linkGoogle(profile.uid, account.id!!)
                        onAuthenticated(profile)
                    }
                }
            } catch (e: ApiException) {
                error = "Google sign-in failed: ${e.statusCode}"
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
                    googleLauncher.launch(googleSignInClient.signInIntent)
                },
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
