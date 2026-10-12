package com.icegood.findmeinwood.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import com.icegood.findmeinwood.core.model.ThemeMode
import com.icegood.findmeinwood.feature.map.OfflineRegion
import com.icegood.findmeinwood.feature.map.OfflineRegions
import com.icegood.findmeinwood.feature.map.ProviderStat
import com.icegood.findmeinwood.feature.map.TileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    /** SMS rung (LNP/3): send + receive, and phone state for the satellite note in the log. */
    private val smsPermissions = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
    )
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val shareTransport by lazy { ShareTransport(this) }
    private val authManager by lazy { AuthManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(permissions.toTypedArray())
        MemberNames.load(this)
        setContent {
            var themeMode by remember { mutableStateOf(Prefs.themeMode(this@MainActivity)) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The window chrome (status/nav bar icons + launch background) follows the
            // explicit choice too, otherwise it would keep tracking the system setting.
            LaunchedEffect(darkTheme) {
                val w = window
                w.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(
                        if (darkTheme) 0xFF1A1A1A.toInt() else android.graphics.Color.WHITE,
                    ),
                )
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                var currentUser by remember { mutableStateOf<UserProfile?>(null) }
                LaunchedEffect(Unit) {
                    authManager.setCurrentUserId(Prefs.currentUid(this@MainActivity))
                    currentUser = authManager.getCurrentUser()
                    Prefs.setCurrentDisplayName(this@MainActivity, currentUser?.displayName)
                }
                if (currentUser == null) {
                    AuthScreen(
                        onAuthenticated = {
                            Prefs.setCurrentUid(this@MainActivity, it.uid)
                            Prefs.setCurrentDisplayName(this@MainActivity, it.displayName)
                            currentUser = it
                        },
                        authManager = authManager,
                    )
                } else {
                    MainApp(
                        user = currentUser!!,
                        onLogout = {
                            Prefs.setCurrentUid(this@MainActivity, null)
                            Prefs.setCurrentDisplayName(this@MainActivity, null)
                            currentUser = null
                        },
                        themeMode = themeMode,
                        onThemeModeChange = {
                            Prefs.setThemeMode(this@MainActivity, it)
                            themeMode = it
                        },
                        darkTheme = darkTheme,
                    )
                }
            }
        }
    }

    private fun smsPermitted() = smsPermissions.take(2).all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun MainApp(
        user: UserProfile,
        onLogout: () -> Unit,
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit,
        darkTheme: Boolean,
    ) {
        var tab by remember { mutableStateOf("networks") }
        var mapTileSource by remember {
            mutableStateOf(
                runCatching {
                    com.icegood.findmeinwood.feature.map.TileSource.valueOf(Prefs.tileSource(this@MainActivity))
                }.getOrDefault(com.icegood.findmeinwood.feature.map.TileSource.OFFLINE),
            )
        }
        // Tile API keys are per *provider* (not per TileSource): OSM resolves to Mapnik
        // in light theme and CARTO dark in dark theme, which are different services.
        var mapTileKeys by remember {
            mutableStateOf(
                com.icegood.findmeinwood.feature.map.TileProvider.keyed
                    .associateWith { Prefs.mapTileApiKey(this@MainActivity, it.id) },
            )
        }
        var openChat by remember { mutableStateOf<String?>(null) }
        // The member being searched for: shared by the map and the peer list, kept across tabs.
        var findTarget by remember { mutableStateOf<com.icegood.findmeinwood.core.model.MemberId?>(null) }
        val profile by SessionBus.profile.collectAsState()
        val running by SessionBus.sessionRunning.collectAsState()
        val scope = rememberCoroutineScope()
        val chatRepo = remember { mutableStateOf<ChatRepository?>(null) }
        // Members as shown: their announced names, overridden by labels given on this phone.
        val namedPeers = remember {
            kotlinx.coroutines.flow.combine(SessionBus.peers, MemberNames.labels) { p, l -> MemberNaming.apply(p, l) }
                .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())
        }

        LaunchedEffect(profile) {
            // Always drop the repo bound to the previous manager first: on Restart the
            // profile can go old -> new without an observed null, and the old repo would
            // keep sending into a stopped session.
            chatRepo.value = null
            val p = profile
            if (p == null) {
                findTarget = null // session over: that member is no longer tracked
                return@LaunchedEffect
            }
            // The service may still be starting: without retrying here the inbox is
            // never wired and every incoming chat message is dropped for the session.
            var manager = SessionService.activeManager
            while (manager == null) {
                delay(500)
                manager = SessionService.activeManager
            }
            val repo = ChatRepository(
                this@MainActivity, manager, p.myMemberId, scope,
                Prefs.currentDisplayName(this@MainActivity) ?: user.displayName,
            )
            chatRepo.value = repo
            // Collected inside the effect so it is cancelled with it; SessionBus.clear()
            // swaps the flow, so a collector outliving the session would hear nothing.
            SessionBus.incomingPayloads.collect { (payload, sender) ->
                repo.handleIncomingPayload(payload, sender)
            }
        }

        Scaffold(bottomBar = {
            NavigationBar {
                @Composable
                fun navItem(label: String, selected: Boolean, onClick: () -> Unit) {
                    NavigationBarItem(
                        selected = selected, onClick = onClick,
                        icon = {},
                        // Six items on a narrow screen leave ~60dp each, which is not enough for
                        // "Networks" at the default label size, so it used to wrap onto two lines.
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                }
                navItem("Map", tab == "map") { tab = "map" }
                navItem("Networks", tab == "networks") { tab = "networks" }
                navItem("Chat", tab == "chat") { tab = "chat" }
                navItem("Peers", tab == "peers") { tab = "peers" }
                navItem("Discover", tab == "discover") { tab = "discover" }
                navItem("Settings", tab == "settings") { tab = "settings" }
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
                        peers = namedPeers,
                        isDarkTheme = darkTheme,
                        tileSource = mapTileSource,
                        tileApiKeys = mapTileKeys,
                        onTileSourceChange = {
                            Prefs.setTileSource(this@MainActivity, it.name)
                            mapTileSource = it
                        },
                        findTarget = findTarget,
                        onFindTargetChange = { findTarget = it },
                    )
                    tab == "peers" -> com.icegood.findmeinwood.feature.map.PeerList(
                        namedPeers,
                        onRename = { id, name -> MemberNames.rename(this@MainActivity, id, name) },
                        onSelectLink = { id, t -> SessionService.activeManager?.selectLink(id, t) },
                        emptyText = if (profile == null) {
                            "No network running. Start one in Networks to see its members here."
                        } else {
                            "No members heard yet. They need “${profile?.name}” started and to be " +
                                "within Bluetooth / Wi-Fi range (or reachable through another member)."
                        },
                        findTarget = findTarget,
                        onFind = { id ->
                            findTarget = id
                            if (id != null) tab = "map" // go straight to the arrow
                        },
                    )
                    tab == "discover" -> DiscoverScreen(
                        context = this@MainActivity,
                        onListen = { if (granted()) SessionService.listen(this@MainActivity) else requestRadioPermissions() },
                        onStart = { p -> if (granted()) SessionService.start(this@MainActivity, p) else requestRadioPermissions() },
                    )
                    tab == "settings" -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        tileApiKeys = mapTileKeys,
                        onTileApiKey = { source, key -> mapTileKeys = mapTileKeys + (source to key) },
                    )
                    else -> NetworksScreen(onDiscover = { tab = "discover" })
                }
            }
        }
    }

    @Composable
    private fun ChatListPlaceholder() {
        val running by SessionBus.sessionRunning.collectAsState()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (running && !SessionBus.listening.collectAsState().value) {
                    "Opening chat…"
                } else {
                    "Start a network in Networks to chat with its members. Messages hop " +
                        "from phone to phone over Bluetooth and Wi-Fi Direct — no internet needed."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NetworksScreen(onDiscover: () -> Unit) {
        var profiles by remember { mutableStateOf(ProfileStore.load(this)) }
        var showCreate by remember { mutableStateOf(false) }
        var showJoin by remember { mutableStateOf(false) }
        /** Network whose password is being shown for sharing. */
        var sharing by remember { mutableStateOf<StoredProfile?>(null) }
        var deleting by remember { mutableStateOf<StoredProfile?>(null) }
        /** Bumped to re-read the "unverified" flags after the user confirms a code. */
        var verifyTick by remember { mutableStateOf(0) }
        val current by SessionBus.profile.collectAsState()
        val runningId = current?.networkId?.bytes?.joinToString("") { "%02x".format(it) }
        var createError by remember { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val uiScope = rememberCoroutineScope()
        val lastWire by SessionBus.lastWireFrame.collectAsState()
        val listening by SessionBus.listening.collectAsState()
        val radios by SessionBus.radioStates.collectAsState()

        Scaffold(
            topBar = { TopAppBar(title = { Text("find me in wood") }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
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
                ) { Text("Join by password") }
                OutlinedButton(
                    onClick = onDiscover,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Find networks nearby") }
                createError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { p ->
                        val isRunning = runningId == p.networkIdHex
                        val code = if (verifyTick >= 0) Prefs.unverifiedCode(this@MainActivity, p.networkIdHex) else null
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (isRunning) "● running — members nearby can see and find you" else "not running",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (code != null) {
                                    Text(
                                        "Joined without a password. The member who let you in must see " +
                                            "the same code: $code",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = {
                                        Prefs.setUnverifiedCode(this@MainActivity, p.networkIdHex, null)
                                        verifyTick++
                                    }) { Text("The codes matched") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isRunning) {
                                        OutlinedButton(onClick = { SessionService.stop(this@MainActivity) }) { Text("Stop") }
                                    } else {
                                        Button(onClick = {
                                            if (!granted()) { requestRadioPermissions(); return@Button }
                                            SessionService.start(this@MainActivity, p)
                                        }) { Text(if (runningId != null) "Switch to this" else "Start") }
                                    }
                                    OutlinedButton(onClick = { sharing = p }) { Text("Invite") }
                                    TextButton(onClick = { deleting = p }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                if (runningId != null) OutlinedButton(
                    onClick = {
                        val wire = SessionBus.lastWireFrame.value
                        uiScope.launch {
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
                    enabled = lastWire != null && runningId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send my position via messenger…") }
                Text(
                    when {
                        runningId != null -> "Radios"
                        listening -> "Looking for networks to join (see Discover)"
                        else -> "Nothing running: start a network to be found"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                UiText.radios(radios).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (runningId != null || listening) RadioSwitches(radios)
            }
        }

        if (showCreate) {
            CreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { name, passphrase ->
                    when (val r = ProfileStore.create(name, passphrase)) {
                        is CreateResult.Ok -> {
                            ProfileStore.add(this, r.profile)
                            Prefs.setSecretFor(this, r.profile.networkIdHex, r.password)
                            profiles = ProfileStore.load(this)
                            showCreate = false
                            createError = null
                            sharing = r.profile
                        }
                        is CreateResult.Error -> createError = r.message
                    }
                },
            )
        }
        if (showJoin) {
            PasswordDialog(
                title = "Join by password",
                askName = true,
                onDismiss = { showJoin = false },
                onJoin = { null },
                onJoinNamed = { name, password ->
                    // If the network is around, take its real name and catch a mistyped password.
                    val heard = SessionBus.discovered.value
                        .firstOrNull { it.networkId == ProfileStore.networkIdOf(password) }
                    when (val r = ProfileStore.join(heard?.name ?: name, password)) {
                        is JoinResult.Ok -> {
                            ProfileStore.add(this, r.profile)
                            Prefs.setSecretFor(this, r.profile.networkIdHex, password)
                            profiles = ProfileStore.load(this)
                            showJoin = false
                            createError = null
                            null
                        }
                        is JoinResult.Error -> r.message
                    }
                },
            )
        }
        deleting?.let { p ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete “${p.name}”?") },
                text = {
                    Text(
                        "This phone leaves the network and forgets its password. To come back you " +
                            "need the password again or a member to let you in.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (runningId == p.networkIdHex) SessionService.stop(this@MainActivity)
                        runBlocking { ProfileStore.remove(this@MainActivity, p.networkIdHex) }
                        Prefs.setSecretFor(this@MainActivity, p.networkIdHex, null)
                        profiles = ProfileStore.load(this@MainActivity)
                        deleting = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
        sharing?.let { p ->
            InviteDialog(p, Prefs.secretFor(this, p.networkIdHex)) { sharing = null }
        }
    }

    /**
     * A radio that is switched off is the usual reason nobody is found: offer to turn it on
     * right here. Re-checked every few seconds, since the user flips it in system UI.
     */
    @Composable
    private fun RadioSwitches(radios: Map<com.icegood.findmeinwood.core.model.TransportId, com.icegood.findmeinwood.transport.api.RadioState>) {
        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
        val btOff = tick >= 0 && runCatching {
            getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == false
        }.getOrDefault(false)
        val wifiOff = tick >= 0 && runCatching {
            getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == false
        }.getOrDefault(false)
        val locationOff = tick >= 0 && runCatching {
            getSystemService(android.location.LocationManager::class.java)?.let {
                !androidx.core.location.LocationManagerCompat.isLocationEnabled(it)
            } ?: false
        }.getOrDefault(false)
        if (locationOff) {
            Text(
                "Location is off: Android then hides other phones from Bluetooth and Wi-Fi " +
                    "Direct searches, so nobody can be found — and your own position is unknown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("Turn on Location") }
        }
        if (btOff) {
            OutlinedButton(onClick = {
                runCatching {
                    startActivity(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.onFailure { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Turn on Bluetooth") }
        }
        if (wifiOff) {
            Text(
                "Wi-Fi is off: Wi-Fi Direct (longer range, photos) cannot work. No Wi-Fi network is needed, only Wi-Fi on.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                startActivity(
                    android.content.Intent(
                        if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI
                        else android.provider.Settings.ACTION_WIFI_SETTINGS,
                    ),
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Turn on Wi-Fi") }
        }
    }

    /** Shows a network's password, to read out or send to someone who should join. */
    @Composable
    private fun InviteDialog(p: StoredProfile, password: String?, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Invite to “${p.name}”") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (password == null) {
                        Text("This phone does not have the password of this network.")
                    } else {
                        Text("Password", style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(password, style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            "The other phone opens Discover, taps Look for networks, picks " +
                                "“${p.name}” and enters this password. This network must be started here " +
                                "so it can be seen. Out of range: Networks → Join by password.\n\n" +
                                "Or skip the password: they tap Ask a member and you tap Accept in Discover.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                if (password != null) Row {
                    TextButton(onClick = {
                        val clip = android.content.ClipData.newPlainText("network password", password)
                        // Android 13+: keep the password out of the clipboard preview.
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                        // Android 13+ confirms a copy itself; older versions show nothing.
                        if (Build.VERSION.SDK_INT < 33) {
                            android.widget.Toast.makeText(this@MainActivity, "Password copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                "find me in wood — network “${p.name}”, password: $password",
                            )
                        startActivity(android.content.Intent.createChooser(send, "Send password with…"))
                    }) { Text("Send via app…") }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }

    /** Dedicated settings screen: theme, tile API keys and device/beacon options. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit,
        tileApiKeys: Map<com.icegood.findmeinwood.feature.map.TileProvider, String>,
        onTileApiKey: (com.icegood.findmeinwood.feature.map.TileProvider, String) -> Unit,
    ) {
        var keys by remember(tileApiKeys) { mutableStateOf(tileApiKeys) }
        var showNodePicker by remember { mutableStateOf(false) }
        var intervalMs by remember { mutableLongStateOf(Prefs.intervalMs(this@MainActivity)) }
        var intervalInput by remember { mutableStateOf((Prefs.intervalMs(this@MainActivity) / 1000).toString()) }
        var intervalError by remember { mutableStateOf<String?>(null) }
        var p2pOwner by remember { mutableStateOf(Prefs.p2pGroupOwner(this@MainActivity)) }
        val snackbar = remember { SnackbarHostState() }
        val uiScope = rememberCoroutineScope()
        val diag by SessionBus.diagnostics.collectAsState()
        // Only providers that need a key get a field; keyless ones never appear here.
        val providers = com.icegood.findmeinwood.feature.map.TileProvider.keyed

        Scaffold(
            topBar = { TopAppBar(title = { Text("Settings") }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "explicit light / dark, or follow the system",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                if (themeMode == mode) {
                                    Button(onClick = { }) { Text(mode.name) }
                                } else {
                                    OutlinedButton(onClick = { onThemeModeChange(mode) }) { Text(mode.name) }
                                }
                            }
                        }
                    }
                }
                if (providers.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tile API keys", style = MaterialTheme.typography.titleSmall)
                            providers.forEach { provider ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    OutlinedTextField(
                                        value = keys[provider].orEmpty(),
                                        onValueChange = { keys = keys + (provider to it) },
                                        label = { Text(provider.label) },
                                        placeholder = { Text("API key required") },
                                        supportingText = { Text("sent as ?token=… to ${provider.id}") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Button(onClick = {
                                providers.forEach { provider ->
                                    val key = keys[provider].orEmpty().trim()
                                    Prefs.setMapTileApiKey(this@MainActivity, provider.id, key)
                                    onTileApiKey(provider, key)
                                }
                                uiScope.launch { snackbar.showSnackbar("Tile API keys saved") }
                            }) { Text("Save tile keys") }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Offline maps", style = MaterialTheme.typography.titleSmall)
                        var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
                        var totalBytes by remember { mutableLongStateOf(0L) }
                        var stats by remember { mutableStateOf<List<ProviderStat>>(emptyList()) }
                        // Sizing walks the tile cache and queries cache.db: keep it off the main thread.
                        fun refreshRegions() {
                            uiScope.launch {
                                val ctx = this@MainActivity
                                val (r, total, st) = withContext(Dispatchers.IO) {
                                    Triple(
                                        OfflineRegions.load(ctx).map {
                                            it.copy(sizeBytes = OfflineRegions.regionSizeBytes(ctx, it))
                                        },
                                        OfflineRegions.cacheSizeBytes(ctx),
                                        OfflineRegions.providerStats(ctx),
                                    )
                                }
                                regions = r
                                totalBytes = total
                                stats = st
                            }
                        }
                        LaunchedEffect(Unit) { refreshRegions() }
                        Text(
                            "saved areas: ${regions.size} · cache ${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (stats.isNotEmpty()) {
                            Text("cached by provider", style = MaterialTheme.typography.labelMedium)
                            stats.forEach { s ->
                                val label = TileProvider.byId(s.providerId)?.label ?: s.providerId
                                Text(
                                    "$label · ${s.tileCount} tiles · ${"%.1f".format(s.sizeBytes / (1024.0 * 1024.0))} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        Text(
                            "To save an area: on the map press ↓, drag a rectangle and download it. " +
                                "Without internet, choose OFFLINE on the map.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        regions.forEach { r ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${TileProvider.byId(r.sourceName)?.label ?: r.sourceName} · " +
                                            "zoom ${r.zoomMin}–${r.zoomMax} · ${r.tileCount} tiles · " +
                                            "${"%.1f".format(r.sizeBytes / (1024.0 * 1024.0))} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                VerticalDivider(
                                    modifier = Modifier.height(32.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                TextButton(onClick = {
                                    OfflineRegions.deleteTiles(this@MainActivity, r) { ok ->
                                        refreshRegions()
                                        uiScope.launch {
                                            snackbar.showSnackbar(
                                                if (ok) "offline area deleted" else "offline area delete failed",
                                            )
                                        }
                                    }
                                }) { Text("Delete", maxLines = 1, softWrap = false) }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    OfflineRegions.clear(this@MainActivity)
                                    refreshRegions()
                                    uiScope.launch { snackbar.showSnackbar("all offline maps cleared") }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Clear all", maxLines = 1, softWrap = false) }
                            Button(
                                onClick = { refreshRegions() },
                                modifier = Modifier.weight(1f),
                            ) { Text("Refresh", maxLines = 1, softWrap = false) }
                        }
                    }
                }
                SmsLinkCard(snackbar, uiScope)
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
                        Text("every ${intervalMs / 1000}s your position goes to members nearby; shorter = fresher, longer = less battery",
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
                                if (sec == null || sec * 1000 !in Prefs.MIN_INTERVAL_MS..Prefs.MAX_INTERVAL_MS) {
                                    intervalError = "enter ${Prefs.MIN_INTERVAL_MS / 1000}–${Prefs.MAX_INTERVAL_MS / 1000} seconds"
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
                        if (BuildConfig.DEBUG) {
                            Text(
                                "debug link log: " + (SessionService.linkLogFile ?: "starts with the session"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        if (showNodePicker) {
            NodePickerDialog(
                onDismiss = { showNodePicker = false },
                onPick = { addr ->
                    Prefs.setLoraNode(this@MainActivity, addr)
                    showNodePicker = false
                },
            )
        }
    }

    /**
     * LNP/3 SMS link: the only input is this phone's number. With it (and SMS permitted)
     * SMS is offered to members like any other transport; nothing else to switch on.
     */
    @Composable
    private fun SmsLinkCard(snackbar: SnackbarHostState, uiScope: kotlinx.coroutines.CoroutineScope) {
        var number by remember { mutableStateOf(Prefs.myPhoneNumber(this@MainActivity)) }
        var myName by remember { mutableStateOf(Prefs.myName(this@MainActivity)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your name", style = MaterialTheme.typography.titleSmall)
                Text("What members see on their map and member list.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = myName, onValueChange = { myName = it },
                        placeholder = { Text(Prefs.currentDisplayName(this@MainActivity) ?: "e.g. Ira") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        Prefs.setMyName(this@MainActivity, myName)
                        myName = Prefs.myName(this@MainActivity)
                        uiScope.launch { snackbar.showSnackbar("Members will see “${Prefs.currentDisplayName(this@MainActivity)}”") }
                    }) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("This phone's number", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Lets members reach you by SMS — also over satellite where the carrier " +
                        "supports it — when Bluetooth and Wi-Fi Direct cannot. Phones agree on " +
                        "it automatically; at most 10 SMS per hour, standard prices apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = number, onValueChange = { number = it },
                        placeholder = { Text("+380…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        Prefs.setMyPhoneNumber(this@MainActivity, number)
                        number = Prefs.myPhoneNumber(this@MainActivity)
                        if (number.isNotEmpty() && !smsPermitted()) smsPermissionLauncher.launch(smsPermissions)
                        uiScope.launch { snackbar.showSnackbar(if (number.isEmpty()) "Number removed" else "Saved") }
                    }) { Text("Save") }
                }
            }
        }
    }

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
                        label = { Text("Password (empty = generate one)") }, singleLine = true,
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

    private fun requestRadioPermissions() = permissionLauncher.launch(permissions.toTypedArray())

    private fun granted(): Boolean = permissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
