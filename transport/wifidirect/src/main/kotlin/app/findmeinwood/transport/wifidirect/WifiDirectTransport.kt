package app.findmeinwood.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * WiFi Direct transport (T4.1, wifidirect.wsd): P2P group + TCP sockets,
 * framing [u32be len][frame], one socket per peer, send fans out.
 *
 * Nearby-devices/location permission is requested and gated by the app layer
 * before this transport is started; runtime rejections surface as SecurityException.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(private val context: Context) : Transport {
    override val id: TransportId = TransportId.WIFI_DIRECT

    private val events = MutableSharedFlow<TransportEvent>(
        replay = 64, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var acceptLoopStarted = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager: WifiP2pManager? get() = runCatching {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }.getOrNull()
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            try {
                channel = manager?.initialize(context, context.mainLooper, null)
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) { emit(RadioState.LINK_DOWN) }
                })
                requestPeerList()
                startAcceptLoop()
                emit(RadioState.SCANNING)
            } catch (e: Exception) {
                emit(RadioState.LINK_DOWN)
            }
        }
        return events
    }

    override suspend fun stop() {
        running = false
        ready = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        try { manager?.removeGroup(channel, null) } catch (_: Exception) {}
        emit(RadioState.STOPPED)
    }

    override suspend fun send(wireFrame: ByteArray): SendResult {
        if (!running || !ready) return SendResult.Failed("no p2p group")
        if (sockets.isEmpty()) return SendResult.Failed("no p2p peer")
        sockets.values.forEach { s ->
            runCatching {
                val out = DataOutputStream(s.getOutputStream())
                out.writeInt(wireFrame.size)
                out.write(wireFrame)
                out.flush()
            }.onFailure {
                runCatching { s.close() }
                sockets.remove(s.remoteSocketAddress.toString(), s)
            }
        }
        return SendResult.Sent
    }

    private fun emit(state: RadioState) = events.tryEmit(TransportEvent.StateChanged(state))

    private fun startAcceptLoop() {
        if (acceptLoopStarted) return
        acceptLoopStarted = true
        scope.launch {
            val server = ServerSocket()
            serverSocket = server
            server.bind(InetSocketAddress(PORT))
            while (running) {
                val s = try { server.accept() } catch (_: Exception) { break }
                registerSocket(s)
            }
        }
    }

    private fun requestPeerList() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch, object : WifiP2pManager.PeerListListener {
                override fun onPeersAvailable(peers: android.net.wifi.p2p.WifiP2pDeviceList) {
                    if (!running) return
                    peers.deviceList.forEach { dev ->
                        val key = dev.deviceAddress ?: return@forEach
                        if (sockets.containsKey(key) || !connecting.add(key)) return@forEach
                        try {
                            val config =
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    WifiP2pConfig.Builder().setDeviceAddress(android.net.MacAddress.fromString(key)).build()
                                } else {
                                    @Suppress("DEPRECATION")
                                    WifiP2pConfig().apply { deviceAddress = key }
                                }
                            m.connect(
                                ch,
                                config,
                                object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {}
                                    override fun onFailure(reason: Int) { connecting.remove(key) }
                                },
                            )
                        } catch (e: Exception) { connecting.remove(key) }
                    }
                }
            })
        } catch (_: SecurityException) {}
    }

    private fun requestConnectionInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch, object : WifiP2pManager.ConnectionInfoListener {
                override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                    if (!running || !info.groupFormed) return
                    startAcceptLoop()
                    val go = info.groupOwnerAddress ?: return
                    if (!info.isGroupOwner) {
                        val key = "go:" + go.hostAddress
                        if (!sockets.containsKey(key) && connecting.add(key)) {
                            scope.launch {
                                try {
                                    val s = Socket()
                                    s.connect(InetSocketAddress(go, PORT), 5000)
                                    registerSocket(s)
                                } catch (e: Exception) { connecting.remove(key) }
                            }
                        }
                    }
                }
            })
        } catch (_: SecurityException) {}
    }

    private fun registerSocket(s: Socket) {
        val key = s.remoteSocketAddress.toString()
        sockets[key] = s
        connecting.remove(key)
        if (!ready) { ready = true; emit(RadioState.READY) }
        events.tryEmit(TransportEvent.PeerRadioVisible(key, id))
        scope.launch {
            try {
                val input = DataInputStream(s.getInputStream())
                while (running && !s.isClosed) {
                    val len = input.readInt()
                    require(len in 1..MAX_FRAME) { "bad frame len" }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    events.tryEmit(TransportEvent.FrameReceived(FrameCodec.decode(buf), id))
                }
            } catch (_: Exception) {
                runCatching { s.close() }
                sockets.remove(key, s)
            }
        }
        scope.launch {
            while (running) {
                delay(15_000)
                requestPeerList(); requestConnectionInfo()
            }
        }
    }

    companion object {
        const val PORT = 8765
        const val MAX_FRAME = 65_507
    }
}
