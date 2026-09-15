package com.icegood.findmeinwood.transport.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
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
 * Decision logic only — every framework call goes through [WifiDirectWiring], which
 * is why group formation is testable as plain JVM. Roles: [setPreferGroupOwner]
 * makes this device host the group (createGroup, TCP server, 192.168.49.1);
 * otherwise it joins as a client and dials the owner.
 */
class WifiDirectTransport(
    private val context: Context,
    internal val wiring: WifiDirectWiring = WifiDirectWiring(context),
    /** Poll interval for group/peer state (shortened in tests). */
    internal val pollMs: Long = POLL_MS,
) : Transport {
    override val id: TransportId = TransportId.WIFI_DIRECT

    private val events = MutableSharedFlow<TransportEvent>(
        replay = 64, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var groupFormed = false
    @Volatile private var preferOwner = false
    @Volatile private var acceptLoopStarted = false
    private var startMs = 0L
    private var discoveryOkAtMs = 0L
    private var lastDiscoverAtMs = 0L
    private var createPending = false
    private var createRequestedAtMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    /** When each connect/create was requested: unanswered ones must expire. */
    private val requestedAt = ConcurrentHashMap<String, Long>()

    /** Host the group: this device owns 192.168.49.1 and peers join it. */
    fun setPreferGroupOwner(v: Boolean) {
        preferOwner = v
        if (v && running) createGroup()
    }

    fun isGroupOwner(): Boolean = preferOwner

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            startMs = System.currentTimeMillis()
            try {
                channel = wiring.initialize()
                // No channel = no P2P (missing permission, Wi-Fi off, unsupported):
                // say so instead of pretending to scan.
                if (channel == null) emit(RadioState.LINK_DOWN)
                registerReceiver()
                startAcceptLoop()
                discover()
                requestPeerList()
                requestConnectionInfo()
                if (preferOwner) createGroup()
                startPoll()
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
        groupFormed = false
        acceptLoopStarted = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        connecting.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        wiring.unregister(receiver)
        receiver = null
        wiring.removeGroup(channel)
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

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = wiring.register(
            onWifiState = { on -> if (!on) dropGroup() },
            onPeers = { requestPeerList() },
            onConnection = { requestConnectionInfo() },
        )
    }

    /**
     * Discovery also makes this device visible: a host that does not scan is never
     * found by its peers, so both roles scan — only dialing is host-forbidden.
     */
    /**
     * A discovery window lasts ~120 s: re-arming it every poll only yields BUSY,
     * which used to be reported as a dead link.
     */
    private fun discover() {
        val now = System.currentTimeMillis()
        if (now - lastDiscoverAtMs < DISCOVER_INTERVAL_MS) return
        lastDiscoverAtMs = now
        wiring.discoverPeers(channel) { result ->
            when (result) {
                P2pResult.OK, P2pResult.BUSY -> discoveryOkAtMs = System.currentTimeMillis()
                P2pResult.FAILED -> if (!groupFormed) emit(RadioState.LINK_DOWN)
            }
        }
    }

    /** P2P discovery stops on its own, so keep asking for peers and group state. */
    private fun startPoll() {
        scope.launch {
            while (running) {
                delay(pollMs)
                if (!running) break
                expirePending()
                if (!groupFormed) {
                    if (channel == null) channel = wiring.initialize() // self-heal
                    if (preferOwner) createGroup() else discover()
                }
                requestPeerList()
                requestConnectionInfo()
                // A disabled/ignoring P2P stack never answers: say so instead of
                // advertising a scan that is not happening (FR-3.6a failover).
                if (!groupFormed && (preferOwner || discoveryOkAtMs == 0L) &&
                    System.currentTimeMillis() - startMs > 3 * pollMs
                ) {
                    emit(RadioState.LINK_DOWN)
                }
            }
        }
    }

    private fun startAcceptLoop() {
        if (acceptLoopStarted) return
        acceptLoopStarted = true
        scope.launch {
            val server = wiring.bind(PORT)
            serverSocket = server
            if (server == null) {
                acceptLoopStarted = false
                return@launch
            }
            while (running) {
                val s = try { server.accept() } catch (_: Exception) { break }
                registerSocket(s)
            }
        }
    }

    private fun createGroup() {
        if (createPending && System.currentTimeMillis() - createRequestedAtMs < PENDING_TIMEOUT_MS) return
        createPending = true
        createRequestedAtMs = System.currentTimeMillis()
        wiring.createGroup(channel) {
            createPending = false
            requestConnectionInfo()
        }
    }

    private fun dropGroup() {
        if (!groupFormed && !ready) return
        groupFormed = false
        ready = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        emit(RadioState.LINK_DOWN)
    }

    private fun requestPeerList() {
        // A formed group is complete: as owner peers dial us, as client we joined it.
        if (groupFormed) return
        wiring.requestPeers(channel) { peers ->
            if (!running || groupFormed) return@requestPeers
            peers.forEach { key ->
                if (sockets.containsKey(key) || !connecting.add(key)) return@forEach
                // The host does not dial out: it created the group, peers join it.
                if (preferOwner) {
                    connecting.remove(key)
                    return@forEach
                }
                requestedAt[key] = System.currentTimeMillis()
                wiring.connect(channel, key, Random.nextInt(16)) { result ->
                    requestedAt.remove(key)
                    if (result != P2pResult.OK) connecting.remove(key)
                }
            }
        }
    }

    private fun requestConnectionInfo() {
        wiring.requestConnectionInfo(channel) { info ->
            if (!running) return@requestConnectionInfo
            if (!info.formed) {
                dropGroup()
                return@requestConnectionInfo
            }
            groupFormed = true
            startAcceptLoop()
            if (!ready) { ready = true; emit(RadioState.READY) }
            if (!info.isOwner) {
                val host = info.ownerHost ?: return@requestConnectionInfo
                val key = "go:$host"
                val linked = sockets.keys.any { it.startsWith("/$host:") }
                if (!linked && !sockets.containsKey(key) && connecting.add(key)) {
                    requestedAt[key] = System.currentTimeMillis()
                    scope.launch {
                        val s = wiring.connectSocket(host, PORT, CONNECT_TIMEOUT_MS)
                        requestedAt.remove(key)
                        connecting.remove(key)
                        if (s == null) Unit else registerSocket(s)
                    }
                }
            }
        }
    }

    /**
     * A connect the framework never answers (peer needs to accept, radio busy, OEM
     * quirk) must not block that peer forever: drop it so the next poll retries.
     */
    private fun expirePending() {
        val now = System.currentTimeMillis()
        requestedAt.entries.removeIf { (key, at) ->
            val dead = now - at > PENDING_TIMEOUT_MS
            if (dead) connecting.remove(key)
            dead
        }
        if (createPending && now - createRequestedAtMs > PENDING_TIMEOUT_MS) createPending = false
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
                    if (MgmtCodec.isMgmt(buf)) events.tryEmit(TransportEvent.MgmtFrameReceived(buf, id))
                    else events.tryEmit(TransportEvent.FrameReceived(FrameCodec.decode(buf), id))
                }
            } catch (_: Exception) {
                runCatching { s.close() }
                sockets.remove(key, s)
            }
        }
    }

    companion object {
        const val PORT = 8765
        const val MAX_FRAME = 65_507
        internal const val DISCOVER_INTERVAL_MS = 60_000L
        internal const val PENDING_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
