package com.icegood.findmeinwood.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Outcome of a P2P request: BUSY means "already running", not a failure. */
enum class P2pResult { OK, BUSY, FAILED }

/** Group state as reported by the framework (kept free of platform value types). */
data class GroupInfo(
    val formed: Boolean,
    val isOwner: Boolean,
    val ownerHost: String?,
)

/**
 * Platform seam for Wi-Fi Direct (T4.1): every WifiP2pManager call, P2P broadcast
 * and TCP socket lives here, so the transport keeps only the decision logic.
 * Device-verified like BluetoothGattWiring (constitution P6).
 */
@SuppressLint("MissingPermission")
open class WifiDirectWiring(private val context: Context) {
    private val manager: WifiP2pManager? get() = runCatching {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }.getOrNull()

    open fun initialize(): WifiP2pManager.Channel? = runCatching {
        manager?.initialize(context, context.mainLooper, null)
    }.getOrNull()

    open fun discoverPeers(channel: WifiP2pManager.Channel?, done: (P2pResult) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.discoverPeers(ch, action(done))
        }.onFailure { done(P2pResult.FAILED) }
    }

    /** Visible peers as device addresses (MAC). */
    open fun requestPeers(channel: WifiP2pManager.Channel?, peers: (List<String>) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestPeers(ch) { list ->
                peers(list.deviceList.mapNotNull { it.deviceAddress })
            }
        }
    }

    open fun requestConnectionInfo(channel: WifiP2pManager.Channel?, info: (GroupInfo) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestConnectionInfo(ch) { i: WifiP2pInfo ->
                info(
                    GroupInfo(
                        formed = i.groupFormed,
                        isOwner = i.isGroupOwner,
                        ownerHost = i.groupOwnerAddress?.hostAddress,
                    ),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    open fun connect(
        channel: WifiP2pManager.Channel?,
        address: String,
        ownerIntent: Int,
        done: (P2pResult) -> Unit,
    ) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            val config = WifiP2pConfig().apply {
                deviceAddress = address
                groupOwnerIntent = ownerIntent
            }
            m.connect(ch, config, action(done))
        }.onFailure { done(P2pResult.FAILED) }
    }

    open fun createGroup(channel: WifiP2pManager.Channel?, done: (P2pResult) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.createGroup(ch, action(done)) }.onFailure { done(P2pResult.FAILED) }
    }

    open fun removeGroup(channel: WifiP2pManager.Channel?) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.removeGroup(ch, null) }
    }

    /**
     * P2P group state is only visible through broadcasts: without them the
     * transport never learns that a group was formed (or lost).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    open fun register(
        onWifiState: (Boolean) -> Unit,
        onPeers: () -> Unit,
        onConnection: () -> Unit,
    ): BroadcastReceiver? {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onWifiState(
                        intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1,
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED,
                    )
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnection()
                }
            }
        }
        // android.jar stubs throw for IntentFilter in plain JVM tests.
        return runCatching {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiver
        }.getOrNull()
    }

    open fun unregister(receiver: BroadcastReceiver?) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
    }

    open fun bind(port: Int): ServerSocket? = runCatching {
        // reuse so a restarted session (or a second test JVM) can bind right away
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
    }.getOrNull()

    open fun connectSocket(host: String, port: Int, timeoutMs: Int): Socket? = runCatching {
        Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
    }.getOrNull()

    private fun action(done: (P2pResult) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { done(P2pResult.OK) }
        override fun onFailure(reason: Int) {
            done(if (reason == WifiP2pManager.BUSY) P2pResult.BUSY else P2pResult.FAILED)
        }
    }
}
