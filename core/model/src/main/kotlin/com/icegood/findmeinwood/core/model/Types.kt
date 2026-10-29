package com.icegood.findmeinwood.core.model

enum class TransportId { WIFI_DIRECT, BLUETOOTH, LORA, INTERNET, SMS }

/** Explicit UI theme: follow the system, or force light / dark regardless of it. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * One join policy (FR-2.1): ask the master, or know the password. Kept as a type because
 * stored profiles and the discovery advert carry it.
 */
enum class JoinPolicy { PRIVATE }

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
    /** LNP/3 link negotiation (FR-3.6–3.9); absent on most beacons to keep them small. */
    val link: LinkCtl? = null,
    /** Fingerprint of the sender's member list + cards: a differing one triggers a sync. */
    val book: Int? = null,
)

/** LNP/3: a transport this member can use for the network, and how to reach it there. */
@kotlinx.serialization.Serializable
data class LinkOffer(
    val t: TransportId,
    /** What a peer needs to reach us on [t] (SMS: our phone number); null when nothing. */
    val addr: String? = null,
)

/** What the user can do on this phone to make an unavailable link usable. */
enum class LinkFix { TURN_ON_BLUETOOTH, TURN_ON_WIFI, TURN_ON_LOCATION, SET_PHONE_NUMBER, ALLOW_SMS }

/**
 * One supported link as seen on this phone, for the member list: usable now (reason null,
 * then it is offered in the handshake) or why not, and what fixes it. [transport] is null
 * for a note that is not a link of its own (satellite is carried by SMS).
 */
data class LinkStatus(
    val label: String,
    val transport: TransportId?,
    val reason: String? = null,
    val fix: LinkFix? = null,
    /** What a peer needs to reach us on [transport] (SMS: our number). */
    val addr: String? = null,
)

/**
 * LNP/3 handshake: the transports this user allowed for one peer, bound to both addresses
 * by hash, plus an echo of the peer's own last ack so both know when the step is done.
 */
@kotlinx.serialization.Serializable
data class LinkAck(
    /** First 4 bytes of the peer's member id. */
    val p: ByteArray,
    /** Hash of the allowed transports [t] with both addresses each. */
    val h: Int,
    /** Allowed transports; null = every transport both offer. */
    val t: List<TransportId>? = null,
    /** The [h] of the peer's last ack received here; null = none yet. */
    val e: Int? = null,
)

/** LNP/3: the transport selected for the link with one peer (within the agreed set). */
@kotlinx.serialization.Serializable
data class LinkSel(
    /** First 4 bytes of the peer's member id. */
    val p: ByteArray,
    val t: TransportId,
    /** Per-pair selection counter: the higher one is current; equal: lower member id wins. */
    val e: Int,
)

/** Who hosts the pair's Wi-Fi Direct group, picked by one of the two (FR-3.5b). */
@kotlinx.serialization.Serializable
data class LinkHost(
    /** First 4 bytes of the peer's member id. */
    val p: ByteArray,
    /** The sender hosts (else the peer does). */
    val h: Boolean,
    /** Per-pair pick counter: the higher one is current; equal: lower member id wins. */
    val e: Int,
)

@kotlinx.serialization.Serializable
data class LinkCtl(
    val v: Int = 3,
    val offers: List<LinkOffer> = emptyList(),
    /** Handshake: the agreed transport set per peer, acknowledged by hash. */
    val acks: List<LinkAck> = emptyList(),
    /** Selections made on this phone (repeated for a few beacons, in case one is lost). */
    val sel: List<LinkSel> = emptyList(),
    /** Sender's display name, so members show up by name rather than as "member". */
    val name: String? = null,
    /** Wi-Fi Direct host picks made on this phone (repeated like selections). */
    val hosts: List<LinkHost> = emptyList(),
)

@kotlinx.serialization.Serializable
data class HelloPayload(
    val name: String? = null,
    val ownerMemberId: ByteArray,
    val ownerNick: String? = null,
    val policy: JoinPolicy = JoinPolicy.PRIVATE,
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
    /** Master's ephemeral public key, so the joiner derives the same ECDH secret. */
    val ownerPub: ByteArray? = null,
    /** The password sealed under the ECDH-derived key (never sent in clear). */
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
