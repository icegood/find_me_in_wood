package com.icegood.findmeinwood.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.crypto.SmsCodec
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.DebugLog
import com.icegood.findmeinwood.core.crypto.BleAdvert
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.session.GnssSource
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.session.DiscoveryManager
import com.icegood.findmeinwood.core.session.LinkNegotiator
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.session.JoinOutcome
import com.icegood.findmeinwood.core.session.SessionManager
import com.icegood.findmeinwood.core.session.KeyRing
import com.icegood.findmeinwood.core.model.RekeyCodec
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.bluetooth.BluetoothTransport
import com.icegood.findmeinwood.transport.lora.BleNodeLink
import com.icegood.findmeinwood.transport.lora.LoraTransport
import com.icegood.findmeinwood.transport.share.ShareInbox
import com.icegood.findmeinwood.transport.share.ShareTransport
import com.icegood.findmeinwood.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Hosts one active network session (P7/T2.5): foreground notification, transports,
 * GNSS source, share inbox. One session at a time (v1).
 */
class SessionService : Service() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SessionManager? = null
    /** Debug builds: persistent link log of the running session (null in release). */
    private var linkLog: LinkLog? = null

    /** Shutdown of the previous session's transports, still running in the background. */
    private var stopping: Job? = null
    /** Latest own GNSS fix of the running session. */
    @Volatile private var lastFix: GnssFix? = null
    private var activeProfile: NetworkProfile? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> if (session == null) startListening()
            else -> startSession(intent)
        }
        return START_STICKY
    }

    /**
     * No network yet: run the radios on a throwaway network that is never saved, sends no
     * beacons and advertises nothing, only to hear other networks' HELLOs and to carry
     * the join handshake. A successful join switches to the joined network.
     */
    private fun startListening() {
        val throwaway = (ProfileStore.create("listening", null) as CreateResult.Ok).profile
        // Same member id as after joining: the master lists exactly the phone it let in.
        val profile = ProfileStore.toProfile(throwaway, deviceId(), master = null)
        runSession(profile, listen = true, masterId = null)
    }

    private fun startSession(intent: Intent?) {
        // START_STICKY: after a process death Android restarts us with a null intent.
        // Without profile extras there is nothing to run, so stop instead of crashing.
        if (intent?.getStringExtra(EXTRA_NETWORK_ID) == null) {
            stopSelf()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "network"
        val masterId = when (val m = intent.getStringExtra(EXTRA_OWNER_ID).orEmpty()) {
            MASTER_ME -> deviceId()
            "" -> null
            else -> MemberId(m.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        val profile = NetworkProfile(
            name = name,
            networkId = com.icegood.findmeinwood.core.model.NetworkId(intent.hex(EXTRA_NETWORK_ID)),
            trafficKey = intent.hex(EXTRA_TRAFFIC_KEY),
            // A member is its device identity (the key that signs its beacons) in every
            // network: member ids are key hashes, so signatures are checkable by id.
            myMemberId = deviceId(),
            ownerMemberId = masterId ?: MemberId(ByteArray(32)),
            policy = intent.getStringExtra(EXTRA_POLICY)?.let {
                runCatching { JoinPolicy.valueOf(it) }.getOrNull()
            } ?: JoinPolicy.PRIVATE,
        )
        runSession(profile, listen = false, masterId = masterId)
    }

    private fun deviceId() = MemberId(Identity.memberIdOf(IdentityHolder.keyPair(this)))

    private fun runSession(profile: NetworkProfile, listen: Boolean, masterId: MemberId?) {
        // "Start" while a session is live restarts it (and "Restart" in the UI is
        // no longer a silent no-op). stopSession hands the old scope to its background
        // shutdown and installs a fresh one, which also covers Stop -> Start on the
        // same service instance, where session is already null.
        if (session != null) stopSession()
        startForeground(NOTIFICATION_ID, buildNotification(listen))
        listening = listen

        activeProfile = profile
        activeNetworkId = if (listen) null else profile.networkId
        val deltaMs = Prefs.intervalMs(this)
        val gnss: GnssSource = AndroidGnssSource(this, deltaMs)
        // Latest own fix, for the debug link log's distance-to-peer figures.
        lastFix = null
        val fixes = gnss.fixes().onEach { lastFix = it }
        val bluetooth = BluetoothTransport(this, linkTimeoutMs = (deltaMs * 3).coerceAtLeast(120_000L))
        val wifiDirect = WifiDirectTransport(this).apply {
            setPreferGroupOwner(Prefs.p2pGroupOwner(this@SessionService))
        }
        activeWifiDirect = wifiDirect
        val transports = listOf<Transport>(
            // link timeout tracks the beacon cadence: 3 missed beacons = dead link
            bluetooth,
            wifiDirect,
            LoraTransport(this, BleNodeLink(this, Prefs.loraNode(this))),
            ShareTransport(this),
        )
        val identityPair = IdentityHolder.keyPair(this)
        val netHex = profile.networkId.bytes.joinToString("") { "%02x".format(it) }
        val boxPair = IdentityHolder.boxPair(this)
        val stored = ProfileStore.load(this).firstOrNull { it.networkIdHex == netHex }
        val book = if (listen) null else MemberBookStore.load(this, profile.networkId, identityPair, masterId, boxPair.public.encoded).also { b ->
            b.onMasterLearned = { id -> ProfileStore.setMaster(this, netHex, id.bytes.joinToString("") { "%02x".format(it) }) }
        }
        activeBook = book
        val discovery = DiscoveryManager(profile, identityPair, { System.currentTimeMillis() }, canAdmit = { book?.isMaster == true })
        discovery.ownerSecret = Prefs.secretFor(this, profile.networkId.bytes.joinToString("") { "%02x".format(it) })
        val clock = { System.currentTimeMillis() }
        val manager = SessionManager(
            profile, transports, fixes,
            clock, scope = scope, keepAliveMs = deltaMs,
            // LNP/3: offers, the agreed transport set and the selected link per peer.
            links = if (listen) null else LinkNegotiator(
                profile.myMemberId, clock, beaconIntervalMs = deltaMs,
                smsIntervalMs = SMS_INTERVAL_MS,
            ),
            signer = identityPair,
            beaconsEnabled = !listen,
            book = book,
            requireSignatures = true,
            // The network key per epoch (re-salted when the master removes a member).
            keys = KeyRing(profile.trafficKey, stored?.let { ProfileStore.salts(it) }.orEmpty()),
            box = boxPair,
            latestRekey = stored?.let { ProfileStore.rekey(it) }?.let { RekeyCodec.decode(it) },
        ).also { it.sharingPaused = !listen && Prefs.sharingPaused(this, netHex) }
        if (!listen) manager.onEpoch = {
            ProfileStore.setEpochs(
                this, netHex, manager.keys.salts(),
                manager.latestRekey?.let { RekeyCodec.encode(it) },
            )
            linkLog?.write("network_key") {
                put("epoch", manager.keys.epoch)
                put("rev", manager.latestRekey?.rev)
                put("sealedTo", manager.latestRekey?.wraps?.size)
            }
        }
        book?.let { b ->
            manager.onBookChanged = {
                MemberBookStore.save(this, profile.networkId, b)
                SessionBus.publishBook(MemberBookStore.view(b))
            }
            SessionBus.publishBook(MemberBookStore.view(b))
        }
        linkLog = (if (listen) null else LinkLog.open(this))?.also { log ->
            linkLogFile = "Download/${LinkLog.FOLDER}/${log.fileName}"
            val sampler = LinkLogSampler(this, log, manager, bluetooth, wifiDirect, { lastFix }, deltaMs)
            manager.onLinkSwitch = sampler::onLinkSwitch
            sampler.start(scope, deltaMs)
        }
        DiscoveryBridge.onRequestJoin = { netId ->
            val wire = discovery.buildJoinRequest(netId, Prefs.currentDisplayName(this@SessionService))
            // A listening phone holds no links: open one to a phone advertising the network,
            // then send the request once, as soon as a radio really carries it.
            discovery.radioPeersOf(netId).takeLast(MAX_JOIN_LINKS).forEach { (t, peer) -> manager.connect(t, peer) }
            scope.launch {
                val deadline = System.currentTimeMillis() + JOIN_LINK_WAIT_MS
                while (!manager.sendMgmt(wire) && System.currentTimeMillis() < deadline) delay(1_000)
            }
        }
        DiscoveryBridge.onApprove = { joiner ->
            val wire = discovery.approve(joiner)
            if (wire == null) {
                SessionBus.publishOwnerNote(getString(R.string.cannot_approve))
            } else {
                scope.launch { manager.sendMgmt(wire) }
                SessionBus.setFindable(true) // others may be waiting to join too
                val sas = discovery.ownerSas.value ?: "------"
                SessionBus.publishOwnerNote(getString(R.string.approved_compare, sas))
            }
        }
        DiscoveryBridge.onReject = { joiner ->
            val wire = discovery.reject(joiner)
            scope.launch { manager.sendMgmt(wire) }
        }
        activeDiscovery = discovery
        manager.onMgmtFrame = { wire, source ->
            val before = discovery.discovered.value.size
            val r = runCatching { discovery.onMgmtFrame(MgmtCodec.decode(wire)) }
            DebugLog.d {
                "rx mgmt ${wire.size}B type=${wire.getOrNull(3)} via $source, networks listed $before->${discovery.discovered.value.size}" +
                    (r.exceptionOrNull()?.let { " error=$it" } ?: "")
            }
            SessionBus.publishPending(discovery.pendingJoins.value)
            SessionBus.publishDiscovered(discovery.discovered.value)
            discovery.outcome.value?.let(::onJoinOutcome)
            discovery.clearOutcome()
        }
        manager.onNetworkAdvert = { info, peer, transport ->
            BleAdvert.decodeInfo(info)?.let { discovery.onAdvert(it, peer, transport) }
            SessionBus.publishDiscovered(discovery.discovered.value)
        }
        // Findable by new people: on for a while after Start (and Invite, approvals), then
        // off by itself; members reach each other either way.
        // Only the master lets people in, so only the master can be found by them.
        val canBeFound = !listen && book?.isMaster == true
        SessionBus.setFindable(canBeFound)
        val advert = BleAdvert.encodeInfo(BleAdvert.Info(profile.networkId, profile.policy, profile.name))
        scope.launch {
            while (true) {
                manager.setFindable(if (canBeFound && SessionBus.isFindable()) advert else null)
                delay(5_000)
                discovery.pruneStale()
                SessionBus.publishDiscovered(discovery.discovered.value)
                SessionBus.publishPending(discovery.pendingJoins.value)
                discovery.outcome.value?.let(::onJoinOutcome)
                discovery.clearOutcome()
            }
        }
        scope.launch {
            discovery.ownerSas.collect { SessionBus.publishOwnerSas(it) }
        }
        scope.launch {
            manager.radioStates.collect { SessionBus.publishRadioStates(it) }
        }
        manager.onFrameSent = { wire ->
            // Not copied to the clipboard any more: that silently replaced whatever the user
            // had copied (a network password, say) every beacon. "Send my position via
            // messenger" in Networks shares the latest beacon on request instead.
            SessionBus.publish(profile, manager.peerFlow.value, wire)
        }
        ShareInbox.sink = { wire ->
            scope.launch { manager.acceptExternalWire(wire, TransportId.INTERNET) }
        }
        manager.onFrameReceived = { plain, sender ->
            scope.launch { SessionBus.publishIncoming(plain, sender) }
        }
        // The throwaway listening network is not a network the screens should show.
        if (!listen) scope.launch {
            manager.peerFlow.collect { peers -> SessionBus.publish(profile, peers, SessionBus.lastWireFrame.value) }
        }
        scope.launch {
            manager.diagnostics.collect { SessionBus.publishDiagnostics(it) }
        }
        manager.links?.let { links -> runLinks(manager, links) }
        if (BuildConfig.DEBUG) registerMuteHook(manager)
        // On Restart the old transports may still be releasing their radios; starting
        // the new ones before that finishes would fight over the same GATT server/link.
        val previous = stopping
        if (previous == null || previous.isCompleted) {
            manager.start()
        } else {
            scope.launch(Dispatchers.Main) { previous.join(); manager.start() }
        }
        session = manager
        activeManager = manager
        activeDiscovery = discovery
        isRunning = true
        SessionBus.setSessionRunning(true)
        SessionBus.setListening(listen)
    }

    /**
     * Saved here, not in a screen, so a join approved while the app is in the background
     * is not lost. When we were only listening, the joined network starts right away.
     */
    private fun onJoinOutcome(outcome: JoinOutcome) {
        if (outcome is JoinOutcome.Joined) {
            val id = ProfileStore.networkIdOf(outcome.secret).bytes.joinToString("") { "%02x".format(it) }
            val existing = ProfileStore.load(this).firstOrNull { it.networkIdHex == id }
            val masterHex = outcome.masterId?.bytes?.joinToString("") { b -> "%02x".format(b) }.orEmpty()
            // The phone that let us in is the master: started with it, not learned later.
            val stored = existing ?: (ProfileStore.join(outcome.name, outcome.secret) as? JoinResult.Ok)?.profile
                ?.copy(masterIdHex = masterHex)?.also {
                ProfileStore.add(this, it)
                Prefs.setSecretFor(this, it.networkIdHex, outcome.secret)
                // FR-8.4: unverified until the human compares the SAS
                Prefs.setUnverifiedCode(this, it.networkIdHex, outcome.sas)
            }
            if (stored != null && listening) start(this, stored)
        }
        SessionBus.publishJoinOutcome(outcome)
    }

    // ------------------------------------------------------------------ LNP/3

    /** Last SMS position report per peer (FR-3.9 rate). */
    private val smsSentAt = HashMap<MemberId, Long>()
    /** SMS link budget: SMS may be slow, limited (satellite) and paid. */
    private val smsLinkBudget = SmsBudget(maxPerHour = 10)

    /**
     * Keeps this phone's offers current (radio on/off, LoRa node, SMS allowed) and sends a
     * position SMS to every peer whose active rung is SMS, once per SMS interval.
     */
    private fun runLinks(manager: SessionManager, links: LinkNegotiator) {
        smsSentAt.clear()
        scope.launch {
            val power = HashMap<String, Boolean>()
            while (true) {
                // The phone's radio switches, flipped in the app or outside it: logged on
                // change, so a link that ends because a radio was switched off says so.
                mapOf("BLUETOOTH" to bluetoothOn(), "WIFI" to wifiOn()).forEach { (radio, on) ->
                    if (power.put(radio, on) != on) linkLog?.write("radio_power") {
                        put("radio", radio)
                        put("on", on)
                    }
                }
                val statuses = linkStatuses(manager)
                SessionBus.publishLinkStatuses(statuses)
                links.setOffers(LinkAvailability.offers(statuses))
                links.setName(Prefs.currentDisplayName(this@SessionService))
                // The member card: name and links for members who never heard us directly.
                manager.book?.let { b ->
                    if (b.updateMyCard(Prefs.currentDisplayName(this@SessionService), links.offers(), System.currentTimeMillis())) {
                        manager.onBookChanged?.invoke()
                        manager.syncBook(force = true)
                    }
                }
                val now = System.currentTimeMillis()
                val interval = SMS_INTERVAL_MS
                for ((peer, addr) in links.peersOn(TransportId.SMS)) {
                    if (addr == null || now - (smsSentAt[peer] ?: 0L) < interval) continue
                    if (!smsLinkBudget.tryTake(now)) break
                    smsSentAt[peer] = now
                    val sent = SmsSender.send(this@SessionService, listOf(addr), positionSms(manager))
                    linkLog?.write("sms_report") {
                        put("peer", peer.bytes.take(4).joinToString("") { "%02x".format(it) })
                        put("handedToModem", sent)
                        put("satellite", SmsSender.onSatellite(this@SessionService))
                    }
                }
                delay(3_000) // a fixed link (number set, radio on) shows up within seconds
            }
        }
    }

    /** Every supported link on this phone, usable or with the reason it is not. */
    private fun linkStatuses(manager: SessionManager): List<com.icegood.findmeinwood.core.model.LinkStatus> {
        return LinkAvailability.statuses(
            this@SessionService,
            LinkAvailability.Inputs(
                bluetoothOn = bluetoothOn(),
                wifiOn = wifiOn(),
                locationOn = locationOn(),
                lora = manager.radioStates.value[TransportId.LORA],
                phoneNumber = Prefs.myPhoneNumber(this@SessionService),
                smsPermitted = smsPermitted(),
                sdk = android.os.Build.VERSION.SDK_INT,
                onSatellite = SmsSender.onSatellite(this@SessionService),
            ),
        )
    }

    private fun bluetoothOn() = runCatching {
        getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    private fun wifiOn() = runCatching {
        getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    private fun locationOn() = runCatching {
        getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != false
    }.getOrDefault(true)

    private fun smsPermitted() = listOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.RECEIVE_SMS).all {
        checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** The 47-byte encrypted SMS position frame (FR-3.9). */
    private fun positionSms(manager: SessionManager): String {
        val profile = activeProfile!!
        val now = System.currentTimeMillis()
        val fix = lastFix ?: lastKnownFix()
        return SmsCodec.encode(
            manager.keys.current(), profile.networkId, profile.myMemberId, manager.nextSeq(),
            timeS = (fix?.epochMs ?: now) / 1000,
            latE7 = fix?.let { (it.lat * 1e7).toInt() } ?: 0,
            lonE7 = fix?.let { (it.lon * 1e7).toInt() } ?: 0,
            accuracyM = fix?.accuracyM?.toInt() ?: 0xFFFF,
            batteryPct = batteryPct(),
        )
    }

    // Without the permission getLastKnownLocation throws, and runCatching makes that "no fix".
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownFix(): GnssFix? = runCatching {
        val lm = getSystemService(android.location.LocationManager::class.java)
        com.icegood.findmeinwood.feature.map.trustedLastKnown(lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) })
            ?.let { GnssFix(it.latitude, it.longitude, it.accuracy, it.altitude, it.time) }
    }.getOrNull()

    private fun batteryPct(): Int? = runCatching {
        getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()

    private var muteHook: android.content.BroadcastReceiver? = null

    /**
     * Debug builds only: simulate being out of a radio's range from adb while the radio
     * stays on (and offered), e.g.
     *   adb shell am broadcast -a com.icegood.findmeinwood.debug.MUTE --es transports BLUETOOTH
     *   adb shell am broadcast -a com.icegood.findmeinwood.debug.MUTE --es transports ""
     */
    private fun registerMuteHook(manager: SessionManager) {
        val hook = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val set = i.getStringExtra("transports").orEmpty().split(',')
                    .mapNotNull { n -> runCatching { TransportId.valueOf(n.trim()) }.getOrNull() }.toSet()
                manager.muted = set
                linkLog?.write("debug_mute") { put("transports", set.joinToString()) }
            }
        }
        val filter = android.content.IntentFilter(ACTION_DEBUG_MUTE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(hook, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(hook, filter)
        muteHook = hook
    }

    private fun stopSession() {
        muteHook?.let { runCatching { unregisterReceiver(it) } }
        muteHook = null
        val s = session
        session = null
        linkLog?.let { log ->
            log.write("session_stop")
            log.close()
        }
        linkLog = null
        activeManager = null
        activeNetworkId = null
        activeWifiDirect = null
        activeBook = null
        activeDiscovery = null
        DiscoveryBridge.onRequestJoin = null
        DiscoveryBridge.onApprove = null
        DiscoveryBridge.onReject = null
        ShareInbox.sink = null
        // Cancel the session's collectors now so nothing republishes after the
        // SessionBus.clear() below, and install a fresh scope for the next Start.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Transport shutdown can block (GATT close, socket teardown): keep it off the
        // main thread. Shutdowns are chained so a restart never overlaps two of them.
        val previous = stopping
        stopping = CoroutineScope(Dispatchers.IO).launch {
            previous?.join()
            runCatching { s?.stop() }
        }
        isRunning = false
        listening = false
        // Wipe all session-scoped state: peers, the beacon buffer (lastWireFrame),
        // diagnostics, radio states, discovery — otherwise the screens keep showing
        // stale "data" text after the user turns everything off.
        SessionBus.clear()
    }

    private fun buildNotification(listen: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_active_network), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (listen) R.string.notif_listening else R.string.notif_active))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.icegood.findmeinwood.action.STOP"
        /** How long a join request waits for a link to the member it asks. */
        private const val JOIN_LINK_WAIT_MS = 20_000L
        private const val MAX_JOIN_LINKS = 2
        /** LNP/3 SMS link cadence (FR-3.9): one position SMS per peer on an SMS link per 30 min. */
        const val SMS_INTERVAL_MS = 30 * 60_000L
        const val ACTION_DEBUG_MUTE = "com.icegood.findmeinwood.debug.MUTE"
        const val ACTION_LISTEN = "com.icegood.findmeinwood.action.LISTEN"
        const val EXTRA_NAME = "name"
        const val EXTRA_NETWORK_ID = "networkId"
        const val EXTRA_TRAFFIC_KEY = "trafficKey"
        const val EXTRA_OWNER_ID = "ownerId"
        const val EXTRA_POLICY = "policy"

        @Volatile var isRunning: Boolean = false
            private set

        /** Running only to find networks to join (no network of our own yet). */
        @Volatile var listening: Boolean = false
            private set

        /** Debug builds: where the current session's link log is written (persists after stop). */
        @Volatile var linkLogFile: String? = null
            private set

        @Volatile var activeManager: SessionManager? = null

        /** Network of the running session (null while only listening), for incoming SMS. */
        @Volatile var activeNetworkId: com.icegood.findmeinwood.core.model.NetworkId? = null
            private set
        @Volatile var activeDiscovery: DiscoveryManager? = null
            private set

        @Volatile var activeWifiDirect: WifiDirectTransport? = null
            private set

        /** The running network's member list (null while listening or stopped). */
        @Volatile var activeBook: com.icegood.findmeinwood.core.session.MemberBook? = null

        /** FR-4.4: pause or resume sending my position in network [networkIdHex]. */
        fun setSharingPaused(context: Context, networkIdHex: String, paused: Boolean) {
            Prefs.setSharingPaused(context, networkIdHex, paused)
            val running = activeNetworkId?.bytes?.joinToString("") { "%02x".format(it) }
            if (running == networkIdHex) activeManager?.sharingPaused = paused
        }

        /** Master only: drops [id] from the member list and tells the others. */
        fun removeMember(id: MemberId): Boolean {
            val b = activeBook ?: return false
            val m = activeManager ?: return false
            if (!b.remove(id)) return false
            m.forgetRemoved()
            m.onBookChanged?.invoke()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                m.syncBook(force = true)
                // It still holds the network key: re-salt it for everyone else.
                m.rekey()
            }
            return true
        }

        /** Flip the Wi-Fi Direct role while a session is running (host = group owner). */
        fun setP2pGroupOwner(context: Context, owner: Boolean) {
            Prefs.setP2pGroupOwner(context, owner)
            activeWifiDirect?.setPreferGroupOwner(owner)
        }

        fun start(context: Context, p: StoredProfile) {
            val i = intentFor(context, p)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        private fun intentFor(context: Context, p: StoredProfile) =
            Intent(context, SessionService::class.java)
                .putExtra(EXTRA_NAME, p.name)
                .putExtra(EXTRA_NETWORK_ID, p.networkIdHex)
                .putExtra(EXTRA_TRAFFIC_KEY, p.trafficKeyHex)
                .putExtra(EXTRA_OWNER_ID, p.masterIdHex)
                .putExtra(EXTRA_POLICY, p.policy.name)

        /** Starts listening for networks to join; no-op while a session already runs. */
        fun listen(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_LISTEN)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.hex(key: String) = getStringExtra(key)!!.hex()
        private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
