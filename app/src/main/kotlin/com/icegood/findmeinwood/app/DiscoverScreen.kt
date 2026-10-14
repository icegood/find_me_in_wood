package com.icegood.findmeinwood.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.session.JoinOutcome

/**
 * FR-8.2/FR-8.3: networks heard around us. Joining one takes only its password (typed
 * here and checked against the network on the spot), or a member nearby can let us in
 * without it. Works with no network of our own: [onListen] runs the radios just to listen.
 */
@Composable
fun DiscoverScreen(
    context: android.content.Context,
    onListen: () -> Unit,
    onStart: (StoredProfile) -> Unit,
) {
    val discovered by SessionBus.discovered.collectAsState()
    // Re-check system switches (Location) every few seconds: the user flips them elsewhere.
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3_000); tick++ } }
    val outcome by SessionBus.joinOutcome.collectAsState()
    val running by SessionBus.sessionRunning.collectAsState()
    val listening by SessionBus.listening.collectAsState()
    val current by SessionBus.profile.collectAsState()
    var passwordFor by remember { mutableStateOf<DiscoveredNetwork?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Networks around you", style = MaterialTheme.typography.titleMedium)
        val blocked = tick >= 0 && DiscoveryBlockers(context, finding = true)
        when {
            blocked -> Unit
            !running -> {
                Text(
                    "Turn on the radios to see networks nearby. A network shows up only while " +
                        "one of its members has discovery on (Start discovery or Invite on their phone).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onListen, modifier = Modifier.fillMaxWidth()) { Text("Look for networks") }
            }
            listening -> Text(
                "Looking… a network appears within seconds once a member nearby has discovery on " +
                    "(Start discovery or Invite on their phone).",
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(
                "Listening while “${current?.name ?: "your network"}” runs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val mine = ProfileStore.load(context).associateBy { it.networkIdHex }
        discovered.forEach { net ->
            val stored = mine[net.networkId.bytes.joinToString("") { "%02x".format(it) }]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(net.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)

                    if (stored != null) {
                        Text("You are a member.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onStart(stored) }) { Text("Start") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { passwordFor = net }) { Text("Enter password") }
                            OutlinedButton(onClick = {
                                DiscoveryBridge.requestJoin(net.networkId)
                                asked = net.name ?: "the network"
                            }) { Text("Ask a member") }
                        }
                        if (asked != null && asked == (net.name ?: "the network")) {
                            Text(
                                "Asked. The network's master must tap Accept on their phone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        outcome?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    when (result) {
                        is JoinOutcome.Joined -> Column {
                            Text(
                                "Joined “${result.name}”. Check that the member who let you in sees " +
                                    "the same code: ${result.sas}",
                            )
                            TextButton(onClick = {
                                Prefs.setUnverifiedCode(context, ProfileStore.networkIdOf(result.secret).bytes.joinToString("") { "%02x".format(it) }, null)
                                SessionBus.publishJoinOutcome(null)
                            }) { Text("The codes matched") }
                        }
                        is JoinOutcome.Rejected -> Text("Not let in: ${result.reason}")
                    }
                }
            }
        }

        JoinRequests(current?.name)
    }

    passwordFor?.let { net ->
        PasswordDialog(
            title = "Join “${net.name ?: "network"}”",
            onDismiss = { passwordFor = null },
            onJoin = { password ->
                when (val r = ProfileStore.join(net.name ?: "network", password, expected = net.networkId)) {
                    is JoinResult.Ok -> {
                        ProfileStore.add(context, r.profile)
                        Prefs.setSecretFor(context, r.profile.networkIdHex, password)
                        passwordFor = null
                        onStart(r.profile)
                        null
                    }
                    is JoinResult.Error -> r.message
                }
            },
        )
    }
}

private fun bluetoothOn(ctx: android.content.Context): Boolean = runCatching {
    ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled != false
}.getOrDefault(true)

/**
 * FR-8.8: says in red what stops discovery here (Bluetooth or Location off), with buttons
 * to turn it on; true when something does. Called again every few seconds by its screen.
 */
@Composable
internal fun DiscoveryBlockers(ctx: android.content.Context, finding: Boolean): Boolean {
    val bt = bluetoothOn(ctx)
    val loc = locationOn(ctx)
    val why = UiText.discoveryBlocked(bt, loc, finding) ?: return false
    fun open(intent: android.content.Intent) = runCatching {
        ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!bt) Button(onClick = {
            open(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)).onFailure {
                open(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }) { Text("Turn on Bluetooth") }
        if (!loc) Button(onClick = {
            open(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }) { Text("Turn on Location") }
    }
    return true
}

private fun locationOn(ctx: android.content.Context): Boolean = runCatching {
    ctx.getSystemService(android.location.LocationManager::class.java)
        ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: true
}.getOrDefault(true)

/** Password entry; [onJoin] returns an error to show, or null when done. */
@Composable
internal fun PasswordDialog(
    title: String,
    onDismiss: () -> Unit,
    askName: Boolean = false,
    onJoin: (String) -> String?,
    onJoinNamed: ((String, String) -> String?)? = null,
    /** Filled in from an invite QR code opened with the phone's camera app (FR-2.6). */
    initial: JoinInvite? = null,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Keyboards like to append a space; no password starts or ends with one.
    fun join(n: String, pw: String) = if (askName) onJoinNamed?.invoke(n.trim(), pw.trim()) else onJoin(pw.trim())
    // A scanned invite joins at once: name and password come from the master's phone.
    val scan = rememberInviteScanner { invite ->
        if (invite == null) {
            error = "That QR code is not a network invite."
        } else {
            name = invite.name
            password = invite.password
            error = join(invite.name, invite.password)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (askName) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; error = null },
                        label = { Text("Network name (your label)") }, singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                )
                OutlinedButton(onClick = scan) { Text("Scan QR") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && (!askName || name.isNotBlank()),
                onClick = { error = join(name, password) },
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Join requests waiting for this phone (the master) and the code of the last one let in. */
@Composable
internal fun JoinRequests(networkName: String?) {
    val pending by SessionBus.pendingJoins.collectAsState()
    val sas by SessionBus.ownerSas.collectAsState()
    val note by SessionBus.ownerNote.collectAsState()
    if (sas != null || note != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sas?.let { Text("Code $it — the person joining must see the same", style = MaterialTheme.typography.bodyMedium) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (pending.isNotEmpty()) {
        Text("Asking to join “${networkName ?: "your network"}”", style = MaterialTheme.typography.titleMedium)
        pending.forEach { req ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        req.joinerNick ?: "device ${req.joinerId.bytes.take(4).joinToString("") { "%02x".format(it) }}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { DiscoveryBridge.approve(req.joinerId) }) { Text("Accept") }
                        OutlinedButton(onClick = { DiscoveryBridge.reject(req.joinerId) }) { Text("Reject") }
                    }
                }
            }
        }
    }
}
