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
 * Where this phone's Wi-Fi Direct group stands. Every change goes through
 * [WifiDirectTransport.moveState]; the graph is in that class's comment.
 */
internal sealed interface GroupState {
    /** Not started, or stopped. */
    data object Stopped : GroupState
    /** Wi-Fi (and with it P2P) is off: every request would be refused. */
    data object RadioOff : GroupState
    /** The plan has no group to serve now. */
    data object Idle : GroupState
    /** Hosting: a create is under way since [since]; [askedAt] = last request, null = ask at the next poll. */
    data class Creating(val since: Long, val askedAt: Long?) : GroupState
    /**
     * Joining: a join is under way since [since]; [askedAt] as for [Creating]. [attempt]:
     * which try in a row this is (1 = the first), the later ones are given longer.
     */
    data class Joining(val since: Long, val askedAt: Long?, val attempt: Int = 1) : GroupState
    /** A join is overdue: asking the system which group this phone is in. */
    data class Checking(val since: Long, val attempt: Int) : GroupState
    /** In the group to join since [since], waiting for its address (DHCP). */
    data class Addressing(val since: Long, val attempt: Int) : GroupState
    /** The group is up; [owner] = this phone hosts it. */
    data class Formed(val owner: Boolean) : GroupState
}

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
 * the session hands the ones this phone serves ([setGroups], FR-3.5b) — the one it hosts
 * and one per member it joins. A phone can be in one group at a time, so several are
 * served in turn: each for a few seconds once a member is linked (both sides send a beacon
 * at once), or until a group nobody showed up in has had its slot; [serveNow] jumps to one
 * with urgent traffic. Every group has its pair's role: the host hosts, the others only
 * join. No plan, no group. [setPreferGroupOwner] makes this phone always host.
 *
 * Group state machine ([GroupState]). (target) = the state for the slot served now:
 * no group → Idle, hosting → Creating, else Joining; Creating and Joining send their
 * request on entry, and again at a poll once it was refused or went unanswered.
 *
 * ```
 *                   ┌─────────┐
 *  stop (any) ─────▶│ Stopped │
 *                   └────┬────┘
 *                  start │ (while Wi-Fi is off: to RadioOff)
 *                        ▼
 *  ┌──────────┐ Wi-Fi ┌───────────────────────────────────────────────────┐
 *  │ RadioOff │──on──▶│ (target): chosen by the slot served now.          │
 *  └──────────┘       │ Entered again from any running state when the     │
 *       ▲             │ slot, role or band changes (setGroups, serveNow,  │
 *       │ Wi-Fi off   │ rotation); a formed group is left first.          │
 *       │ (any        └─────┬───────────────┬─────────────────┬───────────┘
 *       │  running)         │ no group      │ hosting         │ joining
 *                           ▼               ▼                 ▼
 *                       ┌──────┐      ┌──────────┐       ┌─────────┐
 *                       │ Idle │      │ Creating │◀─┐    │ Joining │◀───────────┐
 *                       └──────┘      └──┬─────┬─┘  │    └──┬────┬─┘            │
 *                                        │     └─(1)┘       │    │ (2)          │
 *                                        │                  │    ▼              │
 *                                        │                  │  ┌──────────┐     │
 *                                        │                  │  │ Checking │─(3)─┤
 *                                        │                  │  └──┬─────┬─┘     │
 *                                        │                  │     │     │ in the group to join
 *                                        │                  │     │     ▼       │
 *                                        │                  │     │  ┌────────────┐
 *                                        │                  │     │  │ Addressing │─(4)┘
 *                                        │                  │     │  └─────┬──────┘
 *                                        │    group formed  │     │        │
 *                                        ▼                  ▼     ▼        ▼
 *                                     ┌──────────────────────────────────────┐
 *                                     │                Formed                │
 *                                     └───────┬─────────────────────┬────────┘
 *                                             │ group lost          │ (5)
 *                                             ▼                     ▼
 *                                         (target)               Joining
 *
 *  (1) no group after CREATE_TIMEOUT_MS: cancel, ask again
 *  (2) overdue (JOIN_STEP_MS for the first SHORT_JOINS attempts, then a step more
 *     each, at most FORM_TIMEOUT_MS), or a group owner
 *      came in sight
 *  (3) in no group or in another: cancel, ask again with attempt + 1
 *  (4) no address after ADDRESS_WAIT_MS: cancel, ask again with attempt + 1
 *  (5) hosting alone while the pair has this phone join: leave the group
 * ```
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
    @Volatile private var state: GroupState = GroupState.Stopped
    private val running get() = state != GroupState.Stopped
    private val formed get() = state is GroupState.Formed
    /** Last radio state reported, so READY / LINK_DOWN go out once per change. */
    @Volatile private var reported: RadioState? = null
    @Volatile private var preferOwner = false
    @Volatile private var acceptLoopStarted = false
    private var startMs = 0L
    private var discoveryOkAtMs = 0L
    private var lastDiscoverAtMs = 0L
    /** Credentials of the group served now; null = no group to serve. */
    @Volatile private var creds: GroupCredentials? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    /** When each socket connect was requested: unanswered ones must expire. */
    private val requestedAt = ConcurrentHashMap<String, Long>()

    /** Host the group: this device owns 192.168.49.1 and peers join it. */
    @Synchronized
    fun setPreferGroupOwner(v: Boolean) {
        preferOwner = v
        if (v && running && state !is GroupState.Formed && state != GroupState.RadioOff) formTarget()
    }

    fun isGroupOwner(): Boolean = preferOwner

    /**
     * Wi-Fi (and with it P2P) is on, as last broadcast: an input remembered also while
     * stopped, so a start knows whether to begin in [GroupState.RadioOff].
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
        val keep = groups.indexOfFirst { GroupCredentials.from(it.secret).networkName == current?.networkName }
        if (keep < 0) return moveTo(0)
        val before = role
        val wanted = credsOf(groups[keep])
        val st = state
        // The group this phone hosts moves to the band its user chose now.
        if (wanted.band != current?.band && st is GroupState.Formed && st.owner) return moveTo(keep)
        creds = wanted
        slot = keep
        applySlotRole()
        val changed = wanted != current || role != before
        when {
            !running || st == GroupState.RadioOff -> Unit
            st is GroupState.Formed -> yieldIdleHost()
            // A new role or band for the group being formed: the pending request was another.
            changed || st == GroupState.Idle -> formTarget()
        }
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
        if (formed) leaveGroup()
        slot = index
        slotSinceMs = System.currentTimeMillis()
        linkedAtMs = 0L
        creds = plan.getOrNull(index)?.let { credsOf(it) }
        applySlotRole()
        if (running && state != GroupState.RadioOff) formTarget()
    }

    /** A host creates on its band; a joiner scans the host's band (any while unknown). */
    private fun credsOf(p: GroupPlan) = GroupCredentials.from(p.secret, p.band)

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

    /** The state for the slot served now (see the class comment). */
    private fun target(attempt: Int = 1): GroupState {
        val now = System.currentTimeMillis()
        return when {
            creds == null -> GroupState.Idle
            preferOwner || role == GroupRole.HOST -> GroupState.Creating(now, askedAt = null)
            else -> GroupState.Joining(now, askedAt = null, attempt = attempt)
        }
    }

    /** The one place the group state changes. */
    @Synchronized
    private fun moveState(to: GroupState) {
        val from = state
        state = to
        if (from::class != to::class) {
            com.icegood.findmeinwood.transport.api.DebugLog.d { "wifi direct: $from -> $to" }
        }
    }

    /** Into (target): Creating and Joining send their request at once. */
    private fun formTarget(attempt: Int = 1) {
        when (val to = target(attempt)) {
            is GroupState.Creating -> askCreate(to)
            is GroupState.Joining -> askJoin(to)
            else -> moveState(to)
        }
    }

    /** Open peer sockets (diagnostics). */
    fun peerLinkCount(): Int = sockets.size

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        synchronized(this) {
            if (running) return events
            startMs = System.currentTimeMillis()
            slotSinceMs = startMs
            reported = null
            try {
                channel = wiring.initialize()
                // No channel = no P2P (missing permission, Wi-Fi off, unsupported):
                // say so instead of pretending to scan.
                if (channel == null) report(RadioState.LINK_DOWN)
                registerReceiver()
                // Running from here on (the accept loop and the callbacks check it).
                moveState(if (p2pOn) GroupState.Idle else GroupState.RadioOff)
                startAcceptLoop()
                discover()
                requestConnectionInfo()
                if (p2pOn) formTarget()
                startPoll()
                emit(RadioState.SCANNING)
            } catch (e: Exception) {
                report(RadioState.LINK_DOWN)
            }
        }
        return events
    }

    override suspend fun stop() {
        moveState(GroupState.Stopped)
        acceptLoopStarted = false
        closeSockets()
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
        if (!running || reported != RadioState.READY) return SendResult.Failed("no p2p group")
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

    /** READY / LINK_DOWN for the app, once per change. */
    private fun report(s: RadioState) {
        if (reported == s) return
        reported = s
        emit(s)
    }

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = wiring.register(
            onWifiState = ::onP2pState,
            onPeers = ::onPeersChanged,
            onConnection = { requestConnectionInfo() },
        )
    }

    /** Group owners in sight at the last look. */
    private var ownersSeen: Set<String> = emptySet()

    /**
     * The phones in sight changed. A group owner that was not there when the join was
     * asked may be the host coming back: the pending join never finds a group that
     * appeared after its scan, so it is asked again now instead of when it is overdue.
     */
    internal fun onPeersChanged() {
        if (state !is GroupState.Joining) return
        wiring.requestOwners(channel) { owners ->
            synchronized(this@WifiDirectTransport) {
                val appeared = owners - ownersSeen
                ownersSeen = owners
                val st = state as? GroupState.Joining ?: return@requestOwners
                val asked = st.askedAt ?: return@requestOwners
                if (appeared.isEmpty() || System.currentTimeMillis() - asked < REJOIN_MIN_MS) return@requestOwners
                com.icegood.findmeinwood.transport.api.DebugLog.d { "wifi direct: a host appeared, joining again" }
                checkGroup()
            }
        }
    }

    /** Wi-Fi (and with it P2P) was switched on or off, in the app or outside it. */
    @Synchronized
    internal fun onP2pState(on: Boolean) {
        if (on == p2pOn) return
        p2pOn = on
        if (!running) return
        if (!on) {
            closeSockets()
            moveState(GroupState.RadioOff)
            report(RadioState.LINK_DOWN)
            return
        }
        // Back on: look for the members' group at once, as at start.
        if (channel == null) channel = wiring.initialize()
        startMs = System.currentTimeMillis()
        lastDiscoverAtMs = 0L
        discoveryOkAtMs = 0L
        reported = null
        emit(RadioState.SCANNING)
        discover()
        requestConnectionInfo()
        formTarget()
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
                P2pResult.FAILED -> if (!formed) report(RadioState.LINK_DOWN)
            }
        }
    }

    /** P2P discovery stops on its own, so keep asking for peers and group state. */
    private fun startPoll() {
        scope.launch {
            while (running) {
                delay(pollMs)
                if (!running) break
                expireConnects()
                if (state == GroupState.RadioOff) continue
                if (!formed) {
                    if (channel == null) channel = wiring.initialize() // self-heal
                    discover()
                }
                synchronized(this@WifiDirectTransport) {
                    tick()
                    rotate()
                }
                requestConnectionInfo()
                // A disabled/ignoring P2P stack never answers: say so instead of
                // advertising a scan that is not happening (FR-3.6a failover).
                if (!formed && (preferOwner || discoveryOkAtMs == 0L) &&
                    System.currentTimeMillis() - startMs > 3 * pollMs
                ) {
                    report(RadioState.LINK_DOWN)
                }
            }
        }
    }

    /**
     * How long join number [attempt] in a row may take: one step for the first
     * [SHORT_JOINS] (a host that is back is joined in 3 s, one still away is never found),
     * then a step more each time, up to the limit (far away a join takes longer).
     */
    private fun joinAllowance(attempt: Int): Long =
        minOf(maxOf(1, attempt - SHORT_JOINS + 1) * JOIN_STEP_MS, FORM_TIMEOUT_MS)

    /** The time-driven transitions, once a poll. */
    private fun tick() {
        val now = System.currentTimeMillis()
        when (val st = state) {
            is GroupState.Creating -> when {
                // Seen for 30 s right after Wi-Fi came back: the P2P service sits on the
                // request and refuses every new one until it is cancelled.
                now - st.since > CREATE_TIMEOUT_MS -> { wiring.cancelConnect(channel); formTarget() }
                st.askedAt == null || now - st.askedAt > PENDING_TIMEOUT_MS -> askCreate(st)
            }
            is GroupState.Joining -> when {
                now - st.since > joinAllowance(st.attempt) -> checkGroup()
                st.askedAt == null || now - st.askedAt > PENDING_TIMEOUT_MS -> askJoin(st)
            }
            is GroupState.Addressing ->
                if (now - st.since > ADDRESS_WAIT_MS) { wiring.cancelConnect(channel); formTarget(st.attempt + 1) }
            is GroupState.Formed -> yieldIdleHost()
            else -> Unit
        }
    }

    /**
     * A join overdue: a joiner already in the group only waits for its address (DHCP, slow
     * when its radio also serves a router on another channel) and must not be cancelled.
     */
    private fun checkGroup() {
        val st = state as? GroupState.Joining ?: return
        moveState(GroupState.Checking(st.since, st.attempt))
        wiring.requestGroupName(channel) { name ->
            synchronized(this@WifiDirectTransport) {
                val checking = state as? GroupState.Checking ?: return@requestGroupName
                if (name != null && name == creds?.networkName) {
                    moveState(GroupState.Addressing(System.currentTimeMillis(), checking.attempt))
                } else {
                    wiring.cancelConnect(channel)
                    formTarget(checking.attempt + 1)
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

    private fun askJoin(st: GroupState.Joining) {
        val c = creds ?: return
        moveState(st.copy(askedAt = System.currentTimeMillis()))
        wiring.join(channel, c) { result ->
            synchronized(this@WifiDirectTransport) {
                // Refused: asked again at the next poll.
                val now = state
                if (result != P2pResult.OK && now is GroupState.Joining) moveState(now.copy(askedAt = null))
            }
            requestConnectionInfo()
        }
    }

    private fun askCreate(st: GroupState.Creating) {
        val c = creds ?: return
        moveState(st.copy(askedAt = System.currentTimeMillis()))
        wiring.createGroup(channel, c) {
            synchronized(this@WifiDirectTransport) {
                // Answered either way: the next poll asks again unless the group formed.
                val now = state
                if (now is GroupState.Creating) moveState(now.copy(askedAt = null))
            }
            requestConnectionInfo()
        }
    }

    /**
     * This phone hosts a group nobody linked to although its pair has it join (left over
     * from an earlier role): it gives the group up at once and joins. The host never does.
     */
    private fun yieldIdleHost() {
        val st = state as? GroupState.Formed ?: return
        if (preferOwner || role != GroupRole.JOIN || !st.owner || sockets.isNotEmpty()) return
        leaveGroup()
        formTarget()
    }

    /** Leaves the formed group: the radio removes it and its sockets close. */
    private fun leaveGroup() {
        wiring.removeGroup(channel)
        closeSockets()
        report(RadioState.LINK_DOWN)
    }

    private fun closeSockets() {
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun requestConnectionInfo() {
        wiring.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
    }

    @Synchronized
    private fun onConnectionInfo(info: GroupInfo) {
        val st = state
        if (st == GroupState.Stopped || st == GroupState.RadioOff) return
        if (!info.formed) {
            if (st is GroupState.Formed) {
                closeSockets()
                report(RadioState.LINK_DOWN)
                formTarget()
            } else if (reported == RadioState.READY && sockets.isEmpty()) {
                report(RadioState.LINK_DOWN)
            }
            return
        }
        if (st == GroupState.Idle) return // a group left over: nothing to serve in it
        moveState(GroupState.Formed(info.isOwner))
        startAcceptLoop()
        report(RadioState.READY)
        if (!info.isOwner) {
            val host = info.ownerHost ?: return
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

    /**
     * A socket connect the framework never answers (peer needs to accept, radio busy, OEM
     * quirk) must not block that peer forever: drop it so the next poll retries.
     */
    private fun expireConnects() {
        val now = System.currentTimeMillis()
        requestedAt.entries.removeIf { (key, at) ->
            val dead = now - at > PENDING_TIMEOUT_MS
            if (dead) connecting.remove(key)
            dead
        }
    }

    private fun registerSocket(s: Socket) {
        val key = s.remoteSocketAddress.toString()
        sockets[key] = s
        if (linkedAtMs == 0L) linkedAtMs = System.currentTimeMillis()
        connecting.remove(key)
        report(RadioState.READY)
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
        // a stuck create aborted after 3 s and a join after 15 s, a dead socket noticed
        // after 15 s.
        internal const val PENDING_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 2_000L
        /** Every socket carries an empty frame this often, so the other side sees it alive. */
        internal var HEARTBEAT_MS = 5_000L
        /** A socket silent this long is dead (the peer left the group without closing it). */
        internal var SILENT_SOCKET_MS = 15_000L
        /**
         * A join that formed no group by then is aborted and asked again: a join started
         * before the host was back keeps blacklisting it with growing back-off; a fresh
         * request starts clean. Not shorter: far from the host a scan and an association
         * take 6 s and more, and a join aborted at 5 s never got through beyond ~30 m.
         */
        internal var FORM_TIMEOUT_MS = 15_000L
        /**
         * The first [SHORT_JOINS] joins in a row are given this long, each later one as
         * much more (5, 5, 5, 10, 15 s): a join asked while the host was still away never
         * finds its group, and a near host is joined in 3 s, so an early try must not hold
         * up the next.
         */
        internal var JOIN_STEP_MS = 5_000L
        internal const val SHORT_JOINS = 3
        /**
         * A joiner in its group but without an address yet waits this long before the join
         * is asked again. DHCP either answers in 1-2 s or stalls for good (offer heard, no
         * ack, the radio shared with a router on another channel); a fresh join then got an
         * address within 2 s, so waiting longer only delays that.
         */
        internal var ADDRESS_WAIT_MS = 8_000L
        /** A join asked this recently has already seen an owner that shows up now. */
        internal var REJOIN_MIN_MS = 2_000L
        /** A create forms its group in well under a second; one still pending is stuck. */
        internal var CREATE_TIMEOUT_MS = 3_000L
        /** Several groups: a slot ends this long after a member linked in it... */
        internal var LINKED_DWELL_MS = 5_000L
        /** ...or after this long when nobody did (time for a join: scan, associate, connect). */
        internal var SLOT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
