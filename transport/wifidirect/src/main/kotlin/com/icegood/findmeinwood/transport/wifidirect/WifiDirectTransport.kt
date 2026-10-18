package com.icegood.findmeinwood.transport.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.GroupRole
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
 * is why group formation is testable as plain JVM.
 *
 * The group runs under [GroupCredentials] derived from the network's secret: members
 * join it with name + passphrase (a WPA2 login, not an invitation), so no system dialog
 * appears on either phone and devices outside the network are never contacted. Roles:
 * once members agreed on Wi-Fi Direct, the agreement decides ([setGroupRole]): the host
 * hosts and never gives up, the others only join. Before that (or with nobody agreed in
 * reach) a phone first tries to join for a randomised window; if no member hosts yet it
 * hosts itself. A host nobody joins gives the group up after a while and tries joining
 * again, which resolves two phones that started hosting at the same time.
 * [setPreferGroupOwner] makes this phone always host, whatever was agreed.
 */
class WifiDirectTransport(
    private val context: Context,
    internal val wiring: WifiDirectWiring = WifiDirectWiring(context),
    /** Poll interval for group/peer state (shortened in tests). */
    internal val pollMs: Long = POLL_MS,
    /** TCP port of the group's frame sockets (tests use a free one: runs may overlap). */
    internal val port: Int = PORT,
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
    private var joinPending = false
    private var joinRequestedAtMs = 0L
    /** Group credentials of this network; null = no secret, so no group at all. */
    @Volatile private var creds: GroupCredentials? = null
    /** Until then try joining a member's group; afterwards host one. */
    private var hostAfterMs = 0L
    /** Since when this phone hosts a group nobody joined. */
    private var hostingAloneSinceMs = 0L

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
        if (v && running) formGroup()
    }

    fun isGroupOwner(): Boolean = preferOwner

    /**
     * P2P is usable (Wi-Fi on). Switched off outside the app, the group is gone and every
     * request would only be refused: report the radio off and wait for it to come back.
     */
    @Volatile private var p2pOn = true

    /** Role agreed with the members in reach (LNP/3); null = none, find one by trying. */
    @Volatile private var role: GroupRole? = null

    /**
     * A working group is kept whatever the role says: tearing it down would cut the very
     * link the agreement came over. A host nobody joined that should join gives up at once.
     */
    override fun setGroupRole(role: GroupRole?) {
        if (role == this.role) return
        this.role = role
        if (role == null) hostAfterMs = System.currentTimeMillis() + joinWindowMs()
        if (!running) return
        if (!groupFormed) formGroup() else yieldIdleHost()
    }

    /** Open peer sockets (diagnostics). */
    fun peerLinkCount(): Int = sockets.size

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            startMs = System.currentTimeMillis()
            creds = config.groupSecret?.let { GroupCredentials.from(it) }
            hostAfterMs = startMs + joinWindowMs()
            try {
                channel = wiring.initialize()
                // No channel = no P2P (missing permission, Wi-Fi off, unsupported):
                // say so instead of pretending to scan.
                if (channel == null) emit(RadioState.LINK_DOWN)
                registerReceiver()
                startAcceptLoop()
                discover()
                requestConnectionInfo()
                formGroup()
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

    override suspend fun send(wireFrame: ByteArray): SendResult = write(wireFrame, except = null)

    /** The group owner passes a member's frame on to the other members (they only reach it). */
    override suspend fun relay(wireFrame: ByteArray, except: String): SendResult = write(wireFrame, except)

    private fun write(wireFrame: ByteArray, except: String?): SendResult {
        if (!running || !ready) return SendResult.Failed("no p2p group")
        val targets = sockets.filterKeys { it != except }
        if (targets.isEmpty()) return SendResult.Failed("no p2p peer")
        targets.values.forEach { s ->
            runCatching {
                // Beacons, chat and relays send concurrently: without the lock two frames'
                // length prefixes and bodies interleave and the peer's stream desyncs.
                synchronized(s) {
                    val out = DataOutputStream(s.getOutputStream())
                    out.writeInt(wireFrame.size)
                    out.write(wireFrame)
                    out.flush()
                }
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
            onWifiState = ::onP2pState,
            onPeers = {},
            onConnection = { requestConnectionInfo() },
        )
    }

    /**
     * A new network key: the group moves to its credentials. A group under the old ones is
     * left (the removed member knows them), and the members' new group is formed at once.
     */
    override fun setGroupSecrets(current: ByteArray, older: List<ByteArray>) {
        val next = GroupCredentials.from(current)
        if (next == creds) return
        creds = next
        if (!running) return
        if (groupFormed || ready) {
            wiring.removeGroup(channel)
            dropGroup()
        }
        joinPending = false
        createPending = false
        if (role == null) hostAfterMs = System.currentTimeMillis() + joinWindowMs()
        formGroup()
    }

    /** Wi-Fi (and with it P2P) was switched on or off, in the app or outside it. */
    internal fun onP2pState(on: Boolean) {
        if (on == p2pOn) return
        p2pOn = on
        if (!running) return
        if (!on) {
            createPending = false
            if (groupFormed || ready) dropGroup() else emit(RadioState.LINK_DOWN)
            return
        }
        // Back on: look for the members' group at once, as at start.
        if (channel == null) channel = wiring.initialize()
        startMs = System.currentTimeMillis()
        if (role == null) hostAfterMs = startMs + joinWindowMs()
        lastDiscoverAtMs = 0L
        discoveryOkAtMs = 0L
        emit(RadioState.SCANNING)
        discover()
        requestConnectionInfo()
        formGroup()
    }

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
                if (!p2pOn) continue
                if (!groupFormed) {
                    if (channel == null) channel = wiring.initialize() // self-heal
                    discover()
                    formGroup()
                } else {
                    yieldIdleHost()
                }
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
            val server = wiring.bind(port)
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

    /** Host or join as agreed; without a role join a member's group, or host one once the join window is over. */
    private fun formGroup() {
        if (creds == null || groupFormed || !p2pOn) return
        val host = when {
            preferOwner -> true
            role != null -> role == GroupRole.HOST
            else -> System.currentTimeMillis() >= hostAfterMs
        }
        if (host) createGroup() else joinGroup()
    }

    private fun joinGroup() {
        val c = creds ?: return
        if (joinPending && System.currentTimeMillis() - joinRequestedAtMs < PENDING_TIMEOUT_MS) return
        joinPending = true
        joinRequestedAtMs = System.currentTimeMillis()
        wiring.join(channel, c) { result ->
            if (result != P2pResult.OK) joinPending = false
            requestConnectionInfo()
        }
    }

    private fun createGroup() {
        val c = creds ?: return
        if (createPending && System.currentTimeMillis() - createRequestedAtMs < PENDING_TIMEOUT_MS) return
        createPending = true
        createRequestedAtMs = System.currentTimeMillis()
        wiring.createGroup(channel, c) {
            createPending = false
            requestConnectionInfo()
        }
    }

    /**
     * A host nobody joined for a while gives the group up and goes back to joining: when
     * two phones started hosting at once, one of them ends up in the other's group. The
     * agreed host never gives up; one agreed to join gives up at once.
     */
    private fun yieldIdleHost() {
        if (preferOwner || role == GroupRole.HOST || !isOwnerNow || sockets.isNotEmpty()) {
            hostingAloneSinceMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (hostingAloneSinceMs == 0L) hostingAloneSinceMs = now
        if (role == GroupRole.JOIN || now - hostingAloneSinceMs >= hostIdleMs()) {
            hostingAloneSinceMs = 0L
            wiring.removeGroup(channel)
            dropGroup()
            hostAfterMs = now + joinWindowMs()
        }
    }

    @Volatile private var isOwnerNow = false

    /** Randomised so two phones do not keep making the same choice at the same moment. */
    private fun joinWindowMs() = JOIN_WINDOW_MS + Random.nextLong(JOIN_WINDOW_MS)
    private fun hostIdleMs() = HOST_IDLE_MS + Random.nextLong(HOST_IDLE_MS)

    private fun dropGroup() {
        if (!groupFormed && !ready) return
        joinPending = false
        isOwnerNow = false
        groupFormed = false
        ready = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        emit(RadioState.LINK_DOWN)
    }

    private fun requestConnectionInfo() {
        wiring.requestConnectionInfo(channel) { info ->
            if (!running) return@requestConnectionInfo
            if (!info.formed) {
                dropGroup()
                return@requestConnectionInfo
            }
            groupFormed = true
            joinPending = false
            isOwnerNow = info.isOwner
            startAcceptLoop()
            if (!ready) { ready = true; emit(RadioState.READY) }
            if (!info.isOwner) {
                val host = info.ownerHost ?: return@requestConnectionInfo
                val key = "go:$host"
                val linked = sockets.keys.any { it.startsWith("/$host:") }
                if (!linked && !sockets.containsKey(key) && connecting.add(key)) {
                    requestedAt[key] = System.currentTimeMillis()
                    scope.launch {
                        val s = wiring.connectSocket(host, port, CONNECT_TIMEOUT_MS)
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
        if (joinPending && now - joinRequestedAtMs > PENDING_TIMEOUT_MS) joinPending = false
    }

    private fun registerSocket(s: Socket) {
        val key = s.remoteSocketAddress.toString()
        sockets[key] = s
        connecting.remove(key)
        if (!ready) { ready = true; emit(RadioState.READY) }
        events.tryEmit(TransportEvent.PeerRadioVisible(key, id))
        events.tryEmit(TransportEvent.LinkUp(key, id))
        scope.launch {
            try {
                val input = DataInputStream(s.getInputStream())
                while (running && !s.isClosed) {
                    val len = input.readInt()
                    require(len in 1..MAX_FRAME) { "bad frame len" }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    if (MgmtCodec.isMgmt(buf)) events.tryEmit(TransportEvent.MgmtFrameReceived(buf, id))
                    else events.tryEmit(TransportEvent.FrameReceived(FrameCodec.decode(buf), id, key))
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
        /** Base time a phone tries to join before hosting itself (randomised up to 2x). */
        internal var JOIN_WINDOW_MS = 20_000L
        /** Base time a host stays alone before giving the group up (randomised up to 2x). */
        internal var HOST_IDLE_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
