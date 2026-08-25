package app.findmeinwood.core.session

import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.crypto.Identity
import app.findmeinwood.core.crypto.NetworkKeysFactory
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionManagerTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Member(nick: String) {
        val memberId = MemberId(Identity.memberIdOf(Identity.generate()))
        val keys = NetworkKeysFactory.derive("hunt-2026-secret".toByteArray())
        val profile = NetworkProfile(
            name = "hunt-2026",
            networkId = keys.networkId,
            trafficKey = keys.trafficKey,
            myMemberId = memberId,
            ownerMemberId = memberId, // overwritten below for non-owners
            policy = JoinPolicy.PRIVATE,
        )
    }

    private fun pump(from: FakeTransport, to: FakeTransport) {
        scope.launch { from.sentWire.collect { w -> to.deliver(FrameCodec.decode(w)) } }
    }

    @Test
    fun `end to end beacon between two members`() = runBlocking {
        val alice = Member("alice"); val bob = Member("bob")
        val tA = FakeTransport(TransportId.BLUETOOTH)
        val tB = FakeTransport(TransportId.BLUETOOTH)
        pump(tA, tB); pump(tB, tA)
        val mA = SessionManager(alice.profile, listOf(tA), flowOf(), { now }, scope = scope)
        val mB = SessionManager(bob.profile, listOf(tB), flowOf(), { now }, scope = scope)
        mA.start(); mB.start()
        try {
            mA.sendBeacon(GnssFix(51.5, -0.12, 4.5f, 120.0, now))
            withTimeout(5_000) { mB.peerFlow.first { it.containsKey(alice.memberId) } }
            val peer = mB.peerFlow.value[alice.memberId]!!
            assertEquals(515000000, peer.lastPosition!!.latE7)
            assertEquals(0u, peer.lastSeq)
        } finally { mA.stop(); mB.stop(); scope.cancel() }
    }

    @Test
    fun `relay across A-B-C chain decrements ttl`() = runBlocking {
        val a = Member("a"); val b = Member("b"); val c = Member("c")
        val aLora = FakeTransport(TransportId.LORA)
        val bLora = FakeTransport(TransportId.LORA)
        val bBt = FakeTransport(TransportId.BLUETOOTH)
        val cBt = FakeTransport(TransportId.BLUETOOTH)
        pump(aLora, bLora); pump(bLora, aLora)
        pump(bBt, cBt); pump(cBt, bBt)
        val mA = SessionManager(a.profile, listOf(aLora), flowOf(), { now }, scope = scope)
        val mB = SessionManager(b.profile, listOf(bLora, bBt), flowOf(), { now }, scope = scope)
        val mC = SessionManager(c.profile, listOf(cBt), flowOf(), { now }, scope = scope)
        mA.start(); mB.start(); mC.start()
        try {
            mA.sendBeacon(GnssFix(52.0, 1.0, 5f, 10.0, now))
            withTimeout(5_000) { mC.peerFlow.first { it.containsKey(a.memberId) } }
            val relayedAtB = bBt.sentHistory.map { FrameCodec.decode(it) }
                .first { it.senderId == a.memberId }
            assertEquals(2u, relayedAtB.ttl) // 3 decremented once by B
        } finally { mA.stop(); mB.stop(); mC.stop(); scope.cancel() }
    }

    @Test
    fun `replayed frame is dropped by receiver`() = runBlocking {
        val alice = Member("alice"); val bob = Member("bob")
        val tA = FakeTransport(TransportId.BLUETOOTH)
        val tB = FakeTransport(TransportId.BLUETOOTH)
        pump(tA, tB); pump(tB, tA)
        val mA = SessionManager(alice.profile, listOf(tA), flowOf(), { now }, scope = scope)
        val mB = SessionManager(bob.profile, listOf(tB), flowOf(), { now }, scope = scope)
        mA.start(); mB.start()
        try {
            mA.sendBeacon(null)
            withTimeout(5_000) { mB.peerFlow.first { it.containsKey(alice.memberId) } }
            val seen = mB.peerFlow.value[alice.memberId]!!.lastSeq
            val wire = tA.sentHistory.peek()!!
            tB.deliver(FrameCodec.decode(wire)) // replay
            assertEquals(seen, mB.peerFlow.value[alice.memberId]!!.lastSeq)
        } finally { mA.stop(); mB.stop(); scope.cancel() }
    }
}
