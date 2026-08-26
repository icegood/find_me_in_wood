package app.findmeinwood.core.session

import app.findmeinwood.core.crypto.Aead
import app.findmeinwood.core.crypto.AesGcmAead
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.crypto.ReplayWindow
import app.findmeinwood.core.model.BeaconPayload
import app.findmeinwood.core.model.DecryptedBeacon
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.PayloadCodec
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Wires profile -> transports -> scheduler -> tracker -> edge manager
 * (T2.2/T6.2). Receiving happens on all transports; sending broadcasts on all
 * active transports (v1 degenerate mesh). Relay: valid unseen frames with ttl>1
 * are re-sent on all other transports (FR-3.3).
 */
class SessionManager(
    private val profile: NetworkProfile,
    private val transports: List<Transport>,
    private val fixes: Flow<GnssFix>,
    private val clock: () -> Long,
    private val aead: Aead = AesGcmAead,
    private val relayEnabled: Boolean = true,
    private val edgeManager: EdgeManager? = null,
    private val scope: CoroutineScope,
    private val keepAliveMs: Long = KEEP_ALIVE_MS,
) {
    companion object {
        const val INITIAL_TTL: UByte = 3u
        const val KEEP_ALIVE_MS = 60_000L
    }

    private val tracker = PeerTracker(keepAliveMs = KEEP_ALIVE_MS, nowMs = clock)
    private val replay = ReplayWindow(clock)
    private val seq = AtomicLong(0)
    private val jobs = mutableListOf<Job>()
    private val running = MutableStateFlow(false)
    val runningFlow: StateFlow<Boolean> = running
    val peerFlow: StateFlow<Map<MemberId, PeerState>> get() = tracker.peersFlow

    /** Wire bytes of every beacon sent (used by carriers, e.g. Viber share — US-9). */
    var onFrameSent: ((ByteArray) -> Unit)? = null

    /** Called when an edge goes DOWN; candidates per FR-3.6a for auto-failover. */
    var onEdgeDown: ((MemberId, List<TransportId>) -> Unit)? = null

    private val diag = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = diag

    private fun bump(transform: (Diagnostics) -> Diagnostics) {
        diag.value = transform(diag.value)
    }

    fun edges(): List<EdgeView> = edgeManager?.all()?.map {
        EdgeView(it.peerId, it.transport, it.state, it.lastSeenMs)
    } ?: emptyList()

    /** Human-agreed per-edge transport change (FR-3.6). */
    fun setEdgeTransport(peerId: MemberId, transport: TransportId) {
        edgeManager?.setTransport(peerId, transport)
    }

    fun start() {
        if (running.value) return
        running.value = true
        val flows = transports.map { t ->
            t to t.start(SessionConfig(profile.networkId, transports.map { it.id }.toSet()))
        }
        flows.forEach { (t, flow) ->
            jobs += scope.launch {
                flow.collect { ev -> if (ev is TransportEvent.FrameReceived) handleFrame(ev) }
            }
        }
        jobs += scope.launch {
            BeaconScheduler(keepAliveMs, clock)
                .commands(fixes)
                .collect { sendBeacon(it.fix) }
        }
        jobs += scope.launch {
            while (true) {
                delay(keepAliveMs)
                tracker.tick()
                edgeManager?.tick()?.forEach { peer ->
                    val candidates = edgeManager.failoverOrder(peer, transports.map { it.id }.toSet())
                    onEdgeDown?.invoke(peer, candidates)
                }
            }
        }
    }

    suspend fun stop() {
        running.value = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        transports.forEach { it.stop() }
    }

    suspend fun sendBeacon(fix: GnssFix?) {
        val payload: BeaconPayload = fix?.let { PayloadCodec.fromFix(it) } ?: BeaconPayload()
        sendRaw(PayloadCodec.encode(payload))
    }

    suspend fun sendRaw(payloadBytes: ByteArray) {
        val s = (seq.getAndIncrement()).toUInt()
        val sentAt = clock()
        val aad = FrameCodec.aad(profile.networkId, profile.myMemberId, s, sentAt)
        val ct = aead.seal(profile.trafficKey, payloadBytes, aad)
        val frame = EncryptedFrame(
            profile.networkId, profile.myMemberId, s, INITIAL_TTL, sentAt, ct,
        )
        val wire = FrameCodec.encode(frame)
        transports.forEach { it.send(wire) }
        bump { it.copy(sent = it.sent + 1) }
        onFrameSent?.invoke(wire)
    }

    suspend fun acceptExternalWire(wire: ByteArray, source: TransportId) {
        val frame = try {
            FrameCodec.decode(wire)
        } catch (_: Exception) {
            return
        }
        handleFrame(TransportEvent.FrameReceived(frame, source))
    }

    private suspend fun handleFrame(ev: TransportEvent.FrameReceived) {
        val f = ev.frame
        if (!f.networkId.bytes.contentEquals(profile.networkId.bytes)) {
            bump { it.copy(dropped = it.dropped + 1) }
            return
        }
        if (f.senderId.bytes.contentEquals(profile.myMemberId.bytes)) return
        if (!replay.accept(f.senderId.bytes, f.seq, f.sentAtMs)) {
            bump { it.copy(dropped = it.dropped + 1) }
            return
        }
        val plain = try {
            aead.open(profile.trafficKey, f.ciphertext, FrameCodec.aad(f.networkId, f.senderId, f.seq, f.sentAtMs))
        } catch (_: Exception) {
            bump { it.copy(authFailed = it.authFailed + 1) }
            return // auth fail: drop silently (FR-8.6 / T5.5)
        }
        val payload = try {
            PayloadCodec.decode(plain)
        } catch (_: Exception) {
            bump { it.copy(dropped = it.dropped + 1) }
            return
        }
        bump { it.copy(received = it.received + 1) }
        tracker.onBeacon(DecryptedBeacon(f.senderId, f.seq, f.sentAtMs, payload))
        edgeManager?.onFrameFrom(f.senderId, ev.source)
        if (relayEnabled && f.ttl > 1u) {
            val relayed = f.copy(ttl = (f.ttl - 1u).toUByte())
            val wire = FrameCodec.encode(relayed)
            transports.filter { it.id != ev.source }.forEach { it.send(wire) }
            bump { it.copy(relayed = it.relayed + 1) }
        }
    }
}
