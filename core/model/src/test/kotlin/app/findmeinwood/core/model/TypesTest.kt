package app.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class TypesTest {
    private fun net(seed: Byte) = NetworkId(ByteArray(8) { seed })
    private fun member(seed: Byte) = MemberId(ByteArray(32) { seed })

    @Test
    fun `network id equality hash toString`() {
        assertEquals(net(1), net(1))
        assertEquals(net(1).hashCode(), net(1).hashCode())
        assertNotEquals(net(1), net(2))
        assertFalse(net(1).equals("x"))
        assertEquals("NetworkId(8B)", net(1).toString())
    }

    @Test
    fun `member id equality hash toString`() {
        assertEquals(member(3), member(3))
        assertEquals(member(3).hashCode(), member(3).hashCode())
        assertNotEquals(member(3), member(4))
        assertFalse(member(3).equals(null))
        assertEquals("MemberId(32B)", member(3).toString())
    }

    @Test
    fun `profile data class copy`() {
        val p = NetworkProfile(
            name = "n", networkId = net(1), trafficKey = ByteArray(32),
            myMemberId = member(2), ownerMemberId = member(2), policy = JoinPolicy.OPEN,
        )
        val q = p.copy(name = "other")
        assertNotEquals(p, q)
        assertEquals(JoinPolicy.OPEN, p.policy)
    }

    @Test
    fun `hello payload defaults`() {
        val h = HelloPayload(ownerMemberId = ByteArray(32))
        assertNullDefaults(h)
        assertEquals(JoinPolicy.CODE, h.policy)
    }

    private fun assertNullDefaults(h: HelloPayload) {
        assertEquals(null, h.name)
        assertEquals(null, h.ownerNick)
        assertEquals(emptyList(), h.activeTransports)
    }
}
