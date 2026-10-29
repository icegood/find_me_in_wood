package com.icegood.findmeinwood.transport.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.GroupPlan
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
 * appears on either phone and devices outside the network are never contacted. Groups:
 * the session hands the ones this phone serves ([setGroups], FR-3.5b) — the one it hosts,
 * one per member it joins, the network's common group while a handshake is pending. A
 * phone can be in one group at a time, so several are served in turn: each for a few
 * seconds once a member is linked (both sides send a beacon at once), or until a group
 * nobody showed up in has had its slot; [serveNow] jumps to one with urgent traffic. Every
 * group has its pair's role: the host hosts, the others only join. No plan, no group.
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
    /** Credentials of the group served now; null = no group to serve. */
    @Volatile private var creds: GroupCredentials? = null
    /** Since when a create/join is under way with no group yet (0 = none). */
    private var formingSinceMs = 0L
    /** That attempt hosts (a create forms at once when it works) rather than joins (scans first). */
    private var formingCreate = false

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

    /** Role in the group served now, agreed by its pair (LNP/3). */
    @Volatile private var role: GroupRole? = null

    /** The groups to serve ([setGroups]); empty = none, the radio stays out of groups. */
    @Volatile private var plan: List<GroupPlan> = emptyList()
    /** Index in [plan] of the group served now, and since when. */
    private var slot = 0
    private var slotSinceMs = 0L
    /** When the first member socket of this slot came up (0 = none yet). */
    private var linkedAtMs = 0L

    /**
     * The groups to serve. The one served now is kept when it is still in the plan (its
     * link carries on); otherwise the radio leaves it for the plan's first.
     */
    @Synchronized
    override fun setGroups(groups: List<GroupPlan>) {
        if (groups == plan) return
        val current = creds
        plan = groups
        val keep = groups.indexOfFirst { GroupCredentials.from(it.secret) == current }
        if (keep >= 0) {
            val before = role
            slot = keep
            applySlotRole()
            if (!running) return
            if (groupFormed) {
                yieldIdleHost()
            } else if (role != before) {
                // A new role for the group being formed: the pending request was the other one.
                joinPending = false
                createPending = false
                formGroup()
            }
            return
        }
        moveTo(0)
    }

    /** Jump to the group at [index] now (its pair has something urgent). */
    @Synchronized
    override fun serveNow(index: Int) {
        if (index == slot || index !in plan.indices) return
        moveTo(index)
    }

    override fun closeLink(link: String) {
        sockets.remove(link)?.let { runCatching { it.close() } }
    }

    /** Leaves the group served now (if any) and goes to the plan's group at [index]. */
    private fun moveTo(index: Int) {
        if (running && (groupFormed || ready)) {
            wiring.removeGroup(channel)
            dropGroup()
        }
        joinPending = false
        createPending = false
        formingSinceMs = 0L
        slot = index
        slotSinceMs = System.currentTimeMillis()
        linkedAtMs = 0L
        creds = plan.getOrNull(index)?.let { GroupCredentials.from(it.secret) }
        applySlotRole()
        if (running) formGroup()
    }

    private fun applySlotRole() {
        role = plan.getOrNull(slot)?.role
    }

    /**
     * Several groups: the slot ends a few seconds after a member linked (what was pending
     * has gone both ways), or when nobody showed up within its time.
     */
    private fun rotate() {
        if (plan.size < 2 || preferOwner) return
        val now = System.currentTimeMillis()
        val done = if (linkedAtMs != 0L) now - linkedAtMs >= LINKED_DWELL_MS else now - slotSinceMs >= SLOT_MS
        if (done) moveTo((slot + 1) % plan.size)
    }


    /** Open peer sockets (diagnostics). */
    fun peerLinkCount(): Int = sockets.size

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            startMs = System.currentTimeMillis()
            slotSinceMs = startMs
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
            if (!writeFrame(s, wireFrame)) {
                runCatching { s.close() }
                sockets.remove(s.remoteSocketAddress.toString(), s)
            }
        }
        return SendResult.Sent
    }

    /** One length-prefixed frame (empty = heartbeat); false when the socket is dead. */
    private fun writeFrame(s: Socket, frame: ByteArray): Boolean = runCatching {
        // Beacons, chat, relays and heartbeats send concurrently: without the lock two
        // frames' length prefixes and bodies interleave and the peer's stream desyncs.
        synchronized(s) {
            val out = DataOutputStream(s.getOutputStream())
            out.writeInt(frame.size)
            out.write(frame)
            out.flush()
        }
    }.isSuccess

    private fun emit(state: RadioState) = events.tryEmit(TransportEvent.StateChanged(state))

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = wiring.register(
            onWifiState = ::onP2pState,
            onPeers = {},
            onConnection = { requestConnectionInfo() },
        )
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
                synchronized(this@WifiDirectTransport) { rotate() }
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

    /** Host or join the group served now, as its pair agreed. */
    private fun formGroup() {
        if (creds == null || groupFormed || !p2pOn) return
        if (preferOwner || role == GroupRole.HOST) createGroup() else joinGroup()
    }

    private fun joinGroup() {
        val c = creds ?: return
        if (joinPending && System.currentTimeMillis() - joinRequestedAtMs < PENDING_TIMEOUT_MS) return
        joinPending = true
        joinRequestedAtMs = System.currentTimeMillis()
        if (formingSinceMs == 0L) { formingSinceMs = joinRequestedAtMs; formingCreate = false }
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
        if (formingSinceMs == 0L) { formingSinceMs = createRequestedAtMs; formingCreate = true }
        wiring.createGroup(channel, c) {
            createPending = false
            requestConnectionInfo()
        }
    }

    /**
     * This phone hosts a group nobody linked to although its pair has it join (left over
     * from an earlier role): it gives the group up at once and joins. The host never does.
     */
    private fun yieldIdleHost() {
        if (preferOwner || role != GroupRole.JOIN || !isOwnerNow || sockets.isNotEmpty()) return
        wiring.removeGroup(channel)
        dropGroup()
    }

    @Volatile private var isOwnerNow = false


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
            formingSinceMs = 0L
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
        // A negotiation the P2P service sits on (seen for 30 s right after Wi-Fi came back)
        // blocks every new request: abort it and let the next poll ask again.
        val limit = if (formingCreate) CREATE_TIMEOUT_MS else FORM_TIMEOUT_MS
        if (!groupFormed && formingSinceMs != 0L && now - formingSinceMs > limit) {
            wiring.cancelConnect(channel)
            formingSinceMs = 0L
            createPending = false
            joinPending = false
            if (p2pOn) formGroup() // ask again at once, not a poll later
        }
        if (createPending && now - createRequestedAtMs > PENDING_TIMEOUT_MS) createPending = false
        if (joinPending && now - joinRequestedAtMs > PENDING_TIMEOUT_MS) joinPending = false
    }

    private fun registerSocket(s: Socket) {
        val key = s.remoteSocketAddress.toString()
        sockets[key] = s
        if (linkedAtMs == 0L) linkedAtMs = System.currentTimeMillis()
        connecting.remove(key)
        if (!ready) { ready = true; emit(RadioState.READY) }
        events.tryEmit(TransportEvent.PeerRadioVisible(key, id))
        events.tryEmit(TransportEvent.LinkUp(key, id))
        // A peer gone without a FIN (Wi-Fi off, group dropped) leaves a socket that only
        // its silence reveals: the read times out and the socket is closed.
        runCatching { s.soTimeout = SILENT_SOCKET_MS.toInt() }
        scope.launch {
            while (running && !s.isClosed) {
                delay(HEARTBEAT_MS)
                if (!writeFrame(s, ByteArray(0))) {
                    runCatching { s.close() }
                    sockets.remove(key, s)
                }
            }
        }
        scope.launch {
            try {
                val input = DataInputStream(s.getInputStream())
                while (running && !s.isClosed) {
                    val len = input.readInt()
                    if (len == 0) continue // heartbeat
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
        // FR-3.5c: a pair reconnects within 20 s whatever dropped it — a retry every poll,
        // a stuck create aborted after 3 s and a join after 5 s, a dead socket noticed
        // after 15 s.
        internal const val PENDING_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 2_000L
        /** Every socket carries an empty frame this often, so the other side sees it alive. */
        internal var HEARTBEAT_MS = 5_000L
        /** A socket silent this long is dead (the peer left the group without closing it). */
        internal var SILENT_SOCKET_MS = 15_000L
        /**
         * A join that formed no group by then is aborted and asked again: a scan and an
         * association take 2-3 s, while a join started before the host was back keeps
         * blacklisting it with growing back-off; a fresh request starts clean.
         */
        internal var FORM_TIMEOUT_MS = 5_000L
        /** A create forms its group in well under a second; one still pending is stuck. */
        internal var CREATE_TIMEOUT_MS = 3_000L
        /** Several groups: a slot ends this long after a member linked in it... */
        internal var LINKED_DWELL_MS = 5_000L
        /** ...or after this long when nobody did (time for a join: scan, associate, connect). */
        internal var SLOT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
