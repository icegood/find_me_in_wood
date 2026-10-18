package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RekeyCodecTest {
    private val r = Rekey(
        net = ByteArray(8) { 1 }, epoch = 2, rev = 3, eph = byteArrayOf(4, 5),
        wraps = listOf(SaltWrap(ByteArray(32) { 6 }, byteArrayOf(7))),
        roster = Roster(ByteArray(8) { 1 }, 4, byteArrayOf(8), emptyList()),
        sig = byteArrayOf(9),
    )

    @Test
    fun `round trip, and only rekeys decode`() {
        val back = assertNotNull(RekeyCodec.decode(RekeyCodec.encode(r)))
        assertEquals(2, back.epoch)
        assertEquals(3, back.rev)
        assertContentEquals(byteArrayOf(7), back.wraps.single().ct)
        assertEquals(4, back.roster?.version)
        assertNull(RekeyCodec.decode(RosterCodec.encode(RosterSync())), "a member list is not a rekey")
        assertNull(RekeyCodec.decode(RekeyCodec.MAGIC), "nothing after the magic")
        assertNull(RekeyCodec.decode(RekeyCodec.MAGIC + byteArrayOf(1, 2, 3)), "garbage")
    }

    @Test
    fun `the signature covers everything but itself`() {
        assertContentEquals(RekeyCodec.signedBytes(r), RekeyCodec.signedBytes(r.copy(sig = byteArrayOf(1, 1))))
        assertFalse(RekeyCodec.signedBytes(r).contentEquals(RekeyCodec.signedBytes(r.copy(epoch = 3))))
    }

    @Test
    fun `a card without a box key signs as before`() {
        val card = MemberCard(ByteArray(32), byteArrayOf(1), "Ira", emptyList(), 5)
        assertContentEquals(
            RosterCodec.signedBytes(card),
            RosterCodec.signedBytes(card.copy(box = null)),
        )
        assertTrue(RosterCodec.signedBytes(card.copy(box = byteArrayOf(2))).size > RosterCodec.signedBytes(card).size)
    }
}
