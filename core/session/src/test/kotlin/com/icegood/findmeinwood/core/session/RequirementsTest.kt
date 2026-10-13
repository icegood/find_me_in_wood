package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.AesGcmAead
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.BeaconPosition
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.model.RosterCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session behaviour checked against spec.md (one test per requirement found broken in review). */
class RequirementsTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("requirements".toByteArray())
    private val mePair = Identity.generate()
    private val me = MemberId(Identity.memberIdOf(mePair))
    private val bt = FakeTransport(TransportId.BLUETOOTH)
    private val lora = FakeTransport(TransportId.LORA)
    private val book = MemberBook(keys.networkId, mePair, me)

    private fun session(keepAliveMs: Long = 60_000L) = SessionManager(
        NetworkProfile("n", keys.networkId, keys.trafficKey, me, me, JoinPolicy.PRIVATE),
        listOf(bt, lora), flowOf(), { now }, scope = scope, keepAliveMs = keepAliveMs,
        signer = mePair, book = book, requireSignatures = true,
    )

    @AfterTest
    fun tearDown() { scope.cancel() }

    /** A beacon from [pair] claiming member id [id], signed or not. */
    private fun beacon(pair: java.security.KeyPair, id: MemberId, seq: UInt, signed: Boolean = true, pos: Boolean = true): EncryptedFrame {
        val payload = BeaconPayload(position = if (pos) BeaconPosition(1, 2, 300, 0) else null)
        val sentAt = now
        val body = if (!signed) payload else {
            val probe = SessionManager(
                NetworkProfile("n", keys.networkId, keys.trafficKey, id, id, JoinPolicy.PRIVATE),
                emptyList(), flowOf(), { now }, scope = scope,
            )
            payload.copy(sig = Identity.sign(pair, probe.signedBeaconBytes(seq, sentAt, payload)), pub = pair.public.encoded)
        }
        val ct = AesGcmAead.seal(keys.trafficKey, PayloadCodec.encode(body), FrameCodec.aad(keys.networkId, id, seq, sentAt))
        return EncryptedFrame(keys.networkId, id, seq, SessionManager.INITIAL_TTL, sentAt, ct)
    }

    private suspend fun eventually(cond: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!cond()) { check(System.nanoTime() < deadline) { "timed out" }; delay(10) }
    }

    @Test
    fun `FR-5_3 a key that is not the sender's own, or no signature, is refused`() = runBlocking {
        val m = session().apply { start() }
        val victim = MemberId(Identity.memberIdOf(Identity.generate()))
        bt.deliver(beacon(Identity.generate(), victim, 1u)) // someone else's key for the victim id
        bt.deliver(beacon(Identity.generate(), MemberId(ByteArray(32) { 9 }), 1u, signed = false))
        val honest = Identity.generate()
        val honestId = MemberId(Identity.memberIdOf(honest))
        bt.deliver(beacon(honest, honestId, 1u))
        eventually { m.peerFlow.value.containsKey(honestId) }
        assertNull(m.peerFlow.value[victim])
        assertEquals(1, m.peerFlow.value.size)
        assertEquals(2, m.diagnostics.value.authFailed)
        m.stop()
    }

    @Test
    fun `FR-6_3 staleness follows the beacon interval`() = runBlocking {
        val m = session(keepAliveMs = 300_000L).apply { start() }
        val peer = Identity.generate()
        val peerId = MemberId(Identity.memberIdOf(peer))
        bt.deliver(beacon(peer, peerId, 1u))
        eventually { m.peerFlow.value[peerId]?.lastPosition != null }
        now += 4 * 60_000 // 4 min: under 3 intervals of 5 min
        m.housekeeping()
        assertNotNull(m.peerFlow.value[peerId]?.lastPosition, "still located")
        now += 12 * 60_000
        m.housekeeping()
        assertNull(m.peerFlow.value[peerId]?.lastPosition, "inactive after 3 missed keep-alives")
        m.stop()
    }

    @Test
    fun `FR-4_4 paused sharing still beacons, without my position`() = runBlocking {
        val m = session().apply { start() }
        val sent = CopyOnWriteArrayList<ByteArray>()
        val job = scope.launch { bt.sentWire.collect { sent += it } }
        delay(50)
        m.sharingPaused = true
        m.sendBeaconSigned(BeaconPayload(position = BeaconPosition(1, 2, 3, 4)))
        m.sharingPaused = false
        m.sendBeaconSigned(BeaconPayload(position = BeaconPosition(1, 2, 3, 4)))
        eventually { sent.size == 2 }
        val positions = sent.map { w ->
            val f = FrameCodec.decode(w)
            PayloadCodec.decode(AesGcmAead.open(keys.trafficKey, f.ciphertext, FrameCodec.aad(f.networkId, f.senderId, f.seq, f.sentAtMs))).position
        }
        assertEquals(listOf(false, true), positions.map { it != null })
        job.cancel()
    }

    @Test
    fun `FR-9_1 only beacons are what the messenger shares, member lists never go over LoRa`() = runBlocking {
        val m = session().apply { start() }
        val shared = CopyOnWriteArrayList<ByteArray>()
        m.onFrameSent = { shared += it }
        val loraSent = CopyOnWriteArrayList<ByteArray>()
        val job = scope.launch { lora.sentWire.collect { loraSent += it } }
        delay(50)
        m.syncBook(force = true)
        m.sendRaw(byteArrayOf(1, 2, 3)) // chat-like payload
        m.sendBeaconSigned(BeaconPayload())
        delay(200)
        assertEquals(1, shared.size, "only the beacon")
        assertEquals(2, loraSent.size, "chat + beacon on LoRa, no member list")
        job.cancel()
    }

    @Test
    fun `FR-10_5 a removed member disappears at once and its SMS reports are refused`() = runBlocking {
        val m = session().apply { start() }
        val peer = Identity.generate()
        val peerId = MemberId(Identity.memberIdOf(peer))
        bt.deliver(beacon(peer, peerId, 1u))
        eventually { m.peerFlow.value.containsKey(peerId) && book.isListed(peerId) }
        assertTrue(book.remove(peerId))
        m.forgetRemoved()
        assertNull(m.peerFlow.value[peerId])
        assertNull(m.acceptSmsReport(peerId.bytes.copyOf(8), 5u, now, null, null))
        bt.deliver(beacon(peer, peerId, 2u))
        delay(200)
        assertNull(m.peerFlow.value[peerId], "its beacons count for nothing")
        // SMS from someone on the list but never met is accepted.
        val other = Identity.generate()
        val otherId = MemberId(Identity.memberIdOf(other))
        book.add(otherId, other.public.encoded)
        assertEquals(otherId, m.acceptSmsReport(otherId.bytes.copyOf(8), 5u, now, null, null))
        assertTrue(RosterCodec.isSync(RosterCodec.encode(book.toSync())))
        m.stop()
    }
}
