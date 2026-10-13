package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.RosterCodec
import com.icegood.findmeinwood.core.model.RosterSync
import com.icegood.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemberBookTest {
    private val net = NetworkId(ByteArray(8) { 1 })
    private val masterPair = Identity.generate()
    private val masterId = MemberId(Identity.memberIdOf(masterPair))
    private val aPair = Identity.generate()
    private val aId = MemberId(Identity.memberIdOf(aPair))
    private val bPair = Identity.generate()
    private val bId = MemberId(Identity.memberIdOf(bPair))

    private val master = MemberBook(net, masterPair, masterId)
    private val a = MemberBook(net, aPair, masterId)
    /** Joined by password: does not know the master yet. */
    private val b = MemberBook(net, bPair, null)

    @Test
    fun `the master lists members, everyone converges by merging`() {
        assertTrue(master.isMaster)
        assertEquals(listOf(masterId), master.members())
        assertTrue(master.add(aId, aPair.public.encoded))
        assertFalse(master.add(aId, aPair.public.encoded), "already listed")
        assertFalse(master.add(bId, aPair.public.encoded), "key does not match the id")
        assertTrue(master.add(bId, bPair.public.encoded))
        assertEquals(3, master.roster!!.version)

        assertTrue(master.knowsMoreThan(a.toSync()))
        assertTrue(a.merge(master.toSync()))
        assertEquals(master.members(), a.members())
        assertTrue(!master.knowsMoreThan(a.toSync()) && !a.knowsMoreThan(master.toSync()), "in sync: nobody answers")
        assertFalse(a.merge(master.toSync()), "nothing new")
        assertFalse(a.isMaster)

        var learned: MemberId? = null
        b.onMasterLearned = { learned = it }
        assertTrue(b.merge(a.toSync()), "any member passes the list on")
        assertEquals(masterId, learned)
        assertEquals(masterId, b.masterId)
        assertTrue(b.isListed(aId))
    }

    @Test
    fun `cards carry each member's name and links, newest wins`() {
        assertTrue(a.updateMyCard("Ira", listOf(LinkOffer(TransportId.SMS, "+380001")), now = 10))
        assertFalse(a.updateMyCard("Ira", listOf(LinkOffer(TransportId.SMS, "+380001")), now = 11), "unchanged")
        assertTrue(a.knowsMoreThan(master.toSync()), "a has a newer card")
        assertTrue(master.merge(a.toSync()))
        assertEquals("+380001", master.card(aId)!!.offers.single().addr)
        val before = master.digest()
        a.updateMyCard("Ira", listOf(LinkOffer(TransportId.SMS, "+380099")), now = 20)
        assertNotEquals(master.digest(), a.digest(), "a has news: digests differ")
        master.merge(a.toSync())
        assertNotEquals(before, master.digest())
        assertEquals("+380099", master.card(aId)!!.offers.single().addr)
        assertFalse(master.merge(RosterSync(cards = listOf(a.card(aId)!!.copy(at = 5)))), "older card ignored")
        // Nobody can sign for someone else.
        val forged = a.card(aId)!!.copy(name = "Mallory", at = 99)
        assertFalse(master.merge(RosterSync(cards = listOf(forged))))
    }

    @Test
    fun `a removed member stays out, its card is dropped`() {
        master.add(aId, aPair.public.encoded)
        a.updateMyCard("Ira", emptyList(), now = 1)
        master.merge(a.toSync())
        assertTrue(master.remove(aId))
        assertFalse(master.remove(aId), "already removed")
        assertFalse(master.remove(masterId), "the master stays")
        assertTrue(master.isRemoved(aId))
        assertNull(master.card(aId))
        assertEquals("Ira", master.listedName(aId), "still recognisable once removed")
        assertFalse(master.add(aId, aPair.public.encoded), "removed for good")
        assertFalse(master.merge(a.toSync()), "its card is not taken back")
        b.merge(master.toSync())
        assertTrue(b.isRemoved(aId))
        assertFalse(b.isListed(aId))
    }

    @Test
    fun `only the master's key makes a list valid`() {
        master.add(aId, aPair.public.encoded)
        // A member signing its own "list" with itself as master.
        val rogue = MemberBook(net, aPair, aId)
        rogue.add(bId, bPair.public.encoded)
        rogue.add(bId, bPair.public.encoded)
        assertFalse(master.merge(rogue.toSync()), "not the master")
        assertFalse(a.merge(rogue.toSync()))
        assertFalse(a.remove(masterId), "members cannot change the list")
        assertFalse(a.add(bId, bPair.public.encoded))
        // A list for another network is ignored even from the master.
        val other = MemberBook(NetworkId(ByteArray(8) { 2 }), masterPair, masterId)
        other.add(aId, aPair.public.encoded)
        assertFalse(a.merge(other.toSync()))
        // A tampered list fails its signature.
        val tampered = master.roster!!.copy(version = 50)
        assertFalse(a.merge(RosterSync(tampered)))
    }

    @Test
    fun `sync frames are told apart from beacons and survive the codec`() {
        master.add(aId, aPair.public.encoded)
        val wire = RosterCodec.encode(master.toSync())
        assertTrue(RosterCodec.isSync(wire))
        assertFalse(RosterCodec.isSync(byteArrayOf(0x46, 0x4D)))
        assertNull(RosterCodec.decode(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(RosterCodec.decode(RosterCodec.MAGIC + byteArrayOf(9)))
        assertTrue(a.merge(RosterCodec.decode(wire)!!))
    }
}
