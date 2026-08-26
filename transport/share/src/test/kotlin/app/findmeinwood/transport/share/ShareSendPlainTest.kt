package app.findmeinwood.transport.share

import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    private val cfg = SessionConfig(app.findmeinwood.core.model.NetworkId(ByteArray(8)), emptySet())

    @Test
    fun `send without any carrier fails gracefully`() = runBlocking<Unit> {
        val t = ShareTransport(dummyContext())
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1, 2, 3)))
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
