package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.Aead
import com.icegood.findmeinwood.core.crypto.AesGcmAead
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.IdentityVerifier
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.crypto.ReplayWindow
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.DecryptedBeacon
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.TransportEvent
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
    /** LNP/3 link negotiation (FR-3.5–3.10); null = broadcast only, no negotiation. */
    val links: LinkNegotiator? = null,
    private val scope: CoroutineScope,
    private val keepAliveMs: Long = KEEP_ALIVE_MS,
    /** Device identity used to sign beacons (NFR-3); null disables origin auth. */
    private val signer: java.security.KeyPair? = null,
    /** False while only listening for networks to join: radios run, no beacons go out. */
    private val beaconsEnabled: Boolean = true,
) {
    companion object {
        const val INITIAL_TTL: UByte = 3u
        const val KEEP_ALIVE_MS = 60_000L
        /** Minimum gap between extra (link-control) beacons. */
        const val REPLY_GAP_MS = 1_000L
    }

    private val verifier = IdentityVerifier()
    private val tracker = PeerTracker(keepAliveMs = KEEP_ALIVE_MS, nowMs = clock)
    private val replay = ReplayWindow(clock)
    // Seeded from wall-clock seconds so a restarted sender keeps increasing seqs: peers
    // (replay window, peer tracker) drop anything at or below the last seq they saw.
    private val seq = AtomicLong(clock() / 1000)
    private val jobs = mutableListOf<Job>()
    private val running = MutableStateFlow(false)
    val runningFlow: StateFlow<Boolean> = running
    val peerFlow: StateFlow<Map<MemberId, PeerState>> get() = tracker.peersFlow

    /**
     * Next sequence number from the same clock-seeded counter as radio frames. SMS position
     * reports use it too, so (sender, seq) — their AEAD nonce — is never reused.
     */
    fun nextSeq(): UInt = seq.getAndIncrement().toUInt()

    /** Wire bytes of every beacon sent (used by carriers, e.g. Viber share — US-9). */
    var onFrameSent: ((ByteArray) -> Unit)? = null

    /** Decrypted payload of every authenticated incoming frame, with sender (chat etc.). */
    var onFrameReceived: ((ByteArray, MemberId) -> Unit)? = null

    /** The selected link to a peer changed (LNP/3 FR-3.8). */
    var onLinkSwitch: ((LinkNegotiator.Switch) -> Unit)? = null

    /** US-8: cleartext management frames (HELLO / join handshake) as raw wire bytes. */
    var onMgmtFrame: ((ByteArray, TransportId) -> Unit)? = null

    private val diag = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = diag

    private fun bump(transform: (Diagnostics) -> Diagnostics) {
        diag.value = transform(diag.value)
    }

    fun edges(): List<LinkNegotiator.LinkView> = links?.all() ?: emptyList()

    /** Monotonic (not [clock]): spacing of extra beacons is about real radio load. */
    @Volatile private var lastReplyAtMs = 0L
    @Volatile private var lastPayload: BeaconPayload = BeaconPayload()

    private fun announce(switches: List<LinkNegotiator.Switch>) {
        switches.forEach { onLinkSwitch?.invoke(it) }
        // Agreed set, selection and suggestion feed the member list.
        links?.all()?.forEach { v ->
            tracker.setLinkInfo(
                v.peer, v.link, if (v.agreed) v.common else emptyList(), v.suggestion,
                common = v.common, confirmedByMe = v.confirmedByMe, confirmedByPeer = v.confirmedByPeer,
            )
        }
        // Answer handshake steps / announce selections at once instead of with the next
        // scheduled beacon (at most one extra beacon per 2 s).
        if (links?.replyNow() == true) replyNow(force = false)
    }

    @Volatile private var replyPending = false

    /**
     * Sends a beacon now (link control due). At most one extra beacon per second: a reply
     * inside that second is deferred to its end, never dropped — a dropped reply would
     * leave the handshake waiting for the next scheduled beacon.
     */
    private fun replyNow(force: Boolean) {
        if (!running.value) return
        val wait = if (force) 0L else (REPLY_GAP_MS - (System.nanoTime() / 1_000_000 - lastReplyAtMs)).coerceAtLeast(0)
        if (wait > 0) {
            if (replyPending) return
            replyPending = true
        }
        scope.launch {
            if (wait > 0) delay(wait)
            replyPending = false
            lastReplyAtMs = System.nanoTime() / 1_000_000
            runCatching { sendBeaconSigned(lastPayload) }
        }
    }

    /** This phone's user confirms the transports common with [peer] (LNP/3 handshake). */
    fun confirmLinks(peer: MemberId) {
        links?.let { announce(it.confirm(peer)) }
    }

    /** User picked [t] for the link with [peer] (LNP/3: within the agreed set, no handshake). */
    fun selectLink(peer: MemberId, t: TransportId) {
        links?.let { announce(it.select(peer, t)) }
    }

    /**
     * A position report that arrived by SMS (FR-3.9): fed to the tracker like a beacon and
     * counted as hearing the peer on the SMS rung. SMS carries only an 8-byte sender
     * prefix, so it must belong to a member already met; the frame was authenticated with
     * the network key by the caller. Returns the member, or null when unknown.
     */
    fun acceptSmsReport(
        senderPrefix: ByteArray, seq: UInt, sentAtMs: Long,
        position: com.icegood.findmeinwood.core.model.BeaconPosition?, batteryPct: Int?,
    ): MemberId? {
        val member = (tracker.peersFlow.value.keys + (links?.all()?.map { it.peer } ?: emptyList()))
            .firstOrNull { it.bytes.copyOf(8).contentEquals(senderPrefix) } ?: return null
        tracker.onBeacon(
            DecryptedBeacon(member, seq, sentAtMs, BeaconPayload(position = position, batteryPct = batteryPct)),
            TransportId.SMS,
        )
        links?.let { announce(it.onFrame(member, TransportId.SMS, direct = true, ctl = null)) }
        bump { it.copy(received = it.received + 1) }
        return member
    }

    /** Sends an already-encoded management frame on every transport (no AEAD). */
    suspend fun sendMgmt(wire: ByteArray) {
        live().forEach { t -> runCatching { t.send(wire) } }
    }

    fun transportIds(): List<TransportId> = transports.map { it.id }

    /**
     * Test hook (debug builds, field tests): radios that send and receive nothing, as if
     * the peers were out of their range, while the radio stays on and offered.
     */
    @Volatile var muted: Set<TransportId> = emptySet()

    private fun live() = transports.filter { it.id !in muted }

    /** Last known radio state per transport (drives the status line in the UI). */
    private val _radioStates = MutableStateFlow<Map<TransportId, RadioState>>(emptyMap())
    val radioStates: StateFlow<Map<TransportId, RadioState>> = _radioStates

    fun start() {
        if (running.value) return
        running.value = true
        val flows = transports.map { t ->
            t to t.start(SessionConfig(profile.networkId, transports.map { it.id }.toSet()))
        }
        flows.forEach { (t, flow) ->
            jobs += scope.launch {
                flow.collect { ev ->
                    when (ev) {
                        is TransportEvent.FrameReceived -> if (ev.source !in muted) handleFrame(ev)
                        is TransportEvent.MgmtFrameReceived -> onMgmtFrame?.invoke(ev.wire, ev.source)
                        is TransportEvent.PeerRadioVisible -> Unit
                        // A new radio link: introduce ourselves now (offers included), so
                        // the handshake starts at once rather than with the next beacon.
                        is TransportEvent.LinkUp -> links?.let { it.announceNow(); replyNow(force = true) }
                        is TransportEvent.StateChanged ->
                            _radioStates.value = _radioStates.value + (t.id to ev.state)
                    }
                }
            }
        }
        if (beaconsEnabled) jobs += scope.launch {
            BeaconScheduler(keepAliveMs, clock)
                .commands(fixes)
                .collect {
                    val payload = it.fix?.let { f -> PayloadCodec.fromFix(f) } ?: BeaconPayload()
                    lastPayload = payload
                    sendBeaconSigned(payload)
                }
        }
        jobs += scope.launch {
            while (true) {
                delay(keepAliveMs)
                tracker.tick()
                links?.let { announce(it.tick()) }
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

    /**
     * NFR-3: sends a beacon signed by the device identity. The signed bytes bind the
     * sequence number and timestamp, so a captured beacon cannot be replayed or re-attributed.
     */
    suspend fun sendBeaconSigned(beacon: BeaconPayload) {
        // Link control is part of the signed payload, so offers cannot be forged by relays.
        val payload = beacon.copy(link = links?.outgoing())
        val s = (seq.getAndIncrement()).toUInt()
        val sentAt = clock()
        val signed = signer?.let { Identity.sign(it, signedBeaconBytes(s, sentAt, payload)) }
            ?.takeIf { it.isNotEmpty() }
        val body = if (signed != null) {
            payload.copy(sig = signed, pub = signer!!.public.encoded)
        } else {
            payload
        }
        val ct = aead.seal(profile.trafficKey, PayloadCodec.encode(body), FrameCodec.aad(profile.networkId, profile.myMemberId, s, sentAt))
        val wire = FrameCodec.encode(
            EncryptedFrame(profile.networkId, profile.myMemberId, s, INITIAL_TTL, sentAt, ct),
        )
        live().forEach { it.send(wire) }
        bump { it.copy(sent = it.sent + 1) }
        onFrameSent?.invoke(wire)
    }

    /** Canonical bytes an identity signature covers: seq + sentAt + payload (sig/pub removed). */
    fun signedBeaconBytes(seqNo: UInt, sentAtMs: Long, payload: BeaconPayload): ByteArray {
        val canonical = PayloadCodec.encode(payload.copy(sig = null, pub = null))
        val out = ByteArray(4 + 8 + canonical.size)
        FrameCodec.writeU32(out, 0, seqNo.toInt())
        FrameCodec.writeU64(out, 4, sentAtMs)
        System.arraycopy(canonical, 0, out, 12, canonical.size)
        return out
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
        live().forEach { it.send(wire) }
        bump { it.copy(sent = it.sent + 1) }
        onFrameSent?.invoke(wire)
    }

    suspend fun acceptExternalWire(wire: ByteArray, source: TransportId) {
        if (MgmtCodec.isMgmt(wire)) {
            onMgmtFrame?.invoke(wire, source)
            return
        }
        val frame = try {
            FrameCodec.decode(wire)
        } catch (_: Exception) {
            // FR-9.3: malformed/garbage envelopes are dropped and counted, never silent.
            bump { it.copy(dropped = it.dropped + 1) }
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
        val plain = try {
            aead.open(profile.trafficKey, f.ciphertext, FrameCodec.aad(f.networkId, f.senderId, f.seq, f.sentAtMs))
        } catch (_: Exception) {
            bump { it.copy(authFailed = it.authFailed + 1) }
            return // auth fail: drop silently (FR-8.6 / T5.5)
        }
        // Replay check only after authentication: the window records the seq, so checking
        // first would let a forged frame with a huge seq lock the real sender out.
        if (!replay.accept(f.senderId.bytes, f.seq, f.sentAtMs)) {
            bump { it.copy(dropped = it.dropped + 1) }
            // The same frame over a second radio is a duplicate, but it still proves that
            // radio reaches the peer — without this the slower radio never counts as heard.
            if (f.ttl == INITIAL_TTL) links?.let { announce(it.onFrame(f.senderId, ev.source, direct = true, ctl = null)) }
            return
        }
        // Chat messages are not beacons: route them to the app instead of dropping
        // them as an "undecodable beacon". They still take part in relaying below.
        // Relayed frames have a decremented TTL: they do not prove a direct link (FR-3.10).
        val direct = f.ttl == INITIAL_TTL
        if (PayloadCodec.tryDecodeChat(plain) == null) {
            val payload = try {
                PayloadCodec.decode(plain)
            } catch (_: Exception) {
                bump { it.copy(dropped = it.dropped + 1) }
                return
            }
            if (!verifyOrigin(f, payload)) {
                bump { it.copy(authFailed = it.authFailed + 1) }
                return
            }
            tracker.onBeacon(DecryptedBeacon(f.senderId, f.seq, f.sentAtMs, payload), ev.source.takeIf { direct })
            links?.let { announce(it.onFrame(f.senderId, ev.source, direct, payload.link)) }
            payload.link?.name?.let { tracker.setNickname(f.senderId, it.take(32)) }
        } else {
            links?.let { announce(it.onFrame(f.senderId, ev.source, direct, null)) }
        }
        bump { it.copy(received = it.received + 1) }
        onFrameReceived?.invoke(plain, f.senderId)
        if (relayEnabled && f.ttl > 1u) {
            val relayed = f.copy(ttl = (f.ttl - 1u).toUByte())
            val wire = FrameCodec.encode(relayed)
            live().filter { it.id != ev.source }.forEach { it.send(wire) }
            bump { it.copy(relayed = it.relayed + 1) }
        }
    }

    /**
     * NFR-3: origin authentication — pin the sender key once, then verify every frame.
     * Once a key is pinned, unsigned beacons from that member are refused, otherwise
     * stripping the signature would bypass the check.
     */
    private fun verifyOrigin(f: EncryptedFrame, payload: BeaconPayload): Boolean {
        val sig = payload.sig ?: return verifier.keyFor(f.senderId) == null
        val pub = payload.pub
        if (pub != null && verifier.keyFor(f.senderId) == null && !verifier.pin(f.senderId, pub)) return false
        return verifier.verify(f.senderId, signedBeaconBytes(f.seq, f.sentAtMs, payload), sig)
    }
}
