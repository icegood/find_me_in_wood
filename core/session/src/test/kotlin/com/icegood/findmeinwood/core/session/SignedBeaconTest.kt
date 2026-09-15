package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignedBeaconTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000L
    private val keys = NetworkKeysFactory.derive("signed-net".toByteArray())
    private val mePair = Identity.generate()
    private val me = MemberId(Identity.memberIdOf(mePair))
    private val profile = NetworkProfile(
        name = "net", networkId = keys.networkId, trafficKey = keys.trafficKey,
        myMemberId = me, ownerMemberId = me, policy = JoinPolicy.PRIVATE,
    )
    private val tx = FakeTransport(TransportId.BLUETOOTH)

    private fun manager(signer: java.security.KeyPair?) = SessionManager(
        profile, listOf(tx), flowOf(), { now }, scope = scope, signer = signer,
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `signed beacon is accepted when the key is pinned from the payload`() = runBlocking {
        val m = manager(mePair)
        m.start()
        try {
            m.sendBeaconSigned(BeaconPayload())
            delay(100)
            val wire = tx.sentHistory.first()
            // a fresh peer receives it: pins the public key and accepts
            val peer = SessionManager(
                profile.copy(myMemberId = MemberId(ByteArray(32) { 1 })), listOf(tx), flowOf(),
                { now }, scope = scope,
            )
            peer.start()
            tx.deliverWire(wire)
            delay(200)
            assertTrue(m.diagnostics.value.sent >= 1)
            assertEquals(1, peer.diagnostics.value.received)
            assertEquals(0, peer.diagnostics.value.authFailed)
            peer.stop()
        } finally { m.stop() }
    }

    @Test
    fun `beacon with a broken signature counts as auth failure`() = runBlocking {
        val m = manager(mePair)
        m.start()
        try {
            m.sendBeaconSigned(BeaconPayload())
            delay(100)
            val wire = tx.sentHistory.first()
            // flip a byte inside the ciphertext -> AEAD open fails
            val broken = wire.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
            val peer = SessionManager(
                profile.copy(myMemberId = MemberId(ByteArray(32) { 2 })), listOf(tx), flowOf(),
                { now }, scope = scope,
            )
            peer.start()
            tx.deliverWire(broken)
            delay(200)
            assertTrue(peer.diagnostics.value.authFailed >= 1)
            assertEquals(0, peer.diagnostics.value.received)
            peer.stop()
        } finally { m.stop() }
    }

    @Test
    fun `unsigned session still works when no signer is configured`() = runBlocking {
        val m = manager(null)
        m.start()
        try {
            m.sendBeaconSigned(BeaconPayload())
            delay(100)
            assertEquals(1, m.diagnostics.value.sent)
            assertTrue(tx.sentHistory.isNotEmpty())
        } finally { m.stop() }
    }

    @Test
    fun `management frames are sent raw on all transports`() = runBlocking {
        val m = manager(mePair)
        m.start()
        try {
            m.sendMgmt(byteArrayOf(0x46, 0x4D, 2, 1))
            delay(100)
            assertTrue(tx.sentHistory.any { it.size == 4 && it[0] == 0x46.toByte() })
        } finally { m.stop() }
    }
}
