package app.findmeinwood.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.findmeinwood.core.crypto.NetworkKeysFactory
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.core.session.GnssSource
import app.findmeinwood.core.session.SessionManager
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.bluetooth.BluetoothTransport
import app.findmeinwood.transport.lora.BleNodeLink
import app.findmeinwood.transport.lora.LoraTransport
import app.findmeinwood.transport.share.ShareInbox
import app.findmeinwood.transport.share.ShareTransport
import app.findmeinwood.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        startForeground(NOTIFICATION_ID, buildNotification())

        val name = intent?.getStringExtra(EXTRA_NAME) ?: "network"
        val profile = NetworkProfile(
            name = name,
            networkId = app.findmeinwood.core.model.NetworkId(intent!!.hex(EXTRA_NETWORK_ID)),
            trafficKey = intent.hex(EXTRA_TRAFFIC_KEY),
            myMemberId = MemberId(intent.hex(EXTRA_MY_ID)),
            ownerMemberId = MemberId(intent.hex(EXTRA_OWNER_ID)),
            policy = intent.getStringExtra(EXTRA_POLICY)?.let {
                runCatching { JoinPolicy.valueOf(it) }.getOrNull()
            } ?: JoinPolicy.PRIVATE,
        )

        val gnss: GnssSource = AndroidGnssSource(this)
        val transports = listOf<Transport>(
            BluetoothTransport(this),
            WifiDirectTransport(this),
            LoraTransport(this, BleNodeLink(this, Prefs.loraNode(this))),
            ShareTransport(this),
        )
        val manager = SessionManager(
            profile, transports, gnss.fixes(),
            { System.currentTimeMillis() }, scope = scope,
        )
        ShareInbox.sink = { wire ->
            scope.launch { manager.acceptExternalWire(wire, TransportId.INTERNET) }
        }
        manager.onFrameSent = { wire -> SessionBus.publish(profile, manager.peerFlow.value, wire) }
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
        isRunning = true
    }

    private fun stopSession() {
        val s = session
        session = null
        activeManager = null
        ShareInbox.sink = null
        s?.let { runBlocking { it.stop() } }
        scope.cancel()
        isRunning = false
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
        const val ACTION_STOP = "app.findmeinwood.action.STOP"
        const val EXTRA_NAME = "name"
        const val EXTRA_NETWORK_ID = "networkId"
        const val EXTRA_TRAFFIC_KEY = "trafficKey"
        const val EXTRA_MY_ID = "myId"
        const val EXTRA_OWNER_ID = "ownerId"
        const val EXTRA_POLICY = "policy"

        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var activeManager: SessionManager? = null
            private set

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
