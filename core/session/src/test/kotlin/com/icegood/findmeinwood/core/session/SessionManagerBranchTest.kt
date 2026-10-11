package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.AesGcmAead
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.BeaconPosition
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.FakeTransport
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
        links: LinkNegotiator? = null,
        keepAliveMs: Long = 10,
    ) = SessionManager(
        profile, transports, flowOf(), { now },
        relayEnabled = relayEnabled, links = links, scope = scope,
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
    fun `forged frame cannot lock out the real sender`(): Unit = runBlocking {
        val m = manager()
        m.start()
        try {
            // Wrong key, max seq: must fail auth without poisoning the replay window.
            t.deliver(frame(seq = UInt.MAX_VALUE, payload = validBeacon(), tamperCt = true))
            t.deliver(frame(seq = 10u, payload = validBeacon()))
            delay(100)
            assertEquals(1, m.diagnostics.value.authFailed)
            assertEquals(1, m.diagnostics.value.received)
        } finally { m.stop() }
    }

    @Test
    fun `replayed frame is dropped`(): Unit = runBlocking {
        val m = manager()
        m.start()
        try {
            val f = frame(seq = 11u, payload = validBeacon())
            t.deliver(f)
            t.deliver(f.copy(senderId = MemberId(other.bytes.copyOf())))
            delay(100)
            assertEquals(1, m.diagnostics.value.received)
            assertEquals(1, m.diagnostics.value.dropped)
        } finally { m.stop() }
    }

    @Test
    fun `chat frames are relayed like beacons`(): Unit = runBlocking {
        val extra = FakeTransport(TransportId.WIFI_DIRECT)
        val m = manager(transports = listOf(t, extra))
        extra.start(com.icegood.findmeinwood.transport.api.SessionConfig(profile.networkId, emptySet()))
        m.start()
        try {
            val chat = com.icegood.findmeinwood.core.model.ChatPayloadCodec.encode(
                com.icegood.findmeinwood.core.model.ChatPayload(
                    id = "c1", senderName = "bob", text = "hi", timestamp = now,
                ),
            )
            t.deliver(frame(seq = 12u, payload = chat))
            delay(100)
            assertEquals(1, m.diagnostics.value.received)
            assertEquals(1, m.diagnostics.value.relayed)
            assertTrue(extra.sentHistory.map { FrameCodec.decode(it) }.any { it.senderId == other })
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
    fun `no negotiator means no link views and beacons without link control`() = runBlocking {
        val m = manager()
        m.start()
        try {
            assertTrue(m.edges().isEmpty())
            t.deliver(frame(seq = 5u, payload = validBeacon()))
            eventually { m.peerFlow.value.containsKey(other) }
            assertEquals(TransportId.BLUETOOTH, m.peerFlow.value[other]?.transport)
            assertTrue(m.edges().isEmpty())
        } finally { m.stop() }
    }

    @Test
    fun `negotiator hears direct frames, ignores relayed ones as links, and signs offers in beacons`() = runBlocking {
        val links = LinkNegotiator(me, { now }, beaconIntervalMs = 10)
        links.setOffers(listOf(LinkOffer(TransportId.BLUETOOTH), LinkOffer(TransportId.SMS, "+1")))
        val switches = mutableListOf<LinkNegotiator.Switch>()
        val m = manager(links = links, keepAliveMs = 10)
        m.onLinkSwitch = { switches += it }
        m.start()
        try {
            t.deliver(frame(seq = 6u, ttl = 2u, payload = validBeacon())) // relayed
            eventually { m.peerFlow.value.containsKey(other) }
            assertEquals(null, m.peerFlow.value[other]?.transport)
            assertEquals(null, links.active(other))
            t.deliver(frame(seq = 7u, payload = validBeacon())) // direct
            eventually { links.active(other) == TransportId.BLUETOOTH }
            assertEquals(TransportId.BLUETOOTH, switches.last().to)
            now += 1_000 // silent; no agreed fallback -> no rung
            eventually { links.active(other) == null }

            m.sendBeaconSigned(BeaconPayload())
            val sent = FrameCodec.decode(t.sentHistory.last())
            val plain = AesGcmAead.open(profile.trafficKey, sent.ciphertext, FrameCodec.aad(sent.networkId, sent.senderId, sent.seq, sent.sentAtMs))
            assertEquals("+1", PayloadCodec.decode(plain).link?.offers?.last()?.addr)
        } finally { m.stop() }
    }

    @Test
    fun `sms reports reach the tracker only for members already met`() = runBlocking {
        val links = LinkNegotiator(me, { now }, beaconIntervalMs = 10)
        val m = manager(links = links)
        m.start()
        try {
            assertEquals(null, m.acceptSmsReport(other.bytes.copyOf(8), 10u, now, BeaconPosition(1, 2, 300, 0), 50, sos = true))
            t.deliver(frame(seq = 5u, payload = validBeacon()))
            eventually { m.peerFlow.value.containsKey(other) }
            assertEquals(other, m.acceptSmsReport(other.bytes.copyOf(8), 10u, now, BeaconPosition(7, 8, 300, 0), 50, sos = true))
            val p = m.peerFlow.value[other]!!
            assertEquals(7, p.lastPosition?.latE7)
            assertTrue(p.sos)
            assertEquals(TransportId.SMS, p.transport)
        } finally { m.stop() }
    }

    @Test
    fun `garbage external wire is dropped and counted`() = runBlocking {
        val m = manager()
        m.start()
        try {
            m.acceptExternalWire("definitely not a frame".toByteArray(), TransportId.INTERNET)
            delay(50)
            // FR-9.3: invalid envelopes are dropped with a counter, never silent.
            assertEquals(1, m.diagnostics.value.dropped)
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
