package com.icegood.findmeinwood.core.model

enum class TransportId { WIFI_DIRECT, BLUETOOTH, LORA, INTERNET, SMS }

/** Explicit UI theme: follow the system, or force light / dark regardless of it. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class JoinPolicy { OPEN, PRIVATE, CODE }

class NetworkId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is NetworkId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "NetworkId(${bytes.size}B)"
}

class MemberId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is MemberId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "MemberId(${bytes.size}B)"
}

data class NetworkProfile(
    val name: String,
    val networkId: NetworkId,
    val trafficKey: ByteArray,
    val myMemberId: MemberId,
    val ownerMemberId: MemberId,
    val policy: JoinPolicy,
)

data class GnssFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val epochMs: Long,
)

@kotlinx.serialization.Serializable
data class BeaconPosition(
    val latE7: Int,
    val lonE7: Int,
    val accuracyCm: Int,
    val altitudeCm: Int,
)

@kotlinx.serialization.Serializable
data class BeaconPayload(
    val position: BeaconPosition? = null,
    val fixAgeS: Int? = null,
    val batteryPct: Int? = null,
    /** NFR-3: signature over the frame header + ciphertext. */
    val sig: ByteArray? = null,
    /** Sender's public key (SPKI) — pinned on first sight. */
    val pub: ByteArray? = null,
    /** LNP/1 link negotiation (FR-3.6–3.9); absent on most beacons to keep them small. */
    val link: LinkCtl? = null,
)

/** LNP/1: a transport this member can use for the network, and how to reach it there. */
@kotlinx.serialization.Serializable
data class LinkOffer(
    val t: TransportId,
    /** What a peer needs to reach us on [t] (SMS: our phone number); null when nothing. */
    val addr: String? = null,
)

/** LNP/1: agreement on the ladder with one peer, by hash of the canonical ladder. */
@kotlinx.serialization.Serializable
data class LinkAck(
    /** First 4 bytes of the peer's member id. */
    val p: ByteArray,
    val h: Int,
)

@kotlinx.serialization.Serializable
data class LinkCtl(
    val v: Int = 1,
    val offers: List<LinkOffer> = emptyList(),
    val acks: List<LinkAck> = emptyList(),
    /** Sender's display name, so members show up by name rather than as "member". */
    val name: String? = null,
)

@kotlinx.serialization.Serializable
data class HelloPayload(
    val name: String? = null,
    val ownerMemberId: ByteArray,
    val ownerNick: String? = null,
    val policy: JoinPolicy = JoinPolicy.CODE,
    val activeTransports: List<TransportId> = emptyList(),
)

enum class MgmtType { HELLO, JOIN_REQ, JOIN_ACCEPT, JOIN_REJECT }

/** FR-8.2: what a newcomer may see — never a member count. */
data class DiscoveredNetwork(
    val networkId: NetworkId,
    val name: String?,              // null for hidden/CODE networks and when not advertised
    val ownerMemberId: MemberId,
    val ownerNick: String?,
    val policy: JoinPolicy,
    val activeTransports: List<TransportId>,
    val lastSeenMs: Long,
)

@kotlinx.serialization.Serializable
data class JoinRequestPayload(
    val joinerPubkey: ByteArray,    // ephemeral X25519/EC public key (SPKI)
    val joinerNick: String? = null,
)

@kotlinx.serialization.Serializable
data class JoinAcceptPayload(
    val name: String,
    val policy: JoinPolicy,
    /** OPEN: the secret in clear (FR-8.3 — UI warns it is readable by anyone). */
    val secret: String? = null,
    /** PRIVATE: owner's ephemeral public key, so the joiner derives the same ECDH secret. */
    val ownerPub: ByteArray? = null,
    /** PRIVATE: secret sealed under the ECDH-derived key. */
    val sealedSecret: ByteArray? = null,
    val nonce: ByteArray? = null,
)

@kotlinx.serialization.Serializable
data class JoinRejectPayload(val reason: String = "rejected by owner")

/** Pending join request shown to the owner for approval. */
data class PendingJoin(
    val networkId: NetworkId,
    val joinerId: MemberId,
    val joinerNick: String?,
    val requestedAtMs: Long,
)
