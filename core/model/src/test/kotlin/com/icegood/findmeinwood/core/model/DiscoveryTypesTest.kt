package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscoveryTypesTest {
    @Test
    fun `mgmt types are enumerable`() {
        assertEquals(4, MgmtType.entries.size)
        assertEquals(MgmtType.HELLO, MgmtType.valueOf("HELLO"))
        assertTrue(MgmtType.entries.containsAll(listOf(MgmtType.JOIN_REQ, MgmtType.JOIN_ACCEPT, MgmtType.JOIN_REJECT)))
    }

    @Test
    fun `discovered network equality and toString`() {
        val id = NetworkId(ByteArray(8) { 1 })
        val owner = MemberId(ByteArray(32) { 2 })
        val a = DiscoveredNetwork(id, "net", owner, "Misha", JoinPolicy.OPEN, listOf(TransportId.BLUETOOTH), 10L)
        val b = DiscoveredNetwork(id, "net", owner, "Misha", JoinPolicy.OPEN, listOf(TransportId.BLUETOOTH), 10L)
        val c = a.copy(lastSeenMs = 99L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().isNotEmpty())
        assertEquals("net", a.name)
        assertEquals(owner, a.ownerMemberId)
    }

    @Test
    fun `pending join equality and toString`() {
        val id = NetworkId(ByteArray(8) { 3 })
        val joiner = MemberId(ByteArray(32) { 4 })
        val a = PendingJoin(id, joiner, "Misha", 5L)
        val b = PendingJoin(id, joiner, "Misha", 5L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(joinerNick = "other"))
        assertTrue(a.toString().isNotEmpty())
    }

    @Test
    fun `join payloads with nullable fields keep equality`() {
        // ByteArray members keep identity semantics: compare contents, exercise the
        // generated toString/hashCode so the plain data classes stay covered.
        val a = JoinRequestPayload(ByteArray(4) { 1 }, null)
        assertTrue(a.joinerPubkey.contentEquals(ByteArray(4) { 1 }))
        assertEquals(null, a.joinerNick)
        assertTrue(a.toString().isNotEmpty())
        assertTrue(a.hashCode() != 0)
        val acc = JoinAcceptPayload("n", JoinPolicy.CODE, null, null, null, null)
        assertEquals("n", acc.name)
        assertEquals(JoinPolicy.CODE, acc.policy)
        assertEquals(null, acc.secret)
        assertTrue(acc.toString().isNotEmpty())
        assertTrue(acc.hashCode() != 0)
    }
}
