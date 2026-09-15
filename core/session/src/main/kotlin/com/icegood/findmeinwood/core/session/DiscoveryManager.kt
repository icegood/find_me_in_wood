package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.AesGcmAead
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.JoinCrypto
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.crypto.MgmtFrame
import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.HelloPayload
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
 * FR-8.1–FR-8.6: cleartext HELLO discovery plus the on-radio join handshake.
 *
 * Pure Kotlin. Frames are signed by the sender's device identity and verified
 * trust-on-first-use: the public key is pinned to a member id on first sight, and any
 * later frame from that id with a different key is dropped.
 *
 * Key exchange: the joiner sends an ephemeral public key in JOIN_REQ; the owner answers
 * with its own ephemeral public key in JOIN_ACCEPT, so both sides derive the same ECDH
 * secret that protects the network secret and yields the 6-digit SAS.
 */
class DiscoveryManager(
    private val profile: NetworkProfile,
    private val identity: KeyPair,
    private val nowMs: () -> Long,
    private val helloIntervalMs: Long = 15_000,
    private val staleAfterMs: Long = 90_000,
) {
    private val myId: MemberId = profile.myMemberId
    private var lastHelloAtMs = 0L
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

    private class JoinRequest(
        val joinerPub: ByteArray,
        val ownerPair: KeyPair,
        val nonce: Long,
        val sharedKey: ByteArray,
        val sas: String,
    )

    fun clearOutcome() { _outcome.value = null }

    // ----------------------------------------------------------------- outgoing

    fun helloDue(now: Long = nowMs()): Boolean = now - lastHelloAtMs >= helloIntervalMs

    fun buildHello(activeTransports: List<TransportId>): ByteArray {
        lastHelloAtMs = nowMs()
        val payload = HelloPayload(
            name = if (profile.policy == JoinPolicy.CODE) null else profile.name, // FR-8.1 hidden
            ownerMemberId = profile.ownerMemberId.bytes,
            ownerNick = null,
            policy = profile.policy,
            activeTransports = activeTransports,
        )
        return send(MgmtType.HELLO, profile.networkId, PayloadCodec.encodeHello(payload))
    }

    fun buildJoinRequest(peerNetworkId: NetworkId, joinerNick: String?): ByteArray {
        val ephemeral = JoinCrypto.ephemeralKeyPair().also { myEphemeral = it }
        val payload = JoinRequestPayload(joinerPubkey = ephemeral.public.encoded, joinerNick = joinerNick)
        return send(MgmtType.JOIN_REQ, peerNetworkId, PayloadCodec.encodeJoinRequest(payload))
    }

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
        _ownerSas.value = req.sas
        _pendingJoins.value = _pendingJoins.value.filterNot { it.joinerId == joinerId }
        return send(
            MgmtType.JOIN_ACCEPT,
            profile.networkId,
            PayloadCodec.encodeJoinAccept(payload),
            nonce = req.nonce, // FR-8.4 nonce echo
        )
    }

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

    fun onMgmtFrame(frame: MgmtFrame, senderKey: java.security.PublicKey?) {
        if (frame.senderId.bytes.contentEquals(myId.bytes)) return
        val pinned = pinnedKeys[frame.senderId]
        if (pinned != null) {
            if (!MgmtCodec.verify(frame, pinned)) return // key changed: drop
        } else if (senderKey != null && MgmtCodec.verify(frame, senderKey)) {
            pinnedKeys[frame.senderId] = senderKey
        }
        when (frame.type) {
            MgmtType.HELLO -> onHello(frame)
            MgmtType.JOIN_REQ -> onJoinRequest(frame)
            MgmtType.JOIN_ACCEPT -> onJoinAccept(frame)
            MgmtType.JOIN_REJECT -> onJoinReject(frame)
        }
    }

    private fun onHello(frame: MgmtFrame) {
        val payload = runCatching { PayloadCodec.decodeHello(frame.payload) }.getOrNull() ?: return
        if (payload.policy == JoinPolicy.CODE) return // hidden: never listed
        val entry = DiscoveredNetwork(
            networkId = frame.networkId,
            name = payload.name,
            ownerMemberId = MemberId(payload.ownerMemberId),
            ownerNick = payload.ownerNick,
            policy = payload.policy,
            activeTransports = payload.activeTransports,
            lastSeenMs = nowMs(),
        )
        _discovered.value = _discovered.value.filterNot { it.networkId == entry.networkId } + entry
    }

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
        _outcome.value = JoinOutcome.Joined(payload.name, secret, sas)
    }

    private fun onJoinReject(frame: MgmtFrame) {
        val payload = runCatching { PayloadCodec.decodeJoinReject(frame.payload) }.getOrNull()
        _outcome.value = JoinOutcome.Rejected(payload?.reason ?: "rejected by owner")
    }

    fun pruneStale(now: Long = nowMs()) {
        _discovered.value = _discovered.value.filter { now - it.lastSeenMs < staleAfterMs }
    }

    fun pinKey(member: MemberId, key: java.security.PublicKey) { pinnedKeys[member] = key }

    private fun send(type: MgmtType, netId: NetworkId, payload: ByteArray, nonce: Long = nextNonce()): ByteArray =
        MgmtCodec.encode(
            MgmtFrame(type, netId, myId, nonce, payload, ByteArray(0)),
            identity,
        )

    private fun nextNonce(): Long = nonceCounter.also { nonceCounter++ }

    private fun Long.toBytes(): ByteArray = ByteArray(8).also { FrameCodec.writeU64(it, 0, this) }
}
