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
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.core.session.GnssSource
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.session.DiscoveryManager
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Hosts one active network session (P7/T2.5): foreground notification, transports,
 * GNSS source, share inbox. One session at a time (v1).
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SessionManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startSession(intent)
        }
        return START_STICKY
    }

    private fun startSession(intent: Intent?) {
        if (session != null) return
        // START_STICKY: after a process death Android restarts us with a null intent.
        // Without profile extras there is nothing to run, so stop instead of crashing.
        if (intent?.getStringExtra(EXTRA_NETWORK_ID) == null) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())

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

        val deltaMs = Prefs.intervalMs(this)
        val gnss: GnssSource = AndroidGnssSource(this, deltaMs)
        val wifiDirect = WifiDirectTransport(this).apply {
            setPreferGroupOwner(Prefs.p2pGroupOwner(this@SessionService))
        }
        activeWifiDirect = wifiDirect
        val transports = listOf<Transport>(
            // link timeout tracks the beacon cadence: 3 missed beacons = dead link
            BluetoothTransport(this, linkTimeoutMs = (deltaMs * 3).coerceAtLeast(120_000L)),
            wifiDirect,
            LoraTransport(this, BleNodeLink(this, Prefs.loraNode(this))),
            ShareTransport(this),
        )
        val identityPair = IdentityHolder.keyPair
        val discovery = DiscoveryManager(profile, identityPair, { System.currentTimeMillis() })
        discovery.ownerSecret = Prefs.secretFor(this, profile.networkId.bytes.joinToString("") { "%02x".format(it) })
        val manager = SessionManager(
            profile, transports, gnss.fixes(),
            { System.currentTimeMillis() }, scope = scope, keepAliveMs = deltaMs,
            signer = IdentityHolder.keyPair,
        )
        // Automatic handoff (FR-9.2): newest envelope goes to the clipboard every
        // delta, silently — the user pastes it into the messenger, no UI is raised.
        DiscoveryBridge.onRequestJoin = { netId ->
            val wire = discovery.buildJoinRequest(netId, Prefs.currentUid(this@SessionService))
            scope.launch {
                manager.transportIds().forEach { }
                manager.sendMgmt(wire)
            }
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
            runCatching {
                val f = MgmtCodec.decode(wire)
                val before = discovery.pendingJoins.value.size
                discovery.onMgmtFrame(f, null)
            }
            SessionBus.publishPending(discovery.pendingJoins.value)
            SessionBus.publishDiscovered(discovery.discovered.value)
            val outcome = discovery.outcome.value
            if (outcome != null) SessionBus.publishJoinOutcome(outcome)
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
                val outcome = discovery.outcome.value
                if (outcome != null) { SessionBus.publishJoinOutcome(outcome); discovery.clearOutcome() }
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
        scope.launch {
            manager.peerFlow.collect { peers -> SessionBus.publish(profile, peers, SessionBus.lastWireFrame.value) }
        }
        scope.launch {
            manager.diagnostics.collect { SessionBus.publishDiagnostics(it) }
        }
        manager.start()
        session = manager
        activeManager = manager
        activeDiscovery = discovery
        isRunning = true
        SessionBus.setSessionRunning(true)
    }

    private fun stopSession() {
        val s = session
        session = null
        activeManager = null
        activeWifiDirect = null
        activeDiscovery = null
        DiscoveryBridge.onRequestJoin = null
        DiscoveryBridge.onApprove = null
        DiscoveryBridge.onReject = null
        ShareInbox.sink = null
        s?.let { runBlocking { it.stop() } }
        scope.cancel()
        isRunning = false
        SessionBus.setSessionRunning(false)
    }

    private fun clipboard(ctx: Context, envelope: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("find-me-in-wood beacon", envelope))
        }
    }

    private fun buildNotification(): Notification {
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
            .setContentText("Network session active")
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
        const val EXTRA_NAME = "name"
        const val EXTRA_NETWORK_ID = "networkId"
        const val EXTRA_TRAFFIC_KEY = "trafficKey"
        const val EXTRA_MY_ID = "myId"
        const val EXTRA_OWNER_ID = "ownerId"
        const val EXTRA_POLICY = "policy"

        @Volatile var isRunning: Boolean = false
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
            val i = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_NAME, p.name)
                .putExtra(EXTRA_NETWORK_ID, p.networkIdHex)
                .putExtra(EXTRA_TRAFFIC_KEY, p.trafficKeyHex)
                .putExtra(EXTRA_MY_ID, p.myMemberIdHex)
                .putExtra(EXTRA_OWNER_ID, p.ownerMemberIdHex)
                .putExtra(EXTRA_POLICY, p.policy.name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.hex(key: String) = getStringExtra(key)!!.hex()
        private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
