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
import com.icegood.findmeinwood.core.model.Rekey
import com.icegood.findmeinwood.core.model.RekeyCodec
import com.icegood.findmeinwood.core.model.SaltWrap
import com.icegood.findmeinwood.core.crypto.EpochKeys
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.GroupRole
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.TransportEvent
import com.icegood.findmeinwood.transport.api.SendResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wires profile -> transports -> scheduler -> tracker -> edge manager
 * (T2.2/T6.2). Receiving happens on all transports; sending broadcasts on the
 * radios agreed with peers (LNP/3, [LinkNegotiator.radios]). Relay: valid unseen
 * frames with ttl>1 are re-sent on the other agreed radios (FR-3.3).
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
    /** The network's member list and member cards (null: no list kept, e.g. listening). */
    val book: MemberBook? = null,
    /** Refuse unsigned beacons (the app always signs; some tests run without keys). */
    private val requireSignatures: Boolean = false,
    /** The network's keys per epoch: frames go out under the newest (re-salted on removals). */
    val keys: KeyRing = KeyRing(profile.trafficKey),
    /** This phone's box key pair: new network keys are sealed to its public half. */
    private val box: java.security.KeyPair? = null,
    /** The newest rekey known, handed to members still on an older key. */
    latestRekey: Rekey? = null,
    /** Minimum gap between rekeys handed to one member on an older key. */
    private val catchUpGapMs: Long = CATCH_UP_GAP_MS,
) {
    companion object {
        const val INITIAL_TTL: UByte = 3u
        const val KEEP_ALIVE_MS = 60_000L
        /** Minimum gap between extra (link-control) beacons. */
        const val REPLY_GAP_MS = 1_000L
        /** Minimum gap between member-list syncs sent by this phone. */
        const val SYNC_GAP_MS = 20_000L
        /** Minimum gap between rekeys handed to one member still on an older key. */
        const val CATCH_UP_GAP_MS = 5_000L
        val SYNC_RADIOS = setOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT)
    }

    private val verifier = IdentityVerifier()
    // Staleness follows this network's beacon interval (3 missed keep-alives, FR-6.3).
    private val tracker = PeerTracker(keepAliveMs = keepAliveMs, nowMs = clock)
    private val replay = ReplayWindow(clock)
    // Seeded from wall-clock seconds so a restarted sender keeps increasing seqs: peers
    // (replay window, peer tracker) drop anything at or below the last seq they saw.
    private val seq = AtomicLong(clock() / 1000)
    private val jobs = mutableListOf<Job>()

    /**
     * Seq numbering through hand-off to the radios, one frame at a time: two frames sent at
     * once (a scheduled beacon and a handshake reply) could otherwise arrive out of order,
     * and the peer's replay window drops the older one with its link control.
     */
    private val sendLock = Mutex()
    private val running = MutableStateFlow(false)
    val runningFlow: StateFlow<Boolean> = running
    val peerFlow: StateFlow<Map<MemberId, PeerState>> get() = tracker.peersFlow

    /**
     * Next sequence number from the same clock-seeded counter as radio frames. SMS position
     * reports use it too, so (sender, seq) — their AEAD nonce — is never reused.
     */
    fun nextSeq(): UInt = seq.getAndIncrement().toUInt()

    /**
     * Wire bytes of every *beacon* sent (the position "Send via messenger" shares, US-9);
     * chat and member-list frames never show up here.
     */
    var onFrameSent: ((ByteArray) -> Unit)? = null

    /** Decrypted payload of every authenticated incoming frame, with sender (chat etc.). */
    var onFrameReceived: ((ByteArray, MemberId) -> Unit)? = null

    /** The member list or a member card changed here (to persist it and refresh the UI). */
    var onBookChanged: (() -> Unit)? = null
    @Volatile private var lastSyncAt = Long.MIN_VALUE / 2

    /** FR-4.4: beacons go out without my position while true. */
    @Volatile var sharingPaused: Boolean = false

    /** Takes members the master removed off the peer table (map, member list) at once. */
    fun forgetRemoved() {
        val b = book ?: return
        tracker.forget { b.isRemoved(it) }
    }

    /** Sends this phone's member list and cards (rate-limited unless [force]). */
    suspend fun syncBook(force: Boolean = false) {
        val b = book ?: return
        val now = clock()
        if (!force && now - lastSyncAt < SYNC_GAP_MS) return
        lastSyncAt = now
        // KBs of signed list + cards: only radios with the bandwidth (never LoRa, SMS or a
        // messenger hand-off).
        sendRaw(com.icegood.findmeinwood.core.model.RosterCodec.encode(b.toSync()), only = SYNC_RADIOS)
    }

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

    /** The newest rekey known (current epoch); persisted by the app via [onEpoch]. */
    @Volatile var latestRekey: Rekey? = latestRekey
        private set

    /** The network key moved to a new epoch, or a newer rekey arrived (to persist both). */
    var onEpoch: (() -> Unit)? = null

    /** Monotonic: when each member on an older key was last handed the rekey. */
    private val catchUpAt = HashMap<MemberId, Long>()

    /** Monotonic (not [clock]): spacing of extra beacons is about real radio load. */
    @Volatile private var lastReplyAtMs = 0L
    @Volatile private var lastPayload: BeaconPayload = BeaconPayload()

    private fun announce(switches: List<LinkNegotiator.Switch>) {
        switches.forEach { onLinkSwitch?.invoke(it) }
        applyGroupRole()
        // Agreed set, selection and suggestion feed the member list.
        val host = links?.wifiDirectHost()?.let { WifiDirectHost(it.takeIf { m -> m != profile.myMemberId }) }
        links?.all()?.forEach { v ->
            tracker.setLinkInfo(
                v.peer, v.link, v.agreed, v.suggestion,
                common = v.common, allowedByMe = v.allowedByMe, deniedByMe = v.deniedByMe, allowedByPeer = v.allowedByPeer,
                wifiDirectHost = host,
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

    /** This phone's user allows every transport common with [peer] (LNP/3 handshake). */
    fun confirmLinks(peer: MemberId) {
        links?.let { announce(it.confirm(peer)) }
    }

    /** This phone's user allows or turns down [t] for the link with [peer] (LNP/3 handshake). */
    fun allowLink(peer: MemberId, t: TransportId, allow: Boolean) {
        links?.let { announce(it.allow(peer, t, allow)) }
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
        // Members met, plus everyone on the member list (texted without ever meeting).
        val known = tracker.peersFlow.value.keys + (links?.all()?.map { it.peer } ?: emptyList()) +
            (book?.members() ?: emptyList())
        val member = known.firstOrNull { it.bytes.copyOf(8).contentEquals(senderPrefix) } ?: return null
        if (book?.isRemoved(member) == true) return null
        tracker.onBeacon(
            DecryptedBeacon(member, seq, sentAtMs, BeaconPayload(position = position, batteryPct = batteryPct)),
            TransportId.SMS,
        )
        links?.let { announce(it.onFrame(member, TransportId.SMS, direct = true, ctl = null)) }
        bump { it.copy(received = it.received + 1) }
        return member
    }

    /** A findable network's advertised info, the radio peer advertising it, and the radio. */
    var onNetworkAdvert: ((ByteArray, String, TransportId) -> Unit)? = null

    /** Shows (info) or hides (null) this network to new people on every radio. */
    fun setFindable(info: ByteArray?) = transports.forEach { it.setFindable(info) }

    /** Opens a link to a radio peer seen advertising a network, to ask to join. */
    fun connect(transport: TransportId, radioPeerId: String) =
        transports.firstOrNull { it.id == transport }?.connect(radioPeerId)

    /**
     * Sends an already-encoded management frame on every transport (no AEAD); true when
     * at least one radio actually sent it.
     */
    suspend fun sendMgmt(wire: ByteArray): Boolean {
        var sent = false
        live().forEach { t ->
            val r = runCatching { t.send(wire) }
            if (r.getOrNull() == SendResult.Sent) sent = true
            com.icegood.findmeinwood.transport.api.DebugLog.d {
                "tx mgmt ${wire.size}B type=${wire.getOrNull(3)} via ${t.id}: ${r.getOrNull() ?: r.exceptionOrNull()}"
            }
        }
        return sent
    }

    fun transportIds(): List<TransportId> = transports.map { it.id }

    /**
     * Test hook (debug builds, field tests): radios that send and receive nothing, as if
     * the peers were out of their range, while the radio stays on and offered.
     */
    @Volatile var muted: Set<TransportId> = emptySet()

    private fun live() = transports.filter { it.id !in muted }

    /** Radios member frames go out on: the live ones agreed with peers (all before any). */
    private fun routed(): List<Transport> {
        val agreed = links?.radios() ?: return live()
        return live().filter { it.id !in LinkNegotiator.RUNGS || it.id in agreed }
    }

    @Volatile private var groupRole: GroupRole? = null

    /** Hands the agreed Wi-Fi Direct role to the radios when it changes. */
    private fun applyGroupRole() {
        val role = links?.wifiDirectRole()
        if (role == groupRole) return
        groupRole = role
        com.icegood.findmeinwood.transport.api.DebugLog.d { "wifi direct role: ${role ?: "undecided"}" }
        transports.forEach { it.setGroupRole(role) }
    }

    /** Last known radio state per transport (drives the status line in the UI). */
    private val _radioStates = MutableStateFlow<Map<TransportId, RadioState>>(emptyMap())
    val radioStates: StateFlow<Map<TransportId, RadioState>> = _radioStates

    fun start() {
        if (running.value) return
        running.value = true
        val flows = transports.map { t ->
            t to t.start(
                SessionConfig(
                    profile.networkId, transports.map { it.id }.toSet(),
                    // Listening holds a throwaway key: nothing to share with anyone.
                    groupSecret = if (!beaconsEnabled) null else groupSecretOf(keys.current()),
                    listening = !beaconsEnabled,
                ),
            )
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
                        is TransportEvent.NetworkAdvertSeen -> onNetworkAdvert?.invoke(ev.info, ev.radioPeerId, ev.transport)
                        is TransportEvent.StateChanged ->
                            _radioStates.value = _radioStates.value + (t.id to ev.state)
                    }
                }
            }
        }
        if (beaconsEnabled) pushGroupSecrets()
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
                housekeeping()
            }
        }
    }

    /** The radios' members-only secret (Bluetooth members code, Wi-Fi Direct group) of a key. */
    private fun groupSecretOf(key: ByteArray) =
        com.icegood.findmeinwood.core.crypto.Hkdf.derive(key, ByteArray(32), "fmiw-wifi-direct-group".toByteArray(), 32)

    /** Radios use the newest key; older ones only to find members that missed a rekey. */
    private fun pushGroupSecrets() {
        val all = keys.newestFirst().map { groupSecretOf(it.second) }
        transports.forEach { it.setGroupSecrets(all.first(), all.drop(1)) }
    }

    /** Once per beacon interval: stale/purged peers, quiet-link suggestions. */
    internal fun housekeeping() {
        tracker.tick()
        links?.let { announce(it.tick()) }
    }

    suspend fun stop() {
        running.value = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        transports.forEach { it.stop() }
    }

    suspend fun sendBeacon(fix: GnssFix?) {
        val payload: BeaconPayload = fix?.let { PayloadCodec.fromFix(it) } ?: BeaconPayload()
        sendRaw(PayloadCodec.encode(payload), beacon = true)
    }

    /**
     * NFR-3: sends a beacon signed by the device identity. The signed bytes bind the
     * sequence number and timestamp, so a captured beacon cannot be replayed or re-attributed.
     */
    suspend fun sendBeaconSigned(beacon: BeaconPayload): Unit = sendLock.withLock {
        // Link control is part of the signed payload, so offers cannot be forged by relays.
        // Paused sharing: still a beacon (reachable, links work), just without my position.
        val payload = beacon.copy(
            position = beacon.position.takeUnless { sharingPaused },
            link = links?.outgoing(),
            book = book?.digest(),
        )
        val s = (seq.getAndIncrement()).toUInt()
        val sentAt = clock()
        val signed = signer?.let { Identity.sign(it, signedBeaconBytes(s, sentAt, payload)) }
            ?.takeIf { it.isNotEmpty() }
        val body = if (signed != null) {
            payload.copy(sig = signed, pub = signer!!.public.encoded)
        } else {
            payload
        }
        val ct = aead.seal(keys.current(), PayloadCodec.encode(body), FrameCodec.aad(profile.networkId, profile.myMemberId, s, sentAt))
        val wire = FrameCodec.encode(
            EncryptedFrame(profile.networkId, profile.myMemberId, s, INITIAL_TTL, sentAt, ct),
        )
        routed().forEach { it.send(wire) }
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

    /**
     * Seals and sends [payloadBytes] on every agreed radio, or only on those of [only]. [beacon] marks a
     * position beacon (reported to [onFrameSent]); [key]: an older one, for a member that missed a rekey.
     */
    suspend fun sendRaw(
        payloadBytes: ByteArray, beacon: Boolean = false, only: Set<TransportId>? = null, key: ByteArray? = null,
    ): Unit = sendLock.withLock {
        val s = (seq.getAndIncrement()).toUInt()
        val sentAt = clock()
        val aad = FrameCodec.aad(profile.networkId, profile.myMemberId, s, sentAt)
        val ct = aead.seal(key ?: keys.current(), payloadBytes, aad)
        val frame = EncryptedFrame(
            profile.networkId, profile.myMemberId, s, INITIAL_TTL, sentAt, ct,
        )
        val wire = FrameCodec.encode(frame)
        routed().filter { only == null || it.id in only }.forEach { it.send(wire) }
        bump { it.copy(sent = it.sent + 1) }
        if (beacon) onFrameSent?.invoke(wire)
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
        // Newest key first; an older one means the sender missed a rekey.
        val aad = FrameCodec.aad(f.networkId, f.senderId, f.seq, f.sentAtMs)
        val (epoch, plain) = keys.newestFirst().firstNotNullOfOrNull { (e, k) ->
            runCatching { e to aead.open(k, f.ciphertext, aad) }.getOrNull()
        } ?: run {
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
        // A member the master dropped is out: nothing it sends counts, nothing is relayed.
        if (book?.isRemoved(f.senderId) == true) {
            bump { it.copy(dropped = it.dropped + 1) }
            return
        }
        RekeyCodec.decode(plain)?.let { r ->
            bump { it.copy(received = it.received + 1) }
            onRekey(r)
            return
        }
        if (epoch < keys.epoch) {
            bump { it.copy(dropped = it.dropped + 1) }
            behind(f, plain, epoch)
            return
        }
        // Member list / cards from another member: merge (any member passes them on).
        com.icegood.findmeinwood.core.model.RosterCodec.decode(plain)?.let { sync ->
            if (book?.merge(sync) == true) {
                forgetRemoved()
                onBookChanged?.invoke()
            }
            // Still differs (we know something they do not): answer with ours.
            if (book?.knowsMoreThan(sync) == true) syncBook()
            bump { it.copy(received = it.received + 1) }
            relay(f, ev.source, ev.link, only = SYNC_RADIOS)
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
            book?.let { b ->
                // The master lists whoever talks with the network key (let in, or joined by
                // password); its id is its key's hash, so the key cannot be someone else's.
                if (b.isMaster && payload.pub != null && b.add(f.senderId, payload.pub!!)) onBookChanged?.invoke()
                if (payload.book != null && payload.book != b.digest()) syncBook()
            }
            links?.let { announce(it.onFrame(f.senderId, ev.source, direct, payload.link)) }
            payload.link?.name?.let { tracker.setNickname(f.senderId, it.take(32)) }
        } else {
            links?.let { announce(it.onFrame(f.senderId, ev.source, direct, null)) }
        }
        bump { it.copy(received = it.received + 1) }
        onFrameReceived?.invoke(plain, f.senderId)
        relay(f, ev.source, ev.link)
    }

    /**
     * Master only: re-salts the network key for every member still listed (after a removal).
     * The new salt is sealed to each member's box key, so the removed member, which still
     * holds every older key, cannot follow; the rekey goes out under the previous key.
     */
    suspend fun rekey(): Boolean {
        val b = book ?: return false
        val me = signer ?: return false
        if (!b.isMaster) return false
        val epoch = keys.epoch + 1
        val salt = EpochKeys.newSalt()
        val r = sealRekey(b, me, epoch, salt, rev = 1) ?: return false
        val previous = keys.current()
        keys.advance(epoch, salt)
        latestRekey = r
        epochChanged()
        sendRaw(RekeyCodec.encode(r), only = SYNC_RADIOS, key = previous)
        return true
    }

    private fun sealRekey(b: MemberBook, me: java.security.KeyPair, epoch: Int, salt: ByteArray, rev: Int): Rekey? {
        val eph = EpochKeys.boxKeyPair()
        val wraps = b.members().filter { it != b.myId }.mapNotNull { id ->
            b.boxOf(id)?.let { box -> SaltWrap(id.bytes, EpochKeys.seal(salt, eph, box, rekeyAad(epoch, id))) }
        }
        val r = Rekey(profile.networkId.bytes, epoch, rev, eph.public.encoded, wraps, roster = b.roster)
        return Identity.sign(me, RekeyCodec.signedBytes(r))?.let { r.copy(sig = it) }
    }

    private fun rekeyAad(epoch: Int, id: MemberId) =
        profile.networkId.bytes + ByteArray(4) { (epoch ushr (24 - 8 * it)).toByte() } + id.bytes

    /**
     * Master only: a listed member whose box key arrived after the rekey gets the same salt
     * in a new revision. True when one was issued.
     */
    private fun reissueIfMissing(): Boolean {
        val b = book ?: return false
        val me = signer ?: return false
        val r = latestRekey ?: return false
        if (!b.isMaster || r.epoch != keys.epoch) return false
        val sealed = r.wraps.map { MemberId(it.id) }.toSet()
        if (b.members().none { it != b.myId && it !in sealed && b.boxOf(it) != null }) return false
        val salt = keys.salt(r.epoch) ?: return false
        latestRekey = sealRekey(b, me, r.epoch, salt, r.rev + 1) ?: return false
        onEpoch?.invoke()
        return true
    }

    /** A rekey from the master (signature checked against the member list's master key). */
    private suspend fun onRekey(r: Rekey) {
        val b = book ?: return
        if (!r.net.contentEquals(profile.networkId.bytes)) return
        // Its member list names the master (to a phone joined by password) and the removal.
        r.roster?.let { if (b.merge(com.icegood.findmeinwood.core.model.RosterSync(it))) { forgetRemoved(); onBookChanged?.invoke() } }
        val masterPub = b.masterPub() ?: return
        if (!Identity.verify(masterPub, RekeyCodec.signedBytes(r), r.sig)) return
        val cur = latestRekey
        if (r.epoch < keys.epoch) return
        if (r.epoch == keys.epoch) {
            // Same key, sealed to more members: keep the newer revision to hand on.
            if (cur == null || cur.epoch < r.epoch || r.rev > cur.rev) { latestRekey = r; onEpoch?.invoke() }
            return
        }
        val mine = r.wraps.firstOrNull { it.id.contentEquals(profile.myMemberId.bytes) }
        val salt = box?.let { pair -> mine?.let { EpochKeys.open(it.ct, pair, r.eph, rekeyAad(r.epoch, profile.myMemberId)) } }
        if (salt == null) {
            // Not sealed to us: the master lacks our box key. Our card carries it.
            com.icegood.findmeinwood.transport.api.DebugLog.d { "rekey ${r.epoch}.${r.rev}: not sealed to this phone, sending our card" }
            syncBook(force = true)
            return
        }
        if (!keys.advance(r.epoch, salt)) return
        latestRekey = r
        epochChanged()
    }

    /** A frame under an older key: its sender missed a rekey. Only what gets it the new key counts. */
    private suspend fun behind(f: EncryptedFrame, plain: ByteArray, epoch: Int) {
        val b = book ?: return
        if (b.isMaster) {
            // The master seals the key to members it knows the box key of: take their card
            // (signed by them), and list one let in or joined by password, as on the current key.
            val sync = com.icegood.findmeinwood.core.model.RosterCodec.decode(plain)
            if (sync != null) {
                if (b.merge(sync)) onBookChanged?.invoke()
            } else if (PayloadCodec.tryDecodeChat(plain) == null) {
                runCatching { PayloadCodec.decode(plain) }.getOrNull()?.let { p ->
                    if (p.pub != null && verifyOrigin(f, p) && b.add(f.senderId, p.pub!!)) onBookChanged?.invoke()
                }
            }
            if (reissueIfMissing()) synchronized(catchUpAt) { catchUpAt.remove(f.senderId) }
        }
        catchUp(f.senderId, epoch)
    }

    /** Hands the newest rekey to [member], sealed under the older key it still uses. */
    private suspend fun catchUp(member: MemberId, epoch: Int) {
        val r = latestRekey ?: return
        if (r.epoch != keys.epoch) return
        val key = keys.key(epoch) ?: return
        val now = System.nanoTime() / 1_000_000
        synchronized(catchUpAt) {
            if (now - (catchUpAt[member] ?: Long.MIN_VALUE / 2) < catchUpGapMs) return
            catchUpAt[member] = now
        }
        com.icegood.findmeinwood.transport.api.DebugLog.d { "rekey ${r.epoch}.${r.rev} handed to a member on epoch $epoch" }
        sendRaw(RekeyCodec.encode(r), only = SYNC_RADIOS, key = key)
    }

    /** The key moved on: persist, switch the radios' secret, and say hello under it at once. */
    private fun epochChanged() {
        com.icegood.findmeinwood.transport.api.DebugLog.d { "network key: epoch ${keys.epoch}" }
        onEpoch?.invoke()
        if (beaconsEnabled) pushGroupSecrets()
        links?.announceNow()
        replyNow(force = true)
    }

    /**
     * Passes [f] on with one hop less: on every other radio, and on the radio it came in on
     * to every link but [link] (a Wi-Fi Direct group owner or a phone with several Bluetooth
     * links is the only way between the members around it). The replay window drops the
     * copies that come back, so nothing loops.
     */
    private suspend fun relay(f: EncryptedFrame, source: TransportId, link: String?, only: Set<TransportId>? = null) {
        if (relayEnabled && f.ttl > 1u) {
            val relayed = f.copy(ttl = (f.ttl - 1u).toUByte())
            val wire = FrameCodec.encode(relayed)
            routed().filter { only == null || it.id in only }.forEach { t ->
                if (t.id != source) t.send(wire) else if (link != null) t.relay(wire, except = link)
            }
            bump { it.copy(relayed = it.relayed + 1) }
        }
    }

    /**
     * NFR-3: origin authentication — pin the sender key once, then verify every frame.
     * Once a key is pinned, unsigned beacons from that member are refused, otherwise
     * stripping the signature would bypass the check.
     */
    private fun verifyOrigin(f: EncryptedFrame, payload: BeaconPayload): Boolean {
        val sig = payload.sig ?: return !requireSignatures && verifier.keyFor(f.senderId) == null
        val pub = payload.pub
        // A member id is its key's hash: a key that is not the sender's own is refused,
        // so nobody can pin a key for someone else and lock their real beacons out.
        if (pub != null && !java.security.MessageDigest.getInstance("SHA-256").digest(pub).contentEquals(f.senderId.bytes)) {
            return false
        }
        if (pub != null && verifier.keyFor(f.senderId) == null && !verifier.pin(f.senderId, pub)) return false
        return verifier.verify(f.senderId, signedBeaconBytes(f.seq, f.sentAtMs, payload), sig)
    }
}
