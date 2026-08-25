package app.findmeinwood.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.findmeinwood.transport.lora.BleNodeLink
import app.findmeinwood.core.session.GnssSource
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.feature.map.MapScreen
import app.findmeinwood.transport.share.ShareTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val shareTransport by lazy { ShareTransport(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(permissions.toTypedArray())
        setContent {
            MaterialTheme {
                var tab by remember { mutableStateOf("networks") }
                Scaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == "map", onClick = { tab = "map" },
                            icon = {}, label = { Text("Map") },
                        )
                        NavigationBarItem(
                            selected = tab == "networks", onClick = { tab = "networks" },
                            icon = {}, label = { Text("Networks") },
                        )
                    }
                }) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            "map" -> MapScreen(SessionBus.profile, SessionBus.peers)
                            else -> NetworksScreen()
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NetworksScreen() {
        var profiles by remember { mutableStateOf(ProfileStore.load(this)) }
        var showCreate by remember { mutableStateOf(false) }
        var joinCode by remember { mutableStateOf<String?>(null) }
        var showNodePicker by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val diag by SessionBus.diagnostics.collectAsState()

        Scaffold(topBar = { TopAppBar(title = { Text("find me in wood") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create network") }
                OutlinedButton(
                    onClick = {
                        val wire = SessionBus.lastWireFrame.value
                        scope2.launch {
                            val r = wire?.let { shareTransport.send(it) }
                            snackbar.showSnackbar(
                                when (r) {
                                    is SendResult.HandedToUser -> "handed to messenger"
                                    is SendResult.Sent -> "handed to messenger"
                                    else -> "no beacon sent yet / no carrier"
                                }
                            )
                        }
                    },
                    enabled = SessionBus.lastWireFrame.value != null && SessionService.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share position via Viber…") }
                Text(
                    "session: " + if (SessionService.isRunning) "RUNNING" else "stopped",
                    style = MaterialTheme.typography.labelMedium,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("LoRa node", style = MaterialTheme.typography.titleSmall)
                        Text(
                            Prefs.loraNode(this@MainActivity) ?: "auto (scan by service UUID)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { showNodePicker = true }) { Text("Choose node…") }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "sent ${diag.sent} · rx ${diag.received} · relayed ${diag.relayed}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "auth-failed ${diag.authFailed} · dropped ${diag.dropped}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(profiles) { p ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${p.policy} · ${p.myMemberIdHex.take(8)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (!granted()) return@Button
                                        SessionService.start(this@MainActivity, p)
                                    }) { Text(if (SessionService.isRunning) "Restart" else "Start") }
                                    OutlinedButton(onClick = { SessionService.stop(this@MainActivity) }) {
                                        Text("Stop")
                                    }
                                    TextButton(onClick = {
                                        runBlocking { ProfileStore.remove(this@MainActivity, p.networkIdHex) }
                                        profiles = ProfileStore.load(this@MainActivity)
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreate) {
            CreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { name, passphrase ->
                    val (sp, code) = ProfileStore.create(name, passphrase)
                    ProfileStore.add(this, sp)
                    profiles = ProfileStore.load(this)
                    showCreate = false
                    joinCode = code
                },
            )
        }
        if (showNodePicker) {
            NodePickerDialog(
                onDismiss = { showNodePicker = false },
                onPick = { addr ->
                    Prefs.setLoraNode(this@MainActivity, addr)
                    showNodePicker = false
                    profiles = profiles // no-op refresh
                },
            )
        }
        joinCode?.let { code ->
            AlertDialog(
                onDismissRequest = { joinCode = null },
                confirmButton = { TextButton(onClick = { joinCode = null }) { Text("OK") } },
                title = { Text("Share the join code") },
                text = {
                    Text("Give this to members in person:\n\n$code\n\nIt is shown once — the secret is not stored.")
                },
            )
        }
    }

    private val scope2 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    @Composable
    private fun NodePickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
        val nodes = remember { mutableStateOf(listOf<BleNodeLink.ScannedNode>()) }
        LaunchedEffect(Unit) {
            val link = BleNodeLink(this@MainActivity)
            link.scan().collect { nodes.value = nodes.value + it }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose your LoRa node") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (nodes.value.isEmpty()) Text("Scanning (Nordic-UART nodes)…")
                    nodes.value.forEach { n ->
                        TextButton(onClick = { onPick(n.address) }) {
                            Text((n.name?.plus(" ") ?: "") + n.address, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }

    @Composable
    private fun CreateDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Create network") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Network name") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("Passphrase (empty = generate)") }, singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.length in 1..64,
                    onClick = { onCreate(name.trim(), pass) },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    private fun granted(): Boolean = permissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
