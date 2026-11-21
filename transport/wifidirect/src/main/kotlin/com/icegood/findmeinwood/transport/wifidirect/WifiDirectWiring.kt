package com.icegood.findmeinwood.transport.wifidirect

import com.icegood.findmeinwood.core.model.WifiBand
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

/**
 * Credentials of the network's Wi-Fi Direct group, derived from a secret only members know.
 * Joining with them is a WPA2 login, not an invitation: neither phone shows a dialog, and
 * phones outside the network cannot join.
 */
data class GroupCredentials(
    val networkName: String,
    val passphrase: String,
    /** The host creates the group on it, a joiner scans only it; null = any band. */
    val band: WifiBand? = null,
    /**
     * The channel (MHz) the group is meant to run on, derived from the secret: the host asks
     * for it and a joiner first scans only it, so a join takes about a second instead of a
     * sweep over every channel (~9 s). 0 = none.
     */
    val frequency: Int = 0,
) {
    /** WifiP2pConfig's band for [band]. */
    val p2pBand: Int get() = when (band) {
        WifiBand.GHZ_2_4 -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        WifiBand.GHZ_5 -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
        null -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
    }

    companion object {
        /** Android requires "DIRECT-" + two characters; the passphrase must be 8–63 chars. */
        fun from(secret: ByteArray, band: WifiBand? = null): GroupCredentials {
            require(secret.size >= 20) { "secret too short" }
            val pass = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret.copyOfRange(4, 20))
            return GroupCredentials(com.icegood.findmeinwood.transport.api.groupNetworkName(secret), pass, band, channelOf(secret, band))
        }

        /**
         * The group's channel: one of the P2P social channels 1/6/11 on 2.4 GHz (the default
         * band, also when the host's band is not known yet), one of the non-DFS channels
         * 36–48 on 5 GHz (no radar wait), picked by the secret so groups nearby spread out.
         */
        fun channelOf(secret: ByteArray, band: WifiBand?): Int {
            val pick = secret[20].toInt() and 0xff
            return if (band == WifiBand.GHZ_5) FREQS_5[pick % FREQS_5.size] else FREQS_2_4[pick % FREQS_2_4.size]
        }

        private val FREQS_2_4 = listOf(2412, 2437, 2462)
        private val FREQS_5 = listOf(5180, 5200, 5220, 5240)

        /**
         * The channel a host asks for: its Wi-Fi's channel ([stationMhz]) when that is in the
         * group's band and a group may run there (2.4 GHz 1-13; 5 GHz 36-48 and 149-165,
         * no radar wait), else [GroupCredentials.frequency]. Two channels in one band make
         * the radio hop between them (MCC): the group and the Wi-Fi each get half the time,
         * and a joiner's scan misses the host. Another band runs alongside on a dual-band
         * radio, and the band is the user's choice: then the group keeps its own channel.
         */
        fun hostChannel(creds: GroupCredentials, stationMhz: Int?): Int {
            val sta = stationMhz ?: return creds.frequency
            val ok = when (creds.band ?: WifiBand.GHZ_2_4) {
                WifiBand.GHZ_2_4 -> sta in 2412..2472
                WifiBand.GHZ_5 -> sta in 5180..5240 || sta in 5745..5825
            }
            return if (ok) sta else creds.frequency
        }
    }
}

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

    open fun stopPeerDiscovery(channel: WifiP2pManager.Channel?) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.stopPeerDiscovery(ch, null) }
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

    /** Device addresses of the group owners in sight (phones hosting a group now). */
    @SuppressLint("MissingPermission")
    open fun requestOwners(channel: WifiP2pManager.Channel?, owners: (Set<String>) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestPeers(ch) { list ->
                owners(list.deviceList.filter { it.isGroupOwner }.mapNotNull { it.deviceAddress }.toSet())
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

    /**
     * Joins the group with [creds] (no invitation, so no dialog on either phone). [exact]:
     * scan only the group's channel ([GroupCredentials.frequency]), else its band.
     */
    open fun join(
        channel: WifiP2pManager.Channel?,
        creds: GroupCredentials,
        exact: Boolean,
        done: (P2pResult) -> Unit,
    ) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.connect(ch, config(creds, exact), action(done))
        }.onFailure { done(P2pResult.FAILED) }
    }

    /** Channel (MHz) of the Wi-Fi network this phone is connected to; null = none. */
    @Suppress("DEPRECATION") // connectionInfo: the replacement needs a network callback
    open fun stationMhz(): Int? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        wm?.connectionInfo?.takeIf { it.networkId != -1 }?.frequency?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * Hosts the group under [creds], so members can join it without a dialog. [exact]: on
     * the group's channel ([GroupCredentials.frequency]), else anywhere in its band.
     */
    open fun createGroup(
        channel: WifiP2pManager.Channel?,
        creds: GroupCredentials,
        exact: Boolean,
        done: (P2pResult) -> Unit,
    ) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.createGroup(ch, config(creds, exact), action(done))
        }.onFailure { done(P2pResult.FAILED) }
    }

    /**
     * Band and channel are exclusive in WifiP2pConfig. A frequency in MHz reaches the
     * supplicant correctly on Android 10 too ("for join with frequency 2437"), unlike the
     * band constant (see [operatingBand]).
     */
    private fun config(creds: GroupCredentials, exact: Boolean): WifiP2pConfig {
        val b = WifiP2pConfig.Builder()
            .setNetworkName(creds.networkName)
            .setPassphrase(creds.passphrase)
        if (exact && creds.frequency > 0) b.setGroupOperatingFrequency(creds.frequency)
        else b.setGroupOperatingBand(operatingBand(creds))
        return b.build()
    }

    /**
     * The band to ask for. Android 10 hands the band constant to the supplicant as is, and
     * the supplicant reads it as a band of its own: GROUP_OWNER_BAND_5GHZ (2) means "2 GHz"
     * there. A joiner then scans 2.4 GHz only and never finds a host on 5 GHz (seen on
     * Huawei VOG-L29, minutes without a link until an unrelated scan covered 5 GHz).
     * Android 11 maps the constants, so before it any band is asked for: every channel.
     */
    private fun operatingBand(creds: GroupCredentials): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) creds.p2pBand
        else WifiP2pConfig.GROUP_OWNER_BAND_AUTO

    /**
     * Name of the group this phone is in now, also while it still waits for its address
     * (connection info says "not formed" until then); null = in none.
     */
    @SuppressLint("MissingPermission")
    open fun requestGroupName(channel: WifiP2pManager.Channel?, name: (String?) -> Unit) {
        val m = manager ?: return name(null)
        val ch = channel ?: return name(null)
        runCatching { m.requestGroupInfo(ch) { g -> name(g?.networkName) } }.onFailure { name(null) }
    }

    /** Channel (MHz) of the group this phone is in now; null = none or not reported. */
    @SuppressLint("MissingPermission")
    open fun requestGroupFrequency(channel: WifiP2pManager.Channel?, mhz: (Int?) -> Unit) {
        val m = manager ?: return mhz(null)
        val ch = channel ?: return mhz(null)
        runCatching { m.requestGroupInfo(ch) { g -> mhz(g?.frequency?.takeIf { it > 0 }) } }.onFailure { mhz(null) }
    }

    /** Aborts a group negotiation that hangs (the P2P service then refuses every new request). */
    open fun cancelConnect(channel: WifiP2pManager.Channel?) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.cancelConnect(ch, null) }
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
