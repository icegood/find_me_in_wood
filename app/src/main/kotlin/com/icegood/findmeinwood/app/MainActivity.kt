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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.stateIn
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { offerHomeScreenIcon() }
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

    /** FR-2.6: an invite QR code opened with the phone's camera app, waiting to be joined. */
    private var pendingInvite by mutableStateOf<JoinInvite?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeInvite(intent)
    }

    private fun takeInvite(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            JoinInvite.parse(intent.dataString)?.let { pendingInvite = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeInvite(intent)
        // Only when something is missing: an (even instantly answered) permission request
        // puts a transparent screen on top that swallowed the first tap after every launch.
        if (!granted()) permissionLauncher.launch(permissions.toTypedArray()) else offerHomeScreenIcon()
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
                // Signed in = a saved uid. While its profile loads from the encrypted DB the
                // screen stays empty: drawing Sign In meanwhile flashed it on every launch.
                var loading by remember { mutableStateOf(Prefs.currentUid(this@MainActivity) != null) }
                LaunchedEffect(Unit) {
                    authManager.setCurrentUserId(Prefs.currentUid(this@MainActivity))
                    currentUser = authManager.getCurrentUser()
                    Prefs.setCurrentDisplayName(this@MainActivity, currentUser?.displayName)
                    loading = false
                }
                if (loading) {
                    Surface(Modifier.fillMaxSize()) {}
                } else if (currentUser == null) {
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
        LaunchedEffect(pendingInvite) { if (pendingInvite != null) tab = "networks" }
        var mapLayer by remember {
            mutableStateOf(
                com.icegood.findmeinwood.feature.map.MapLayer(
                    Prefs.mapProvider(this@MainActivity)?.let { TileProvider.byId(it) }
                        ?: com.icegood.findmeinwood.feature.map.MapLayer.DEFAULT.provider,
                    Prefs.mapOffline(this@MainActivity),
                ),
            )
        }
        // Each provider's own options (key, token…), stored per provider and parameter.
        var tileOptions by remember {
            mutableStateOf(
                TileProvider.entries.associateWith { p ->
                    p.options.associate { it.param to Prefs.tileOption(this@MainActivity, p.id, it.param) }
                        .filterValues { it.isNotEmpty() }
                },
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
                        // Six items on a narrow screen leave ~60dp each: long words ("Networks",
                        // "Налаштування") shrink to fit on one line instead of wrapping or clipping.
                        label = {
                            val style = MaterialTheme.typography.labelSmall
                            androidx.compose.foundation.text.BasicText(
                                label,
                                style = style.copy(color = LocalContentColor.current),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                                    minFontSize = 7.sp, maxFontSize = style.fontSize,
                                ),
                            )
                        },
                    )
                }
                navItem(stringResource(R.string.tab_map), tab == "map") { tab = "map" }
                navItem(stringResource(R.string.tab_networks), tab == "networks") { tab = "networks" }
                navItem(stringResource(R.string.tab_chat), tab == "chat") { tab = "chat" }
                // "!" while a member waits for this user to allow a link: nothing connects before.
                val decide = com.icegood.findmeinwood.feature.map.needsLinkDecision(namedPeers.collectAsState().value.values)
                navItem((if (decide) "! " else "") + stringResource(R.string.tab_peers), tab == "peers") { tab = "peers" }
                navItem(stringResource(R.string.tab_discover), tab == "discover") { tab = "discover" }
                navItem(stringResource(R.string.tab_settings), tab == "settings") { tab = "settings" }
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
                        layer = mapLayer,
                        tileOptions = tileOptions,
                        onLayerChange = {
                            Prefs.setMapLayer(this@MainActivity, it.provider.id, it.offline)
                            mapLayer = it
                        },
                        onTileOptions = { p, values ->
                            p.options.forEach { Prefs.setTileOption(this@MainActivity, p.id, it.param, values[it.param]) }
                            tileOptions = tileOptions + (p to values)
                        },
                        findTarget = findTarget,
                        onFindTargetChange = { findTarget = it },
                    )
                    tab == "peers" -> com.icegood.findmeinwood.feature.map.PeerList(
                        namedPeers,
                        onRename = { id, name -> MemberNames.rename(this@MainActivity, id, name) },
                        onSelectLink = { id, t -> SessionService.activeManager?.selectLink(id, t) },
                        onAllowLink = { id, t, on -> SessionService.activeManager?.allowLink(id, t, on) },
                        linkStatuses = SessionBus.linkStatuses.collectAsState().value,
                        onLinkFix = { fix ->
                            when (fix) {
                                com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_BLUETOOTH -> turnOnBluetooth()
                                com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_WIFI -> turnOnWifi()
                                com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_LOCATION ->
                                    startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                com.icegood.findmeinwood.core.model.LinkFix.SET_PHONE_NUMBER -> tab = "settings"
                                com.icegood.findmeinwood.core.model.LinkFix.ALLOW_SMS -> smsPermissionLauncher.launch(smsPermissions)
                            }
                        },
                        emptyText = if (profile == null) {
                            stringResource(R.string.peers_no_network)
                        } else {
                            stringResource(R.string.peers_none_heard, profile?.name.orEmpty())
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
                    )
                    tab == "members" -> MembersScreen(onBack = { tab = "networks" })
                    else -> NetworksScreen(onDiscover = { tab = "discover" }, onMembers = { tab = "members" })
                }
            }
        }
    }

    @Composable
    private fun ChatListPlaceholder() {
        val running by SessionBus.sessionRunning.collectAsState()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tab_chat), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (running && !SessionBus.listening.collectAsState().value) {
                    stringResource(R.string.chat_opening)
                } else {
                    stringResource(R.string.chat_placeholder)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NetworksScreen(onDiscover: () -> Unit, onMembers: () -> Unit) {
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
        val findableUntil by SessionBus.findableUntil.collectAsState()
        val book by SessionBus.book.collectAsState()
        /** Bumped to re-read the per-network "sharing paused" flag after a toggle. */
        var pausedTick by remember { mutableStateOf(0) }
        // Ticks so "findable: N min left" counts down and ends by itself.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(findableUntil) {
            while (true) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { showCreate = true; createError = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.create_network)) }
                OutlinedButton(
                    onClick = { showJoin = true; createError = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.join_by_password)) }
                OutlinedButton(
                    onClick = onDiscover,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.find_networks_nearby)) }
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
                                    stringResource(if (isRunning) R.string.net_active else R.string.net_not_active),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isRunning) {
                                    val paused = pausedTick >= 0 && Prefs.sharingPaused(this@MainActivity, p.networkIdHex)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(if (paused) R.string.position_paused else R.string.position_shared),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = {
                                            SessionService.setSharingPaused(this@MainActivity, p.networkIdHex, !paused)
                                            pausedTick++
                                        }) { Text(stringResource(if (paused) R.string.share_again else R.string.pause)) }
                                    }
                                }
                                // The master learns at once when radios stop new people from finding it.
                                val blocked = isRunning && book?.iAmMaster == true &&
                                    nowMs >= 0 && DiscoveryBlockers(this@MainActivity, finding = false)
                                if (isRunning && !blocked) Text(
                                    if (book?.iAmMaster == true) UiText.findable(this@MainActivity, findableUntil, nowMs)
                                    else stringResource(R.string.only_master_lets_in),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (code != null) {
                                    Text(
                                        stringResource(R.string.joined_without_password, code),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = {
                                        Prefs.setUnverifiedCode(this@MainActivity, p.networkIdHex, null)
                                        verifyTick++
                                    }) { Text(stringResource(R.string.codes_matched)) }
                                }
                                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isRunning) {
                                        // Discovery only: the network keeps running for its members either way.
                                        if (book?.iAmMaster == true) {
                                            if (findableUntil > nowMs) {
                                                OutlinedButton(onClick = { SessionBus.setFindable(false) }) { Text(stringResource(R.string.stop_discovery)) }
                                            } else {
                                                Button(onClick = { SessionBus.setFindable(true) }) { Text(stringResource(R.string.start_discovery)) }
                                            }
                                            OutlinedButton(onClick = { sharing = p; SessionBus.setFindable(true) }) { Text(stringResource(R.string.invite)) }
                                        }
                                        OutlinedButton(onClick = onMembers) { Text(stringResource(R.string.members)) }
                                    } else {
                                        Button(onClick = {
                                            if (!granted()) { requestRadioPermissions(); return@Button }
                                            SessionService.start(this@MainActivity, p)
                                        }) { Text(stringResource(R.string.use_this_network)) }
                                    }
                                    TextButton(onClick = { deleting = p }) { Text(stringResource(R.string.delete)) }
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
                                    is SendResult.HandedToUser -> getString(R.string.beacon_shared)
                                    is SendResult.Sent -> getString(R.string.beacon_shared)
                                    else -> getString(R.string.beacon_none)
                                }
                            )
                        }
                    },
                    enabled = lastWire != null && runningId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.send_position_messenger)) }
                Text(
                    when {
                        runningId != null -> stringResource(R.string.radios)
                        listening -> stringResource(R.string.looking_see_discover)
                        profiles.isEmpty() -> stringResource(R.string.no_networks_yet)
                        else -> stringResource(R.string.no_network_active)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                UiText.radios(this@MainActivity, radios).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (runningId != null || listening) RadioSwitches(radios)
            }
        }

        if (showCreate) {
            CreateDialog(
                error = createError,
                onDismiss = { showCreate = false; createError = null },
                onCreate = { name, passphrase ->
                    when (val r = ProfileStore.create(name, passphrase)) {
                        is CreateResult.Ok -> {
                            ProfileStore.add(this, r.profile)
                            Prefs.setSecretFor(this, r.profile.networkIdHex, r.password)
                            profiles = ProfileStore.load(this)
                            showCreate = false
                            createError = null
                            sharing = r.profile
                            // New and nothing else running: start it, so it can be found
                            // as the invite says (a running network is never switched away).
                            if (runningId == null && granted()) SessionService.start(this, r.profile)
                        }
                        is CreateResult.Error -> createError = r.text(this)
                    }
                },
            )
        }
        if (showJoin || pendingInvite != null) {
            PasswordDialog(
                title = stringResource(R.string.join_by_password),
                askName = true,
                initial = pendingInvite,
                onDismiss = { showJoin = false; pendingInvite = null },
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
                            pendingInvite = null
                            createError = null
                            null
                        }
                        is JoinResult.Error -> r.text(this)
                    }
                },
            )
        }
        deleting?.let { p ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.delete_named, p.name)) },
                text = {
                    Text(stringResource(R.string.delete_explain))
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (runningId == p.networkIdHex) SessionService.stop(this@MainActivity)
                        runBlocking { ProfileStore.remove(this@MainActivity, p.networkIdHex) }
                        Prefs.setSecretFor(this@MainActivity, p.networkIdHex, null)
                        profiles = ProfileStore.load(this@MainActivity)
                        deleting = null
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
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
                stringResource(R.string.location_off_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.turn_on_location)) }
        }
        if (btOff) {
            OutlinedButton(onClick = { turnOnBluetooth() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.turn_on_bluetooth)) }
        }
        if (wifiOff) {
            Text(
                stringResource(R.string.wifi_off_explain),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { turnOnWifi() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.turn_on_wifi)) }
        }
    }

    private fun turnOnBluetooth() {
        runCatching {
            startActivity(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun turnOnWifi() {
        startActivity(
            android.content.Intent(
                if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI
                else android.provider.Settings.ACTION_WIFI_SETTINGS,
            ),
        )
    }

    /** Shows a network's password, to read out or send to someone who should join. */
    @Composable
    private fun InviteDialog(p: StoredProfile, password: String?, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.invite_to, p.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (password == null) {
                        Text(stringResource(R.string.no_password_here))
                    } else {
                        val qr = remember(p.name, password) {
                            qrBitmap(JoinInvite(p.name, password).toUri(), 600).asImageBitmap()
                        }
                        androidx.compose.foundation.Image(
                            qr, contentDescription = stringResource(R.string.invite_qr),
                            modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                        )
                        Text(stringResource(R.string.password), style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(password, style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            stringResource(R.string.invite_explain, p.name),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                if (password != null) Row {
                    TextButton(onClick = {
                        val clip = android.content.ClipData.newPlainText(getString(R.string.password), password)
                        // Android 13+: keep the password out of the clipboard preview.
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                        // Android 13+ confirms a copy itself; older versions show nothing.
                        if (Build.VERSION.SDK_INT < 33) {
                            android.widget.Toast.makeText(this@MainActivity, getString(R.string.password_copied), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                getString(R.string.share_password_text, p.name, password),
                            )
                        startActivity(android.content.Intent.createChooser(send, getString(R.string.send_password_with)))
                    }) { Text(stringResource(R.string.send_via_app)) }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        )
    }

    @Composable
    private fun themeName(mode: ThemeMode) = stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        },
    )

    /** Dedicated settings screen: theme, offline maps and device/beacon options. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit,
    ) {
        var showNodePicker by remember { mutableStateOf(false) }
        var intervalMs by remember { mutableLongStateOf(Prefs.intervalMs(this@MainActivity)) }
        var intervalInput by remember { mutableStateOf((Prefs.intervalMs(this@MainActivity) / 1000).toString()) }
        var intervalError by remember { mutableStateOf<String?>(null) }
        var p2pOwner by remember { mutableStateOf(Prefs.p2pGroupOwner(this@MainActivity)) }
        val snackbar = remember { SnackbarHostState() }
        val uiScope = rememberCoroutineScope()
        val diag by SessionBus.diagnostics.collectAsState()

        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.theme_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                if (themeMode == mode) {
                                    Button(onClick = { }) { Text(themeName(mode)) }
                                } else {
                                    OutlinedButton(onClick = { onThemeModeChange(mode) }) { Text(themeName(mode)) }
                                }
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.offline_maps), style = MaterialTheme.typography.titleSmall)
                        var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
                        var totalBytes by remember { mutableLongStateOf(0L) }
                        fun refreshRegions() {
                            OfflineRegions.load(this@MainActivity) { r ->
                                regions = r
                                totalBytes = OfflineRegions.cacheSizeBytes(this@MainActivity)
                            }
                        }
                        LaunchedEffect(Unit) { refreshRegions() }
                        Text(
                            stringResource(R.string.saved_areas, regions.size, "%.1f".format(totalBytes / (1024.0 * 1024.0))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.offline_maps_hint),
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
                                        "${TileProvider.byId(r.providerId)?.label ?: r.providerId} · " +
                                            stringResource(R.string.size_mb, "%.1f".format(r.sizeBytes / (1024.0 * 1024.0))) +
                                            if (r.complete) "" else " · " + stringResource(R.string.incomplete),
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
                                    OfflineRegions.delete(this@MainActivity, r) {
                                        refreshRegions()
                                        uiScope.launch { snackbar.showSnackbar(getString(R.string.offline_area_deleted)) }
                                    }
                                }) { Text(stringResource(R.string.delete), maxLines = 1, softWrap = false) }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    OfflineRegions.clear(this@MainActivity) {
                                        refreshRegions()
                                        uiScope.launch { snackbar.showSnackbar(getString(R.string.offline_cleared)) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.clear_all), maxLines = 1, softWrap = false) }
                            Button(
                                onClick = { refreshRegions() },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.refresh), maxLines = 1, softWrap = false) }
                        }
                    }
                }
                SmsLinkCard(snackbar, uiScope)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.lora_node), style = MaterialTheme.typography.titleSmall)
                        Text(
                            Prefs.loraNode(this@MainActivity) ?: stringResource(R.string.lora_auto),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { showNodePicker = true }) { Text(stringResource(R.string.choose_node)) }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.transport_wifi_direct), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (p2pOwner) R.string.p2p_host_hint else R.string.p2p_auto_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p2pOwner) Button(onClick = { }) { Text(stringResource(R.string.p2p_host)) }
                            else OutlinedButton(onClick = {
                                p2pOwner = true
                                SessionService.setP2pGroupOwner(this@MainActivity, true)
                            }) { Text(stringResource(R.string.p2p_host)) }
                            if (!p2pOwner) Button(onClick = { }) { Text(stringResource(R.string.p2p_auto)) }
                            else OutlinedButton(onClick = {
                                p2pOwner = false
                                SessionService.setP2pGroupOwner(this@MainActivity, false)
                            }) { Text(stringResource(R.string.p2p_auto)) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.beacon_interval), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.beacon_interval_hint, (intervalMs / 1000).toInt()),
                            style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = intervalInput,
                                onValueChange = { intervalInput = it; intervalError = null },
                                label = { Text(stringResource(R.string.seconds)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp),
                            )
                            Button(onClick = {
                                val sec = intervalInput.trim().toLongOrNull()
                                if (sec == null || sec * 1000 !in Prefs.MIN_INTERVAL_MS..Prefs.MAX_INTERVAL_MS) {
                                    intervalError = getString(R.string.interval_range, (Prefs.MIN_INTERVAL_MS / 1000).toInt(), (Prefs.MAX_INTERVAL_MS / 1000).toInt())
                                } else {
                                    Prefs.setIntervalMs(this@MainActivity, sec * 1000L)
                                    intervalMs = sec * 1000L
                                    intervalError = null
                                }
                            }) { Text(stringResource(R.string.set)) }
                        }
                        intervalError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.diag_counts, diag.sent, diag.received, diag.relayed),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.diag_failures, diag.authFailed, diag.dropped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (BuildConfig.DEBUG) {
                            Text(
                                "debug link log: " + (SessionService.linkLogFile ?: "starts with the session"), // debug builds only
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
                Text(stringResource(R.string.your_name), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.your_name_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = myName, onValueChange = { myName = it },
                        placeholder = { Text(Prefs.currentDisplayName(this@MainActivity) ?: stringResource(R.string.name_example)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        Prefs.setMyName(this@MainActivity, myName)
                        myName = Prefs.myName(this@MainActivity)
                        uiScope.launch { snackbar.showSnackbar(getString(R.string.members_will_see, Prefs.currentDisplayName(this@MainActivity).orEmpty())) }
                    }) { Text(stringResource(R.string.save)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.phone_number), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.phone_number_hint),
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
                        uiScope.launch { snackbar.showSnackbar(getString(if (number.isEmpty()) R.string.number_removed else R.string.saved)) }
                    }) { Text(stringResource(R.string.save)) }
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
            title = { Text(stringResource(R.string.choose_lora_node)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (nodes.value.isEmpty()) Text(stringResource(R.string.scanning_nodes))
                    nodes.value.forEach { n ->
                        TextButton(onClick = { onPick(n.address) }) {
                            Text((n.name?.plus(" ") ?: "") + n.address, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        )
    }

    @Composable
    private fun CreateDialog(error: String?, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.create_network)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.network_name)) }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.password_or_generate)) }, singleLine = true,
                        // No suggestions or autocorrect: keyboards otherwise mangle and remember passwords.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    )
                    // Shown here: behind the dialog nobody would see why Create did nothing.
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.length in 1..64,
                    onClick = { onCreate(name.trim(), pass) },
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /**
     * FR-12.3: an APK installed by hand lands only in the app drawer (launchers add Play Store
     * installs to the home screen by themselves), so the first launch offers Android's own
     * "Add to Home screen" once. Launchers that put every app on the home screen anyway
     * (Huawei, Honor) are not asked, or the icon would appear twice.
     */
    private fun offerHomeScreenIcon() {
        if (Prefs.homeIconOffered(this)) return
        Prefs.setHomeIconOffered(this)
        val launcher = packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName.orEmpty()
        if (!homeScreenNeedsOffer(launcher)) return
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) return
        val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "home")
            .setShortLabel(getString(R.string.app_name))
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    .setClass(this, MainActivity::class.java),
            )
            .build()
        runCatching { androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, shortcut, null) }
    }

    private fun requestRadioPermissions() = permissionLauncher.launch(permissions.toTypedArray())

    private fun granted(): Boolean = permissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
