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
import com.icegood.findmeinwood.core.crypto.SosCodec
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.core.session.GnssSource
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.session.DiscoveryManager
import com.icegood.findmeinwood.core.session.EdgeManager
import com.icegood.findmeinwood.core.session.JoinOutcome
import com.icegood.findmeinwood.core.session.SessionManager
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
    private var sosJob: Job? = null
    /** At most 6 SOS rounds (one SMS to each recipient) per hour. */
    private val sosBudget = SmsBudget(maxPerHour = 6)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SOS_STOP -> stopSos(sendCancel = true)
            ACTION_SOS_START -> if (session != null && !listening) startSos() else {
                startSession(intent)
                if (session != null) startSos()
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
        runSession(ProfileStore.toProfile(throwaway).copy(policy = JoinPolicy.CODE), listen = true)
    }

    private fun startSession(intent: Intent?) {
        // START_STICKY: after a process death Android restarts us with a null intent.
        // Without profile extras there is nothing to run, so stop instead of crashing.
        if (intent?.getStringExtra(EXTRA_NETWORK_ID) == null) {
            stopSelf()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "network"
        val profile = NetworkProfile(
            name = name,
            networkId = com.icegood.findmeinwood.core.model.NetworkId(intent.hex(EXTRA_NETWORK_ID)),
            trafficKey = intent.hex(EXTRA_TRAFFIC_KEY),
            myMemberId = MemberId(intent.hex(EXTRA_MY_ID)),
            ownerMemberId = MemberId(intent.hex(EXTRA_OWNER_ID)),
            policy = intent.getStringExtra(EXTRA_POLICY)?.let {
                runCatching { JoinPolicy.valueOf(it) }.getOrNull()
            } ?: JoinPolicy.PRIVATE,
        )
        runSession(profile, listen = false)
    }

    private fun runSession(profile: NetworkProfile, listen: Boolean) {
        // "Start" while a session is live restarts it (and "Restart" in the UI is
        // no longer a silent no-op). stopSession hands the old scope to its background
        // shutdown and installs a fresh one, which also covers Stop -> Start on the
        // same service instance, where session is already null.
        if (session != null) stopSession()
        startForeground(NOTIFICATION_ID, buildNotification(listen))
        listening = listen

        activeProfile = profile
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
        val discovery = DiscoveryManager(profile, identityPair, { System.currentTimeMillis() }, advertise = !listen)
        discovery.ownerSecret = Prefs.secretFor(this, profile.networkId.bytes.joinToString("") { "%02x".format(it) })
        val clock = { System.currentTimeMillis() }
        val manager = SessionManager(
            profile, transports, fixes,
            clock, scope = scope, keepAliveMs = deltaMs,
            // Per-peer transport + up/down state: shown in the debug link log.
            edgeManager = EdgeManager(profile.myMemberId, clock),
            signer = identityPair,
            beaconsEnabled = !listen,
        )
        linkLog = (if (listen) null else LinkLog.open(this))?.also { log ->
            linkLogFile = "Download/${LinkLog.FOLDER}/${log.fileName}"
            val sampler = LinkLogSampler(this, log, manager, bluetooth, wifiDirect, { lastFix }, deltaMs)
            manager.onEdgeDown = sampler::onEdgeDown
            sampler.start(scope, deltaMs)
        }
        // Automatic handoff (FR-9.2): newest envelope goes to the clipboard every
        // delta, silently — the user pastes it into the messenger, no UI is raised.
        DiscoveryBridge.onRequestJoin = { netId ->
            val wire = discovery.buildJoinRequest(netId, Prefs.currentDisplayName(this@SessionService))
            scope.launch { manager.sendMgmt(wire) }
        }
        DiscoveryBridge.onApprove = { joiner ->
            val wire = discovery.approve(joiner)
            if (wire == null) {
                SessionBus.publishOwnerNote(
                    "cannot approve: this device does not hold the network secret",
                )
            } else {
                scope.launch { manager.sendMgmt(wire) }
                val sas = discovery.ownerSas.value ?: "------"
                SessionBus.publishOwnerNote("approved — compare SAS $sas")
            }
        }
        DiscoveryBridge.onReject = { joiner ->
            val wire = discovery.reject(joiner)
            scope.launch { manager.sendMgmt(wire) }
        }
        activeDiscovery = discovery
        manager.onMgmtFrame = { wire, source ->
            runCatching { discovery.onMgmtFrame(MgmtCodec.decode(wire)) }
            SessionBus.publishPending(discovery.pendingJoins.value)
            SessionBus.publishDiscovered(discovery.discovered.value)
            discovery.outcome.value?.let(::onJoinOutcome)
            discovery.clearOutcome()
        }
        scope.launch {
            while (true) {
                delay(5_000)
                discovery.pruneStale()
                if (discovery.helloDue()) {
                    manager.sendMgmt(discovery.buildHello(manager.transportIds()))
                }
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
            SessionBus.publish(profile, manager.peerFlow.value, wire)
            clipboard(this, ShareTransport.encodeEnvelope(wire))
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
        // Members in SOS over the mesh become alerts too (same store/notification as SMS).
        scope.launch {
            val inSos = HashSet<MemberId>()
            manager.peerFlow.collect { peers ->
                peers.values.forEach { peer ->
                    val pos = peer.lastPosition
                    val wasSos = peer.memberId in inSos
                    if (peer.sos && pos != null || wasSos && !peer.sos) {
                        val alert = SosAlert(
                            senderHex = peer.memberId.bytes.copyOf(8).joinToString("") { "%02x".format(it) },
                            networkName = profile.name,
                            name = peer.nickname,
                            latE7 = pos?.latE7 ?: 0,
                            lonE7 = pos?.lonE7 ?: 0,
                            accuracyM = (pos?.accuracyCm ?: 0) / 100,
                            sentAtMs = peer.lastSeenMs,
                            receivedAtMs = System.currentTimeMillis(),
                            seq = peer.lastSeq.toLong(),
                            active = peer.sos,
                            via = "mesh",
                        )
                        if (SosAlerts.upsert(this@SessionService, alert) && peer.sos != wasSos) {
                            SosNotify.show(this@SessionService, alert)
                        }
                    }
                    if (peer.sos) inSos += peer.memberId else inSos -= peer.memberId
                }
            }
        }
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
            val stored = existing ?: (ProfileStore.join(outcome.name, outcome.secret) as? JoinResult.Ok)?.profile?.also {
                ProfileStore.add(this, it)
                Prefs.setSecretFor(this, it.networkIdHex, outcome.secret)
                // FR-8.4: unverified until the human compares the SAS
                Prefs.setUnverified(this, it.networkIdHex, true)
            }
            if (stored != null && listening) start(this, stored)
        }
        SessionBus.publishJoinOutcome(outcome)
    }

    // ------------------------------------------------------------------ SOS

    /**
     * SOS mode: every beacon carries the SOS flag (nearby members see it over the mesh),
     * and an encrypted one-SMS report goes to the member numbers (plain text with a maps
     * link to emergency contacts) right away and then every [Prefs.sosIntervalMin].
     * Plain SMS is what reaches satellites (direct-to-cell / carrier satellite SMS) when
     * there is no land network; apps get no other access to the satellite modem.
     */
    private fun startSos() {
        val manager = session ?: return
        if (sosJob?.isActive == true) return
        manager.sosActive = true
        linkLog?.write("sos_start")
        SessionBus.publishSos(SessionBus.SosState(active = true, note = "starting…"))
        sosJob = scope.launch {
            // Mesh first: free and immediate for anyone in radio range.
            runCatching { manager.sendBeaconSigned(sosBeacon()) }
            var rounds = 0
            while (true) {
                val note = sendSosRound(manager, cancelled = false)
                if (note == null) rounds++
                val interval = Prefs.sosIntervalMin(this@SessionService) * 60_000L
                val now = System.currentTimeMillis()
                SessionBus.publishSos(
                    SessionBus.SosState(
                        active = true,
                        roundsSent = rounds,
                        lastSentAtMs = if (note == null) now else SessionBus.sos.value.lastSentAtMs,
                        nextAtMs = now + interval,
                        note = note,
                        satellite = SosSms.onSatellite(this@SessionService),
                    ),
                )
                delay(interval)
            }
        }
    }

    private fun stopSos(sendCancel: Boolean) {
        val manager = session
        val wasActive = sosJob?.isActive == true
        sosJob?.cancel()
        sosJob = null
        manager?.sosActive = false
        if (wasActive && sendCancel && manager != null) {
            linkLog?.write("sos_cancel")
            scope.launch {
                runCatching { manager.sendBeaconSigned(sosBeacon().copy(sos = null)) }
                sendSosRound(manager, cancelled = true)
            }
        }
        SessionBus.publishSos(SessionBus.SosState(active = false))
    }

    private fun sosBeacon(): BeaconPayload =
        lastFix?.let { PayloadCodec.fromFix(it, batteryPct()) }?.copy(sos = true) ?: BeaconPayload(sos = true)

    /** One SMS to each recipient; returns a problem description, or null when sent. */
    private fun sendSosRound(manager: SessionManager, cancelled: Boolean): String? {
        val members = SosLogic.parseNumbers(Prefs.sosMemberNumbers(this))
        val contacts = SosLogic.parseNumbers(Prefs.sosContactNumbers(this))
        if (members.isEmpty() && contacts.isEmpty()) return "no SOS phone numbers in Settings — mesh only"
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "SMS permission not granted — mesh only"
        }
        val now = System.currentTimeMillis()
        if (!cancelled && !sosBudget.tryTake(now)) return "hourly SMS limit reached"
        val fix = lastFix ?: lastKnownFix()
        val battery = batteryPct()
        val profile = activeProfile ?: return "no network"
        var sent = 0
        if (members.isNotEmpty()) {
            val frame = SosCodec.encode(
                profile.trafficKey, profile.networkId, profile.myMemberId, manager.nextSeq(),
                active = !cancelled, cancelled = cancelled,
                timeS = (fix?.epochMs ?: now) / 1000,
                latE7 = fix?.let { (it.lat * 1e7).toInt() } ?: 0,
                lonE7 = fix?.let { (it.lon * 1e7).toInt() } ?: 0,
                accuracyM = fix?.accuracyM?.toInt() ?: 0xFFFF,
                batteryPct = battery,
            )
            sent += SosSms.send(this, members, frame)
        }
        if (contacts.isNotEmpty()) {
            val name = Prefs.currentDisplayName(this) ?: "A find-me-in-wood user"
            val text = SosLogic.plainMessage(
                name, fix?.lat, fix?.lon, fix?.accuracyM?.toInt(), battery, fix?.epochMs ?: now, cancelled,
            )
            sent += SosSms.send(this, contacts, text)
        }
        linkLog?.write(if (cancelled) "sos_cancel_sms" else "sos_sms") {
            put("recipients", members.size + contacts.size)
            put("handedToModem", sent)
            put("satellite", SosSms.onSatellite(this@SessionService))
            put("fixAccM", fix?.accuracyM)
        }
        return if (sent == 0) "SMS could not be sent" else null
    }

    private fun lastKnownFix(): GnssFix? = runCatching {
        val lm = getSystemService(android.location.LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.let { GnssFix(it.latitude, it.longitude, it.accuracy, it.altitude, it.time) }
    }.getOrNull()

    private fun batteryPct(): Int? = runCatching {
        getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()

    private fun stopSession() {
        stopSos(sendCancel = false)
        val s = session
        session = null
        linkLog?.let { log ->
            log.write("session_stop")
            log.close()
        }
        linkLog = null
        activeManager = null
        activeWifiDirect = null
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

    private fun clipboard(ctx: Context, envelope: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("find-me-in-wood beacon", envelope))
        }
    }

    private fun buildNotification(listen: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Active network", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("find me in wood")
            .setContentText(if (listen) "Looking for networks to join" else "Network session active")
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
        const val ACTION_SOS_START = "com.icegood.findmeinwood.action.SOS_START"
        const val ACTION_SOS_STOP = "com.icegood.findmeinwood.action.SOS_STOP"
        const val ACTION_LISTEN = "com.icegood.findmeinwood.action.LISTEN"
        const val EXTRA_NAME = "name"
        const val EXTRA_NETWORK_ID = "networkId"
        const val EXTRA_TRAFFIC_KEY = "trafficKey"
        const val EXTRA_MY_ID = "myId"
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
        @Volatile var activeDiscovery: DiscoveryManager? = null
            private set

        @Volatile var activeWifiDirect: WifiDirectTransport? = null
            private set

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
                .putExtra(EXTRA_MY_ID, p.myMemberIdHex)
                .putExtra(EXTRA_OWNER_ID, p.ownerMemberIdHex)
                .putExtra(EXTRA_POLICY, p.policy.name)

        /**
         * Starts SOS. With no session running, [fallback] (a stored network) is started
         * first — SOS needs the network keys and wants the mesh beacons too.
         */
        fun startSos(context: Context, fallback: StoredProfile?): Boolean {
            if ((activeManager == null || listening) && fallback == null) return false
            val i = if (activeManager != null && !listening) {
                Intent(context, SessionService::class.java)
            } else {
                intentFor(context, fallback!!)
            }.setAction(ACTION_SOS_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            return true
        }

        /** Starts listening for networks to join; no-op while a session already runs. */
        fun listen(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_LISTEN)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stopSos(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SOS_STOP))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.hex(key: String) = getStringExtra(key)!!.hex()
        private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
