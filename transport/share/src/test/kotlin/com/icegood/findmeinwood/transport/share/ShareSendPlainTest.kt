package com.icegood.findmeinwood.transport.share

import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Plain-JVM tests. The Android Context is an unchecked dummy whose stubbed
 * methods throw — exactly the "no carrier available" environment. Intent glue
 * is border surface; the decision logic and envelope codec are what we gate.
 */
private fun dummyContext(): android.content.Context =
    org.mockito.Mockito.mock(android.content.Context::class.java)

class ShareTransportDecisionTest {
    private val cfg = SessionConfig(com.icegood.findmeinwood.core.model.NetworkId(ByteArray(8)), emptySet())

    @Test
    fun `send without any carrier does not throw`() = runBlocking<Unit> {
        val t = ShareTransport(dummyContext())
        // buffering may fail on a stubbed context, but must never throw or open a UI
        when (val r = t.send(byteArrayOf(1, 2, 3))) {
            is SendResult.Failed, is SendResult.HandedToUser -> Unit
            is SendResult.Sent -> throw AssertionError("beacons are never auto-sent (FR-9.2)")
        }
    }

    @Test
    fun `beacon file name is meaningful and envelope roundtrips`() {
        val name = ShareTransport.beaconFileName(1_700_000_000_000L)
        assertTrue(name.startsWith("findmeinwood-beacon-"))
        assertTrue(name.endsWith(".fmiw1"))
        val env = ShareTransport.encodeEnvelope(byteArrayOf(7, 8, 9))
        assertTrue(env.startsWith("fmiw1:"))
        assertTrue(ShareTransport.parseEnvelope(env)!!.contentEquals(byteArrayOf(7, 8, 9)))
        assertNull(ShareTransport.parseEnvelope("no envelope here"))
    }

    @Test
    fun `start emits ready once`() = runBlocking<Unit> {
        val ev = ShareTransport(dummyContext()).start(cfg).first() as TransportEvent.StateChanged
        assertEquals(RadioState.READY, ev.state)
    }

    @Test
    fun `stop is noop`() = runBlocking<Unit> { ShareTransport(dummyContext()).stop() }

    @Test
    fun `parse envelope edge cases`() {
        assertNull(ShareTransport.parseEnvelope("fmiw1:"))
        assertNull(ShareTransport.parseEnvelope("no prefix here"))
        assertNull(ShareTransport.parseEnvelope("fmiw1:!!!bad!!!"))
        val wire = byteArrayOf(7, 7)
        val env = ShareTransport.encodeEnvelope(wire)
        assertEquals(wire.toList(), ShareTransport.parseEnvelope("msg\n$env.\nbye")!!.toList())
        assertEquals(wire.toList(), ShareTransport.parseEnvelope(env)!!.toList())
    }
}

class ShareInboxSinkTest {
    @Test
    fun `sink is settable and readable`() {
        ShareInbox.sink = { }
        try {
            kotlin.test.assertNotNull(ShareInbox.sink)
        } finally { ShareInbox.sink = null }
        kotlin.test.assertNull(ShareInbox.sink)
    }
}
