package app.findmeinwood.core.session

import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.crypto.Identity
import app.findmeinwood.core.crypto.NetworkKeysFactory
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionDiagnosticsTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("s".toByteArray())

    private class Member(nick: String) {
        val memberId = MemberId(Identity.memberIdOf(Identity.generate()))
        val keys = NetworkKeysFactory.derive("hunt-2026-secret".toByteArray())
        val profile = NetworkProfile(
            name = "n", networkId = keys.networkId, trafficKey = keys.trafficKey,
            myMemberId = memberId, ownerMemberId = memberId, policy = JoinPolicy.PRIVATE,
        )
    }

    private fun pump(from: FakeTransport, to: FakeTransport) {
        scope.launch { from.sentWire.collect { w -> to.deliver(FrameCodec.decode(w)) } }
    }

    @Test
    fun `diagnostics counters track send receive relay authfail`() = runBlocking {
        val a = Member("a"); val b = Member("b"); val c = Member("c")
        val aL = FakeTransport(TransportId.LORA)
        val bL = FakeTransport(TransportId.LORA)
        val bB = FakeTransport(TransportId.BLUETOOTH)
        val cB = FakeTransport(TransportId.BLUETOOTH)
        pump(aL, bL); pump(bL, aL); pump(bB, cB); pump(cB, bB)

        val edgesB = EdgeManager(b.profile.myMemberId, { now })
        val mA = SessionManager(a.profile, listOf(aL), flowOf(), { now }, scope = scope)
        val mB = SessionManager(
            b.profile, listOf(bL, bB), flowOf(), { now },
            edgeManager = edgesB, scope = scope,
        )
        val mC = SessionManager(c.profile, listOf(cB), flowOf(), { now }, scope = scope)
        mA.start(); mB.start(); mC.start()
        try {
            mA.sendBeacon(GnssFix(1.0, 2.0, 3f, 4.0, now))
            withTimeout(5_000) { mC.peerFlow.first { it.containsKey(a.memberId) } }

            // A sent 1; B received 1 + relayed 1 (ttl 3->2); C received 1
            assertEquals(1, mA.diagnostics.value.sent)
            assertEquals(1, mB.diagnostics.value.received)
            assertEquals(1, mB.diagnostics.value.relayed)
            assertEquals(1, mC.diagnostics.value.received)
            assertTrue(mB.diagnostics.value.authFailed == 0)

            // tampered frame -> authFailed on B
            val badWire = aL.sentHistory.peek()!!.clone().also { it[it.size - 1] = it.last().inc() }
            mB.acceptExternalWire(badWire, TransportId.LORA)
            assertEquals(1, mB.diagnostics.value.authFailed)

            // edge manager saw A on LORA
            assertEquals(
                TransportId.LORA,
                edgesB.all().first { it.peerId == a.memberId }.transport,
            )
        } finally { mA.stop(); mB.stop(); mC.stop(); scope.cancel() }
    }
}
