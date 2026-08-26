package app.findmeinwood.core.session

import app.findmeinwood.core.crypto.AesGcmAead
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.crypto.Identity
import app.findmeinwood.core.crypto.NetworkKeysFactory
import app.findmeinwood.core.model.BeaconPayload
import app.findmeinwood.core.model.BeaconPosition
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.PayloadCodec
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionManagerBranchTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("branch-net".toByteArray())
    private val me = MemberId(Identity.memberIdOf(Identity.generate()))
    private val profile = NetworkProfile(
        name = "net", networkId = keys.networkId, trafficKey = keys.trafficKey,
        myMemberId = me, ownerMemberId = me, policy = JoinPolicy.PRIVATE,
    )
    private val other = MemberId(Identity.memberIdOf(Identity.generate()))
    private val t = FakeTransport(TransportId.BLUETOOTH)

    private fun manager(
        transports: List<FakeTransport> = listOf(t),
        relayEnabled: Boolean = true,
        edgeManager: EdgeManager? = null,
        keepAliveMs: Long = 10,
    ) = SessionManager(
        profile, transports, flowOf(), { now },
        relayEnabled = relayEnabled, edgeManager = edgeManager, scope = scope,
        keepAliveMs = keepAliveMs,
    )

    private fun frame(
        networkId: NetworkId = profile.networkId,
        senderId: MemberId = other,
        seq: UInt,
        ttl: UByte = 3u,
        payload: ByteArray = "not-cbor-garbage".toByteArray(),
        tamperCt: Boolean = false,
    ): EncryptedFrame {
        val aad = FrameCodec.aad(networkId, senderId, seq, now)
        var ct = AesGcmAead.seal(profile.trafficKey, payload, aad)
        if (tamperCt) ct[ct.size - 1] = (ct.last() + 1).toByte()
        return EncryptedFrame(networkId, senderId, seq, ttl, now, ct)
    }

    @Test
    fun `foreign network frames are dropped`() = runBlocking {
        val m = manager()
        m.start()
        try {
            t.deliver(frame(networkId = NetworkId(ByteArray(8) { 7 }), seq = 0u))
            delay(100)
            assertEquals(1, m.diagnostics.value.dropped)
            assertEquals(0, m.diagnostics.value.received)
        } finally { m.stop() }
    }

    @Test
    fun `own echoed frames are ignored`() = runBlocking {
        val m = manager()
        m.start()
        try {
            t.deliver(frame(senderId = me, seq = 0u))
            delay(100)
            assertEquals(0, m.diagnostics.value.received)
            assertEquals(0, m.diagnostics.value.dropped)
        } finally { m.stop() }
    }

    @Test
    fun `tampered ciphertext counts authFailed`() = runBlocking {
        val m = manager()
        m.start()
        try {
            t.deliver(frame(seq = 1u, tamperCt = true))
            delay(100)
            assertEquals(1, m.diagnostics.value.authFailed)
            assertEquals(0, m.diagnostics.value.received)
        } finally { m.stop() }
    }

    @Test
    fun `valid crypto but garbage payload counts dropped`() = runBlocking {
        val m = manager()
        m.start()
        try {
            t.deliver(frame(seq = 2u))
            delay(100)
            assertEquals(1, m.diagnostics.value.dropped)
            assertEquals(0, m.diagnostics.value.received)
        } finally { m.stop() }
    }

    @Test
    fun `relay disabled keeps frames local`() = runBlocking {
        val extra = FakeTransport(TransportId.BLUETOOTH)
        val m = manager(transports = listOf(t, extra), relayEnabled = false)
        m.start()
        try {
            t.deliver(frame(seq = 3u, payload = validBeacon()))
            delay(100)
            assertEquals(1, m.diagnostics.value.received)
            assertEquals(0, m.diagnostics.value.relayed)
            // extra may carry own keep-alive beacons, but never a relayed foreign frame
            assertTrue(
                extra.sentHistory.map { FrameCodec.decode(it) }.none { it.senderId == other },
            )
        } finally { m.stop() }
    }

    @Test
    fun `ttl of one is received but not relayed`() = runBlocking {
        val extra = FakeTransport(TransportId.BLUETOOTH)
        val m = manager(transports = listOf(t, extra))
        m.start()
        try {
            t.deliver(frame(seq = 4u, ttl = 1u, payload = validBeacon()))
            delay(100)
            assertEquals(1, m.diagnostics.value.received)
            assertEquals(0, m.diagnostics.value.relayed)
            assertTrue(
                extra.sentHistory.map { FrameCodec.decode(it) }.none { it.senderId == other },
            )
        } finally { m.stop() }
    }

    @Test
    fun `edge views empty without edge manager and set is safe`() = runBlocking {
        val m = manager()
        m.start()
        try {
            assertTrue(m.edges().isEmpty())
            m.setEdgeTransport(other, TransportId.LORA) // must not throw
            assertTrue(m.edges().isEmpty())
        } finally { m.stop() }
    }

    @Test
    fun `edge manager tracks waiting and up states via session`() = runBlocking {
        val em = EdgeManager(me, { now })
        val m = manager(edgeManager = em)
        m.start()
        try {
            m.setEdgeTransport(other, TransportId.WIFI_DIRECT)
            assertEquals(EdgeManager.State.WAITING_PEER, m.edges().single().state)
            t.deliver(frame(seq = 5u, payload = validBeacon()))
            eventually { m.edges().any { it.state == EdgeManager.State.UP } }
            assertEquals(TransportId.BLUETOOTH, m.edges().single().transport)
        } finally { m.stop() }
    }

    @Test
    fun `stale edges fire onEdgeDown with failover candidates`() = runBlocking {
        val em = EdgeManager(me, { now })
        var downPeer: MemberId? = null
        var candidates: List<TransportId> = emptyList()
        val wfd = FakeTransport(TransportId.WIFI_DIRECT)
        val m = manager(transports = listOf(t, wfd), edgeManager = em, keepAliveMs = 10)
        m.onEdgeDown = { peer, c -> downPeer = peer; candidates = c }
        m.start()
        m.setEdgeTransport(other, TransportId.LORA)
        wfd.deliver(frame(seq = 6u, payload = validBeacon()))
        eventually { em.edge(other)?.state == EdgeManager.State.UP }
        now += EdgeManager.KEEP_ALIVE_MS * 3 + 5 // go stale
        eventually { downPeer == other }
        assertEquals(listOf(TransportId.BLUETOOTH), candidates.filter { it != TransportId.INTERNET })
        m.stop()
    }

    @Test
    fun `garbage external wire is silently ignored`() = runBlocking {
        val m = manager()
        m.start()
        try {
            m.acceptExternalWire("definitely not a frame".toByteArray(), TransportId.INTERNET)
            delay(50)
            assertEquals(0, m.diagnostics.value.dropped)
            assertEquals(0, m.diagnostics.value.received)
        } finally { m.stop() }
    }

    @Test
    fun `gnss fixes are encoded into beacons`() = runBlocking {
        val tx = FakeTransport(TransportId.LORA)
        val fixes = flowOf(GnssFix(10.0, 20.0, 3f, 4.0, now))
        val m = SessionManager(profile, listOf(tx), fixes, { now }, scope = scope)
        m.start()
        try {
            eventually { tx.sentHistory.isNotEmpty() }
            val decoded = FrameCodec.decode(tx.sentHistory.peek()!!)
            val plain = AesGcmAead.open(
                profile.trafficKey, decoded.ciphertext,
                FrameCodec.aad(decoded.networkId, decoded.senderId, decoded.seq, decoded.sentAtMs),
            )
            val pos = PayloadCodec.decode(plain).position!!
            assertEquals(100_000_000, pos.latE7)
            assertEquals(200_000_000, pos.lonE7)
        } finally { m.stop() }
    }

    private suspend fun eventually(ms: Long = 5_000, cond: () -> Boolean) {
        withTimeout(ms) { while (!cond()) delay(20) }
    }

    /** Valid CBOR beacon payload so frames pass AEAD + payload decode. */
    private fun validBeacon() = PayloadCodec.encode(BeaconPayload(BeaconPosition(1, 2, 3, 4)))
}

