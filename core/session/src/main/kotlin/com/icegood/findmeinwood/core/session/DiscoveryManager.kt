package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.AesGcmAead
import com.icegood.findmeinwood.core.crypto.BleAdvert
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.JoinCrypto
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.crypto.MgmtFrame
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.JoinAcceptPayload
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.JoinRejectPayload
import com.icegood.findmeinwood.core.model.JoinRequestPayload
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.MgmtType
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.PendingJoin
import com.icegood.findmeinwood.core.model.TransportId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPair

sealed interface JoinOutcome {
    data class Joined(val name: String, val secret: String, val sas: String) : JoinOutcome
    data class Rejected(val reason: String) : JoinOutcome
}

/**
 * FR-8.1–FR-8.6: networks found from their radio adverts (only while a member makes the
 * network findable), plus the on-radio join handshake.
 *
 * Pure Kotlin. Every frame carries the sender's device public key and is signed with
 * it; unsigned or badly signed frames are dropped. Keys are pinned to a member id on
 * first sight (trust-on-first-use), and any later frame from that id under a different
 * key is dropped. Any member may answer a join request (every member holds the secret);
 * an accept only counts when its secret derives exactly the network we asked for.
 *
 * Key exchange: the joiner sends an ephemeral public key in JOIN_REQ; the owner answers
 * with its own ephemeral public key in JOIN_ACCEPT, so both sides derive the same ECDH
 * secret that protects the network secret and yields the 6-digit SAS.
 */
