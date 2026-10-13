package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RosterTest {
    private val roster = Roster(
        net = ByteArray(8) { 1 }, version = 3, masterPub = ByteArray(4) { 2 },
        members = listOf(RosterEntry(ByteArray(32) { 3 }, ByteArray(4) { 4 }), RosterEntry(ByteArray(32) { 5 }, ByteArray(4), removed = true)),
        sig = byteArrayOf(9, 9),
    )
    private val card = MemberCard(
        id = ByteArray(32) { 3 }, pub = ByteArray(4) { 4 }, name = "Ira",
        offers = listOf(LinkOffer(TransportId.SMS, "+380")), at = 77, sig = byteArrayOf(8),
    )

    @Test
    fun `a sync round-trips with its prefix`() {
        val wire = RosterCodec.encode(RosterSync(roster, listOf(card)))
        assertTrue(RosterCodec.isSync(wire))
        val back = RosterCodec.decode(wire)!!
        assertEquals(3, back.roster!!.version)
        assertTrue(back.roster!!.members[1].removed)
        assertFalse(back.roster!!.members[0].removed)
        assertContentEquals(roster.masterPub, back.roster!!.masterPub)
        assertContentEquals(roster.net, back.roster!!.net)
        assertContentEquals(roster.sig, back.roster!!.sig)
        assertContentEquals(roster.members[0].pub, back.roster!!.members[0].pub)
        val c = back.cards.single()
        assertEquals("Ira", c.name)
        assertEquals(77, c.at)
        assertEquals("+380", c.offers.single().addr)
        assertContentEquals(card.id, c.id)
        assertContentEquals(card.pub, c.pub)
        assertContentEquals(card.sig, c.sig)
        // Empty sync: no list, no cards.
        val empty = RosterCodec.decode(RosterCodec.encode(RosterSync()))!!
        assertNull(empty.roster)
        assertTrue(empty.cards.isEmpty())
    }

    @Test
    fun `beacons and garbage are not syncs`() {
        assertFalse(RosterCodec.isSync(PayloadCodec.encode(BeaconPayload())))
        assertFalse(RosterCodec.isSync(RosterCodec.MAGIC))
        assertNull(RosterCodec.decode(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(RosterCodec.decode(RosterCodec.MAGIC + byteArrayOf(0x7F)))
    }

    @Test
    fun `signed bytes leave the signature out`() {
        assertContentEquals(RosterCodec.signedBytes(roster), RosterCodec.signedBytes(roster.copy(sig = byteArrayOf(1))))
        assertContentEquals(RosterCodec.signedBytes(card), RosterCodec.signedBytes(card.copy(sig = ByteArray(0))))
        assertFalse(RosterCodec.signedBytes(card).contentEquals(RosterCodec.signedBytes(card.copy(name = "Mallory"))))
        val defaults = MemberCard(id = ByteArray(1), pub = ByteArray(1), at = 0)
        assertNull(defaults.name)
        assertTrue(defaults.offers.isEmpty() && defaults.sig.isEmpty())
        assertEquals(null, BeaconPayload().book)
        assertEquals(5, BeaconPayload(book = 5).book)
    }
}
