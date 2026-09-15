package com.icegood.findmeinwood.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.icegood.findmeinwood.core.auth.AuthManager
import com.icegood.findmeinwood.core.auth.UserProfile
import com.icegood.findmeinwood.core.chat.ChatRepository
import com.icegood.findmeinwood.core.p2p.MultiProtocolP2PManager
import com.icegood.findmeinwood.transport.lora.BleNodeLink
import com.icegood.findmeinwood.core.session.GnssSource
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.feature.map.MapScreen
import com.icegood.findmeinwood.transport.share.ShareTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    /**
     * Runtime permissions are requested per OS level: the BLUETOOTH_* / NEARBY_WIFI_DEVICES
     * names only exist from API 31/33, and asking for an unknown permission makes
     * checkSelfPermission report "denied" forever (which silently blocked Start on API 29).
     */
    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT < 31) {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val shareTransport by lazy { ShareTransport(this) }
    private val authManager by lazy { AuthManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(permissions.toTypedArray())
        setContent {
            MaterialTheme {
                var currentUser by remember { mutableStateOf<UserProfile?>(null) }
                LaunchedEffect(Unit) {
                    authManager.setCurrentUserId(Prefs.currentUid(this@MainActivity))
                    currentUser = authManager.getCurrentUser()
                }
                if (currentUser == null) {
                    AuthScreen(
                        onAuthenticated = {
                            Prefs.setCurrentUid(this@MainActivity, it.uid)
                            currentUser = it
                        },
                        authManager = authManager,
                    )
                } else {
                    MainApp(user = currentUser!!, onLogout = {
                        Prefs.setCurrentUid(this@MainActivity, null)
                        currentUser = null
                    })
                }
            }
        }
    }

    @Composable
    private fun MainApp(user: UserProfile, onLogout: () -> Unit) {
        var tab by remember { mutableStateOf("networks") }
        var mapTileSource by remember {
            mutableStateOf(
                runCatching {
                    com.icegood.findmeinwood.feature.map.TileSource.valueOf(Prefs.tileSource(this@MainActivity))
                }.getOrDefault(com.icegood.findmeinwood.feature.map.TileSource.OFFLINE),
            )
        }
        var openChat by remember { mutableStateOf<String?>(null) }
        val profile by SessionBus.profile.collectAsState()
        val running by SessionBus.sessionRunning.collectAsState()
        val scope = rememberCoroutineScope()
        val chatRepo = remember { mutableStateOf<ChatRepository?>(null) }

        LaunchedEffect(profile) {
            val p = profile ?: return@LaunchedEffect
            // The service may still be starting: without retrying here the inbox is
            // never wired and every incoming chat message is dropped for the session.
            while (chatRepo.value == null) {
                val manager = SessionService.activeManager
                if (manager != null) {
                    val repo = ChatRepository(this@MainActivity, manager, p.myMemberId, scope)
                    chatRepo.value = repo
                    scope.launch {
                        SessionBus.incomingPayloads.collect { (payload, sender) ->
                            repo.handleIncomingPayload(payload, sender)
                        }
                    }
                } else {
                    delay(500)
                }
            }
        }

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
                NavigationBarItem(
                    selected = tab == "chat", onClick = { tab = "chat" },
                    icon = {}, label = { Text("Chat") },
                )
                NavigationBarItem(
                    selected = tab == "peers", onClick = { tab = "peers" },
                    icon = {}, label = { Text("Peers") },
                )
                NavigationBarItem(
                    selected = tab == "discover", onClick = { tab = "discover" },
                    icon = {}, label = { Text("Discover") },
                )
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                val repo = chatRepo.value
                when {
                    tab == "chat" && openChat != null && repo != null && running -> {
                        ChatScreen(openChat!!, repo, onBack = { openChat = null })
                    }
                    tab == "chat" && repo != null && running -> {
                        ChatListScreen(repo, onSelectChannel = { openChat = it })
                    }
                    tab == "chat" -> ChatListPlaceholder()
                    tab == "map" -> MapScreen(
                        profile = SessionBus.profile,
                        peers = SessionBus.peers,
                        tileSource = runCatching {
                            com.icegood.findmeinwood.feature.map.TileSource.valueOf(Prefs.tileSource(this@MainActivity))
                        }.getOrDefault(com.icegood.findmeinwood.feature.map.TileSource.OFFLINE),
                        onTileSourceChange = {
                            Prefs.setTileSource(this@MainActivity, it.name)
                            mapTileSource = it
                        },
                    )
                    tab == "peers" -> com.icegood.findmeinwood.feature.map.PeerList(SessionBus.peers, null)
                    tab == "discover" -> DiscoverScreen(
                        context = this@MainActivity,
                        onJoin = { netId -> DiscoveryBridge.requestJoin(netId) },
                    )
                    else -> NetworksScreen()
                }
            }
        }
    }

    @Composable
    private fun ChatListPlaceholder() {
        val running by SessionBus.sessionRunning.collectAsState()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat", style = MaterialTheme.typography.headlineSmall)
            if (running) {
                Text(
                    "Waiting to collect peers…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Start a network session to enable mesh chat.\n" +
                    "Chat messages are relayed through all connected peers via BLE, WiFi Direct, and LoRa.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NetworksScreen() {
        var profiles by remember { mutableStateOf(ProfileStore.load(this)) }
        var showCreate by remember { mutableStateOf(false) }
        var showJoin by remember { mutableStateOf(false) }
        var joinCode by remember { mutableStateOf<String?>(null) }
        var showNodePicker by remember { mutableStateOf(false) }
        var createError by remember { mutableStateOf<String?>(null) }
        var intervalMs by remember { mutableLongStateOf(Prefs.intervalMs(this@MainActivity)) }
        var intervalInput by remember { mutableStateOf((Prefs.intervalMs(this@MainActivity) / 1000).toString()) }
        var intervalError by remember { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val diag by SessionBus.diagnostics.collectAsState()
        val lastWire by SessionBus.lastWireFrame.collectAsState()
        val running by SessionBus.sessionRunning.collectAsState()
        val radios by SessionBus.radioStates.collectAsState()
        var p2pOwner by remember { mutableStateOf(Prefs.p2pGroupOwner(this@MainActivity)) }

        Scaffold(topBar = { TopAppBar(title = { Text("find me in wood") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create network") }
                OutlinedButton(
                    onClick = { showJoin = true; createError = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join network") }
                createError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { p ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${p.policy} · ${p.myMemberIdHex.take(8)}…" +
                                        (if (Prefs.unverified(this@MainActivity, p.networkIdHex)) " · unverified" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (!granted()) return@Button
                                        SessionService.start(this@MainActivity, p)
                                    }) { Text(if (running) "Restart" else "Start") }
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
                OutlinedButton(
                    onClick = {
                        val wire = SessionBus.lastWireFrame.value
                        scope2.launch {
                            val r = wire?.let { shareTransport.shareBeacon(it) }
                            snackbar.showSnackbar(
                                when (r) {
                                    is SendResult.HandedToUser -> "shared beacon file (.fmiw1)"
                                    is SendResult.Sent -> "shared beacon file (.fmiw1)"
                                    else -> "no beacon sent yet / no carrier"
                                }
                            )
                        }
                    },
                    enabled = lastWire != null && running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share position via Viber…") }
                Text(
                    "session: " + if (running) "RUNNING" else "stopped",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (radios.isNotEmpty()) {
                    Text(
                        radios.entries.sortedBy { it.key.name }
                            .joinToString(" · ") { (t, st) -> "${t.name} ${st.name.lowercase()}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Wi-Fi Direct", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (p2pOwner) {
                                "this phone hosts the group (server, 192.168.49.1)"
                            } else {
                                "role negotiated by the system"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p2pOwner) Button(onClick = { }) { Text("Host") }
                            else OutlinedButton(onClick = {
                                p2pOwner = true
                                SessionService.setP2pGroupOwner(this@MainActivity, true)
                            }) { Text("Host") }
                            if (!p2pOwner) Button(onClick = { }) { Text("Auto") }
                            else OutlinedButton(onClick = {
                                p2pOwner = false
                                SessionService.setP2pGroupOwner(this@MainActivity, false)
                            }) { Text("Auto") }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Beacon interval", style = MaterialTheme.typography.titleSmall)
                        Text("every ${intervalMs / 1000}s — position is sent automatically (clipboard for messengers)",
                            style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = intervalInput,
                                onValueChange = { intervalInput = it; intervalError = null },
                                label = { Text("seconds") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp),
                            )
                            Button(onClick = {
                                val sec = intervalInput.trim().toLongOrNull()
                                if (sec == null || sec <= 5L || sec > Prefs.MAX_INTERVAL_MS / 1000) {
                                    intervalError = "enter seconds > 5 (max ${Prefs.MAX_INTERVAL_MS / 1000})"
                                } else {
                                    Prefs.setIntervalMs(this@MainActivity, sec * 1000L)
                                    intervalMs = sec * 1000L
                                    intervalError = null
                                }
                            }) { Text("Set") }
                        }
                        intervalError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
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
            }
        }

        if (showCreate) {
            CreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { name, passphrase ->
                    when (val r = ProfileStore.create(name, passphrase)) {
                        is CreateResult.Ok -> {
                            ProfileStore.add(this, r.profile)
                            Prefs.setSecretFor(this, r.profile.networkIdHex, r.joinCode.split("#")[1])
                            profiles = ProfileStore.load(this)
                            showCreate = false
                            createError = null
                            joinCode = r.joinCode
                        }
                        is CreateResult.Error -> createError = r.message
                    }
                },
            )
        }
        if (showJoin) {
            JoinDialog(
                onDismiss = { showJoin = false },
                onJoin = { code ->
                    when (val r = ProfileStore.join(code)) {
                        is JoinResult.Ok -> {
                            ProfileStore.add(this, r.profile)
                            Prefs.setSecretFor(this, r.profile.networkIdHex, code.split("#")[1])
                            profiles = ProfileStore.load(this)
                            showJoin = false
                            createError = null
                        }
                        is JoinResult.Error -> createError = r.message
                    }
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
    private fun JoinDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Join network") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste the join code you were given (name#secret#vtag).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = it },
                        label = { Text("Join code") }, singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = code.isNotBlank(), onClick = { onJoin(code.trim()) }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