class DiscoveryManager(
    private val profile: NetworkProfile,
    private val identity: KeyPair,
    private val nowMs: () -> Long,
    private val staleAfterMs: Long = 90_000,
) {
    // Mutators are @Synchronized: the app calls them from every transport collector (IO)
    // and from UI callbacks at the same time, and the state below is plain HashMaps.
    private val myId: MemberId = profile.myMemberId
    private var nonceCounter = JoinCrypto.nonce()

    /** Network secret, held by the owner only, used to answer approved join requests. */
    var ownerSecret: String? = null

    private val _discovered = MutableStateFlow<List<DiscoveredNetwork>>(emptyList())
    val discovered: StateFlow<List<DiscoveredNetwork>> = _discovered.asStateFlow()

    private val _pendingJoins = MutableStateFlow<List<PendingJoin>>(emptyList())
    val pendingJoins: StateFlow<List<PendingJoin>> = _pendingJoins.asStateFlow()

    private val _outcome = MutableStateFlow<JoinOutcome?>(null)
    val outcome: StateFlow<JoinOutcome?> = _outcome.asStateFlow()

    /** SAS of the last approved PRIVATE join (owner screen shows it for comparison). */
    private val _ownerSas = MutableStateFlow<String?>(null)
    val ownerSas: StateFlow<String?> = _ownerSas.asStateFlow()

    private val pinnedKeys = HashMap<MemberId, java.security.PublicKey>()
    private val pending = HashMap<MemberId, JoinRequest>()
    private var myEphemeral: KeyPair? = null
    /** Network our outstanding JOIN_REQ targets: answers for any other network are ignored. */
    private var requestedNetworkId: NetworkId? = null
    /** Radio peers seen advertising each network: where to connect to ask to join. */
    private val radioPeers = HashMap<NetworkId, LinkedHashMap<String, TransportId>>()

    private class JoinRequest(
        val joinerPub: ByteArray,
        val ownerPair: KeyPair,
        val nonce: Long,
        val sharedKey: ByteArray,
        val sas: String,
    )

    fun clearOutcome() { _outcome.value = null }

    // ----------------------------------------------------------------- outgoing

    @Synchronized
    fun buildJoinRequest(peerNetworkId: NetworkId, joinerNick: String?): ByteArray {
        val ephemeral = JoinCrypto.ephemeralKeyPair().also { myEphemeral = it }
        requestedNetworkId = peerNetworkId
        val payload = JoinRequestPayload(joinerPubkey = ephemeral.public.encoded, joinerNick = joinerNick)
        return send(MgmtType.JOIN_REQ, peerNetworkId, PayloadCodec.encodeJoinRequest(payload))
    }

    @Synchronized
    fun approve(joinerId: MemberId): ByteArray? {
        val req = pending[joinerId] ?: return null
        val secret = ownerSecret ?: return null
        val aad = req.nonce.toBytes()
        val sealed = if (profile.policy == JoinPolicy.OPEN) {
            null
        } else {
            AesGcmAead.seal(req.sharedKey, secret.toByteArray(), aad)
        }
        val payload = JoinAcceptPayload(
            name = profile.name,
            policy = profile.policy,
            secret = if (sealed == null) secret else null,
            ownerPub = if (sealed == null) null else req.ownerPair.public.encoded,
            sealedSecret = sealed,
            nonce = aad,
        )
        pending.remove(joinerId)
        _ownerSas.value = req.sas
        _pendingJoins.value = _pendingJoins.value.filterNot { it.joinerId == joinerId }
        return send(
            MgmtType.JOIN_ACCEPT,
            profile.networkId,
            PayloadCodec.encodeJoinAccept(payload),
            nonce = req.nonce, // FR-8.4 nonce echo
        )
    }

    @Synchronized
    fun reject(joinerId: MemberId, reason: String = "rejected by owner"): ByteArray {
        pending.remove(joinerId)
        _pendingJoins.value = _pendingJoins.value.filterNot { it.joinerId == joinerId }
        return send(
            MgmtType.JOIN_REJECT,
            profile.networkId,
            PayloadCodec.encodeJoinReject(JoinRejectPayload(reason)),
        )
    }

    // ----------------------------------------------------------------- incoming

    @Synchronized
    fun onMgmtFrame(frame: MgmtFrame) {
        if (frame.senderId.bytes.contentEquals(myId.bytes)) return
        // A pinned key wins over whatever the frame claims, so a changed key never verifies.
        val key = pinnedKeys[frame.senderId] ?: MgmtCodec.senderKeyOf(frame) ?: return
        if (!MgmtCodec.verify(frame, key)) return // unsigned, forged or key changed: drop
        pinnedKeys.putIfAbsent(frame.senderId, key)
        when (frame.type) {
            MgmtType.HELLO -> Unit // no longer sent: networks are found from radio adverts
            MgmtType.JOIN_REQ -> onJoinRequest(frame)
            MgmtType.JOIN_ACCEPT -> onJoinAccept(frame)
            MgmtType.JOIN_REJECT -> onJoinReject(frame)
        }
    }

    /** A findable network seen in a radio advert from [radioPeerId]. */
    @Synchronized
    fun onAdvert(info: BleAdvert.Info, radioPeerId: String, transport: TransportId) {
        if (info.policy == JoinPolicy.CODE) return // hidden: never listed
        if (info.networkId == profile.networkId) return // our own network: nothing to join
        radioPeers.getOrPut(info.networkId) { LinkedHashMap() }[radioPeerId] = transport
        val entry = DiscoveredNetwork(
            networkId = info.networkId,
            name = info.name,
            ownerMemberId = MemberId(ByteArray(32)), // adverts name no member
            ownerNick = null,
            policy = info.policy,
            activeTransports = listOf(transport),
            lastSeenMs = nowMs(),
        )
        _discovered.value = _discovered.value.filterNot { it.networkId == entry.networkId } + entry
    }

    /** Radio peers that advertised [networkId], newest last: connect to one to ask to join. */
    @Synchronized
    fun radioPeersOf(networkId: NetworkId): List<Pair<TransportId, String>> =
        radioPeers[networkId].orEmpty().map { (peer, t) -> t to peer }

    private fun onJoinRequest(frame: MgmtFrame) {
        if (frame.networkId != profile.networkId) return
        val payload = runCatching { PayloadCodec.decodeJoinRequest(frame.payload) }.getOrNull() ?: return
        val ownerPair = JoinCrypto.ephemeralKeyPair()
        val shared = JoinCrypto.sharedSecret(ownerPair, payload.joinerPubkey)
        pending[frame.senderId] = JoinRequest(
            joinerPub = payload.joinerPubkey,
            ownerPair = ownerPair,
            nonce = frame.nonce,
            sharedKey = JoinCrypto.aeadKey(shared),
            sas = JoinCrypto.sas(shared),
        )
        _pendingJoins.value = _pendingJoins.value.filterNot { it.joinerId == frame.senderId } +
            PendingJoin(profile.networkId, frame.senderId, payload.joinerNick, nowMs())
    }

    private fun onJoinAccept(frame: MgmtFrame) {
        // JOIN_ACCEPT is cleartext: without this anyone in range could push a network of
        // their choosing onto a device that merely asked to join a different one.
        if (!isAnswerToMyRequest(frame)) return
        val payload = runCatching { PayloadCodec.decodeJoinAccept(frame.payload) }.getOrNull() ?: return
        var sas = "------"
        val secret: String = payload.secret ?: run {
            val ephemeral = myEphemeral ?: return
            val ownerPub = payload.ownerPub ?: return
            val sealed = payload.sealedSecret ?: return
            val shared = JoinCrypto.sharedSecret(ephemeral, ownerPub)
            val plain = runCatching {
                AesGcmAead.open(JoinCrypto.aeadKey(shared), sealed, payload.nonce ?: ByteArray(0))
            }.getOrNull() ?: return
            sas = JoinCrypto.sas(shared)
            plain.decodeToString()
        }
        // The owner named in HELLO is self-declared, so also prove the secret: it must derive
        // exactly the network we asked for, which nobody without the real secret can fake.
        if (NetworkKeysFactory.derive(secret.toByteArray()).networkId != frame.networkId) return
        requestedNetworkId = null
        myEphemeral = null
        _outcome.value = JoinOutcome.Joined(payload.name, secret, sas)
    }

    private fun onJoinReject(frame: MgmtFrame) {
        if (!isAnswerToMyRequest(frame)) return
        requestedNetworkId = null
        myEphemeral = null
        val payload = runCatching { PayloadCodec.decodeJoinReject(frame.payload) }.getOrNull()
        _outcome.value = JoinOutcome.Rejected(payload?.reason ?: "rejected by owner")
    }

    private fun isAnswerToMyRequest(frame: MgmtFrame): Boolean = frame.networkId == requestedNetworkId

    @Synchronized
    fun pruneStale(now: Long = nowMs()) {
        _discovered.value = _discovered.value.filter { now - it.lastSeenMs < staleAfterMs }
    }

    @Synchronized
    fun pinKey(member: MemberId, key: java.security.PublicKey) { pinnedKeys[member] = key }

    private fun send(type: MgmtType, netId: NetworkId, payload: ByteArray, nonce: Long = nextNonce()): ByteArray =
        MgmtCodec.encode(
            MgmtFrame(type, netId, myId, nonce, payload, ByteArray(0)),
            identity,
        )

    private fun nextNonce(): Long = nonceCounter.also { nonceCounter++ }

    private fun Long.toBytes(): ByteArray = ByteArray(8).also { FrameCodec.writeU64(it, 0, this) }
}
