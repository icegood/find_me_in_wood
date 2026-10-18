package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One radio shaped like a Wi-Fi Direct group: A and B are joined to M's group and reach
 * only M. M passes each one's frames on to the other on the same radio, never back to the
 * sender, and nothing loops.
 */
class StarRelayTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("star-relay-secret".toByteArray())

    /** A radio with named links to neighbours; frames arrive tagged with the link. */
    private inner class StarRadio(val name: String) : Transport {
        override val id = TransportId.WIFI_DIRECT
        val events = MutableSharedFlow<TransportEvent>(replay = 64, extraBufferCapacity = 256)
        val links = mutableListOf<StarRadio>()
        val relayedTo = mutableListOf<String>()

        override fun start(config: SessionConfig): Flow<TransportEvent> = events
        override suspend fun stop() {}
        override suspend fun send(wireFrame: ByteArray): SendResult = write(wireFrame, null)
        override suspend fun relay(wireFrame: ByteArray, except: String): SendResult = write(wireFrame, except)

        private suspend fun write(wire: ByteArray, except: String?): SendResult {
            links.filter { it.name != except }.forEach { to ->
                if (except != null) synchronized(relayedTo) { relayedTo += to.name }
                to.events.emit(TransportEvent.FrameReceived(FrameCodec.decode(wire), id, name))
            }
            return SendResult.Sent
        }
    }

    private inner class Phone(name: String) {
        val pair = Identity.generate()
        val id = MemberId(Identity.memberIdOf(pair))
        val radio = StarRadio(name)
        val session = SessionManager(
            NetworkProfile(name, keys.networkId, keys.trafficKey, id, id, JoinPolicy.PRIVATE),
            listOf(radio), flowOf(), { now },
            scope = scope, keepAliveMs = 3_600_000L, signer = pair, requireSignatures = true,
        )
    }

    private val m = Phone("M")
    private val a = Phone("A")
    private val b = Phone("B")

    init {
        m.radio.links += listOf(a.radio, b.radio)
        a.radio.links += m.radio
        b.radio.links += m.radio
    }

    @AfterTest
    fun tearDown() = runBlocking {
        listOf(m, a, b).forEach { it.session.stop() }
        scope.cancel()
    }

    @Test
    fun `the group owner passes frames between members that reach only it`() = runBlocking {
        listOf(m, a, b).forEach { it.session.start() }
        a.session.sendBeaconSigned(BeaconPayload())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (b.session.peerFlow.value[a.id] == null) {
            check(System.nanoTime() < deadline) { "B never heard A" }
            delay(10)
        }
        val heard = assertNotNull(b.session.peerFlow.value[a.id])
        assertEquals(null, heard.transport, "relayed, not heard directly")
        assertEquals(listOf("B"), m.radio.relayedTo, "passed on to B only, never back to A")
        delay(200)
        assertTrue(b.session.diagnostics.value.relayed <= 1, "B passes it on to nobody new: nothing loops")
        assertEquals(listOf("B"), m.radio.relayedTo)
    }
}
