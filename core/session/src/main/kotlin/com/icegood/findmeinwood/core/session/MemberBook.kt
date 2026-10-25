package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberCard
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.Roster
import com.icegood.findmeinwood.core.model.RosterCodec
import com.icegood.findmeinwood.core.model.RosterEntry
import com.icegood.findmeinwood.core.model.RosterSync
import com.icegood.findmeinwood.transport.api.DebugLog
import java.security.KeyPair
import java.security.MessageDigest

/**
 * This phone's copy of the network's member list (signed by the master) and of every
 * member card (each signed by its member). Phones compare [digest]s in beacons and push a
 * [RosterSync] when theirs differ; merging keeps the newest of everything, so all copies
 * converge without the master being present. Pure Kotlin.
 *
 * A member id is the SHA-256 of the member's public key, so signatures are checked
 * against ids directly: nobody can sign for someone else, and only the master's key
 * makes a member list valid.
 */
class MemberBook(
    private val networkId: NetworkId,
    private val me: KeyPair,
    /** The master's member id; null until learned (joined by password: first valid list). */
    masterId: MemberId?,
    /** This phone's box public key, published on its card: new network keys are sealed to it. */
    private val myBox: ByteArray? = null,
) {
    val myId = MemberId(Identity.memberIdOf(me))

    @Volatile var masterId: MemberId? = masterId
        private set

    val isMaster: Boolean get() = masterId == myId

    var roster: Roster? = null
        private set
    private val cards = LinkedHashMap<MemberId, MemberCard>()

    /** Called with the master id when it was learned from a member list (to persist). */
    var onMasterLearned: ((MemberId) -> Unit)? = null

    init {
        if (isMaster) roster = sign(Roster(networkId.bytes, 1, me.public.encoded, listOf(RosterEntry(myId.bytes, me.public.encoded))))
    }

    @Synchronized
    fun cards(): List<MemberCard> = cards.values.toList()

    @Synchronized
    fun card(id: MemberId): MemberCard? = cards[id]

    /** A member's box key from its signed card (null: not known yet, or an older app). */
    @Synchronized
    fun boxOf(id: MemberId): ByteArray? = cards[id]?.box

    /** The master's signing key, from a member list it signed (null until one is known). */
    @Synchronized
    fun masterPub(): ByteArray? = roster?.masterPub

    /** Members on the list (not removed). */
    @Synchronized
    fun members(): List<MemberId> = roster?.members.orEmpty().filterNot { it.removed }.map { MemberId(it.id) }

    /** The name the master recorded for [id] (kept for removed members). */
    @Synchronized
    fun listedName(id: MemberId): String? = roster?.members.orEmpty().firstOrNull { it.id.contentEquals(id.bytes) }?.name

    @Synchronized
    fun isRemoved(id: MemberId): Boolean = roster?.members.orEmpty().any { it.removed && it.id.contentEquals(id.bytes) }

    @Synchronized
    fun isListed(id: MemberId): Boolean = roster?.members.orEmpty().any { !it.removed && it.id.contentEquals(id.bytes) }

    /** Short fingerprint of what this phone knows; carried in beacons. */
    @Synchronized
    fun digest(): Int {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(intBytes(roster?.version ?: 0))
        cards.entries.sortedBy { it.key.bytes.toHex() }.forEach { (id, c) -> md.update(id.bytes); md.update(longBytes(c.at)) }
        return md.digest().let { (it[0].toInt() and 0xFF shl 24) or (it[1].toInt() and 0xFF shl 16) or (it[2].toInt() and 0xFF shl 8) or (it[3].toInt() and 0xFF) }
    }

    @Synchronized
    fun toSync(): RosterSync = RosterSync(roster, cards.values.toList())

    /** Publishes this phone's own card when its name or offers changed. */
    @Synchronized
    fun updateMyCard(name: String?, offers: List<LinkOffer>, now: Long): Boolean {
        val cur = cards[myId]
        if (cur != null && cur.name == name && cur.offers == offers && cur.box.contentEquals(myBox)) return false
        cards[myId] = sign(MemberCard(myId.bytes, me.public.encoded, name, offers, now, box = myBox))
        return true
    }

    /** Master only: puts [id] on the list (a member let in, or heard with the network key). */
    @Synchronized
    fun add(id: MemberId, pub: ByteArray): Boolean {
        val r = roster ?: return false
        if (!isMaster || !idOf(pub).contentEquals(id.bytes)) return false
        if (r.members.any { it.id.contentEquals(id.bytes) }) return false // listed, or removed for good
        roster = sign(r.copy(version = r.version + 1, members = r.members + RosterEntry(id.bytes, pub)))
        return true
    }

    /** Master only: drops [id] from the list; its frames are ignored from then on. */
    @Synchronized
    fun remove(id: MemberId): Boolean {
        val r = roster ?: return false
        if (!isMaster || id == myId) return false
        val e = r.members.firstOrNull { it.id.contentEquals(id.bytes) && !it.removed } ?: return false
        val name = cards[id]?.name ?: e.name
        roster = sign(r.copy(version = r.version + 1, members = r.members.map { if (it === e) it.copy(removed = true, name = name) else it }))
        cards.remove(id)
        return true
    }

    /** True when this phone knows something newer than [sync] (then it should answer). */
    @Synchronized
    fun knowsMoreThan(sync: RosterSync): Boolean {
        if ((roster?.version ?: 0) > (sync.roster?.version ?: 0)) return true
        val theirs = sync.cards.associate { MemberId(it.id) to it.at }
        return cards.any { (id, c) -> c.at > (theirs[id] ?: Long.MIN_VALUE) }
    }

    /** Merges what a member sent; true when anything here changed. */
    @Synchronized
    fun merge(sync: RosterSync): Boolean {
        var changed = false
        sync.roster?.let { r ->
            if (validRoster(r) && r.version > (roster?.version ?: 0)) {
                // The master first: a reader that sees the list must see who signed it.
                if (masterId == null) {
                    masterId = MemberId(idOf(r.masterPub)).also { onMasterLearned?.invoke(it) }
                }
                roster = r
                r.members.filter { it.removed }.forEach { cards.remove(MemberId(it.id)) }
                changed = true
            }
        }
        sync.cards.forEach { c ->
            val id = MemberId(c.id)
            if (id == myId || isRemoved(id) || !validCard(c)) return@forEach
            if (c.at > (cards[id]?.at ?: Long.MIN_VALUE)) {
                cards[id] = c
                changed = true
            }
        }
        return changed
    }

    private fun validRoster(r: Roster): Boolean {
        val why = when {
            !r.net.contentEquals(networkId.bytes) -> "another network"
            // Known master: only its key. Unknown (joined by password): the first valid
            // list names it, and from then on only that key counts.
            masterId?.let { !idOf(r.masterPub).contentEquals(it.bytes) } == true -> "not signed by the master"
            !verify(r.masterPub, RosterCodec.signedBytes(r), r.sig) -> "bad signature (${r.sig.size} B)"
            else -> return true
        }
        DebugLog.d { "member list v${r.version} rejected: $why" }
        return false
    }

    private fun validCard(c: MemberCard): Boolean =
        idOf(c.pub).contentEquals(c.id) && verify(c.pub, RosterCodec.signedBytes(c), c.sig)

    private fun sign(r: Roster) = r.copy(sig = Identity.sign(me, RosterCodec.signedBytes(r)) ?: ByteArray(0))
    private fun sign(c: MemberCard) = c.copy(sig = Identity.sign(me, RosterCodec.signedBytes(c)) ?: ByteArray(0))

    private fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean =
        sig.isNotEmpty() && Identity.verify(pub, data, sig)

    private fun idOf(pub: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(pub)
    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun longBytes(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
