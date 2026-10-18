package com.icegood.findmeinwood.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * One member on the master's list; [removed] keeps a dropped member out for good, and
 * [name] (their last known name) keeps them recognisable once their card is gone.
 */
@Serializable
data class RosterEntry(val id: ByteArray, val pub: ByteArray, val removed: Boolean = false, val name: String? = null)

/**
 * Who is in the network, signed by the master (whose member id is the SHA-256 of
 * [masterPub]). Every member keeps a copy; a higher [version] replaces a lower one.
 */
@Serializable
data class Roster(
    val net: ByteArray,
    val version: Int,
    val masterPub: ByteArray,
    val members: List<RosterEntry>,
    val sig: ByteArray = ByteArray(0),
)

/** What a member says about itself, signed by that member: name and how to reach it. */
@Serializable
data class MemberCard(
    val id: ByteArray,
    val pub: ByteArray,
    val name: String? = null,
    val offers: List<LinkOffer> = emptyList(),
    val at: Long,
    val sig: ByteArray = ByteArray(0),
    /** The member's key-agreement (EC P-256) public key: new network keys are sealed to it. */
    val box: ByteArray? = null,
)

/** Member list plus every member card a phone knows: what phones exchange to agree. */
@Serializable
data class RosterSync(val roster: Roster? = null, val cards: List<MemberCard> = emptyList())

object RosterCodec {
    /** Prefix that marks an encrypted payload as a member-list sync (not a beacon or chat). */
    val MAGIC = byteArrayOf(0x46, 0x4D, 0x52, 0x53) // "FMRS"

    fun encode(s: RosterSync): ByteArray = MAGIC + Cbor.encodeToByteArray(RosterSync.serializer(), s)

    fun isSync(plain: ByteArray): Boolean =
        plain.size > MAGIC.size && MAGIC.indices.all { plain[it] == MAGIC[it] }

    fun decode(plain: ByteArray): RosterSync? = if (!isSync(plain)) null else runCatching {
        Cbor.decodeFromByteArray(RosterSync.serializer(), plain.copyOfRange(MAGIC.size, plain.size))
    }.getOrNull()

    /** The bytes a signature covers: the item with its signature left out. */
    fun signedBytes(r: Roster): ByteArray = Cbor.encodeToByteArray(Roster.serializer(), r.copy(sig = ByteArray(0)))
    fun signedBytes(c: MemberCard): ByteArray = Cbor.encodeToByteArray(MemberCard.serializer(), c.copy(sig = ByteArray(0)))
}