class SessionManagerMicroTest {
    @Test
    fun `running flow and frame-sent hook are exposed`() = runBlocking<Unit> {
        val keys = NetworkKeysFactory.derive("micro".toByteArray())
        val me = MemberId(Identity.memberIdOf(Identity.generate()))
        val profile = NetworkProfile(
            name = "m", networkId = keys.networkId, trafficKey = keys.trafficKey,
            myMemberId = me, ownerMemberId = me, policy = JoinPolicy.PRIVATE,
        )
        val tx = FakeTransport(TransportId.BLUETOOTH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var hooked: ByteArray? = null
        val m = SessionManager(profile, listOf(tx), flowOf(), { 0L }, scope = scope)
        m.onFrameSent = { hooked = it }
        kotlin.test.assertFalse(m.runningFlow.value)
        m.start()
        kotlin.test.assertTrue(m.runningFlow.value)
        m.start() // idempotent
        tx.deliver(FrameCodec.decode(FrameCodec.encode(frame0(profile))))
        m.sendBeacon(null)
        withTimeout(5_000) { while (hooked == null) delay(20) }
        m.stop()
        kotlin.test.assertFalse(m.runningFlow.value)
        scope.cancel()
    }

    private fun frame0(p: NetworkProfile): EncryptedFrame {
        val aad = FrameCodec.aad(p.networkId, MemberId(ByteArray(32) { 9 }), 0u, 0L)
        val ct = AesGcmAead.seal(p.trafficKey, validPayload(), aad)
        return EncryptedFrame(p.networkId, MemberId(ByteArray(32) { 9 }), 0u, 3u, 0L, ct)
    }

    private fun validPayload(): ByteArray =
        PayloadCodec.encode(BeaconPayload(BeaconPosition(1, 2, 3, 4)))
}
