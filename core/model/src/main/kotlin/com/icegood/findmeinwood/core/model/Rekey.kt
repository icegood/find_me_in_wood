package com.icegood.findmeinwood.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** The new epoch's salt sealed to one member's box key. */
@Serializable
data class SaltWrap(
    /** The member's id. */
    val id: ByteArray,
    val ct: ByteArray,
)

/**
 * A new network key (epoch) after a member was removed, signed by the master: the new salt
 * is sealed to each remaining member's box key, so a removed member, who still holds the
 * older keys, cannot follow. A higher [rev] of the same epoch seals the same salt to more
 * members (a card with a box key arrived later).
 */
@Serializable
data class Rekey(
    val net: ByteArray,
    val epoch: Int,
    val rev: Int,
    /** The master's one-off EC P-256 key the salts were sealed with. */
    val eph: ByteArray,
    val wraps: List<SaltWrap>,
    /**
     * The master's current member list: shows who the master is to a phone that does not
     * know yet (joined by password), and that the removed member is out.
     */
    val roster: Roster? = null,
    val sig: ByteArray = ByteArray(0),
)

object RekeyCodec {
    /** Prefix that marks an encrypted payload as a rekey (not a beacon, chat or member list). */
    val MAGIC = byteArrayOf(0x46, 0x4D, 0x52, 0x4B) // "FMRK"

    fun encode(r: Rekey): ByteArray = MAGIC + Cbor.encodeToByteArray(Rekey.serializer(), r)

    fun decode(plain: ByteArray): Rekey? =
        if (plain.size <= MAGIC.size || MAGIC.indices.any { plain[it] != MAGIC[it] }) null
        else runCatching { Cbor.decodeFromByteArray(Rekey.serializer(), plain.copyOfRange(MAGIC.size, plain.size)) }.getOrNull()

    /** The bytes the master's signature covers: the rekey with its signature left out. */
    fun signedBytes(r: Rekey): ByteArray = Cbor.encodeToByteArray(Rekey.serializer(), r.copy(sig = ByteArray(0)))
}
