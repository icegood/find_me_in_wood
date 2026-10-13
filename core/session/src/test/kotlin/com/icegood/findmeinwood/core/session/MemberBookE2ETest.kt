package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.LinkOffer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Master + member (joined by password, master unknown) + a third phone, real signed and
 * encrypted frames: the list and the cards spread through beacons alone, and a member
 * the master removes is ignored by everyone.
 */
class MemberBookE2ETest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("e2e-roster-secret".toByteArray())

    private inner class Phone(masterOf: Phone? = null, isMaster: Boolean = false) {
        val pair = Identity.generate()
        val id = MemberId(Identity.memberIdOf(pair))
        val radio = FakeTransport(TransportId.BLUETOOTH)
        val book = MemberBook(keys.networkId, pair, if (isMaster) id else masterOf?.id)
        var changes = 0
        val session = SessionManager(
            NetworkProfile("n", keys.networkId, keys.trafficKey, id, id, JoinPolicy.PRIVATE),
            listOf(radio), flowOf(), { now },
            scope = scope, keepAliveMs = 3_600_000L, signer = pair, book = book,
        ).also { it.onBookChanged = { changes++ } }
    }

    private val master = Phone(isMaster = true)
    private val a = Phone() // joined by password: learns the master from the list
    private val b = Phone()
    private val phones = listOf(master, a, b)

    init {
        // Everyone hears everyone (one radio, broadcast).
        phones.forEach { from ->
            scope.launch {
                from.radio.sentWire.collect { w ->
                    phones.filter { it !== from }.forEach { it.radio.deliver(FrameCodec.decode(w)) }
                }
            }
        }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        phones.forEach { it.session.stop() }
        scope.cancel()
    }

    private suspend fun beacons() {
        now += SessionManager.SYNC_GAP_MS
        phones.forEach { it.session.sendBeaconSigned(BeaconPayload()) }
        delay(300)
    }

    private suspend fun eventually(cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out" }
            beacons()
        }
    }

    @Test
    fun `list and cards spread through beacons, a removed member is ignored`() = runBlocking {
        phones.forEach { it.session.start() }
        a.book.updateMyCard("Ira", listOf(LinkOffer(TransportId.SMS, "+380001")), now)
        b.book.updateMyCard("Misha", emptyList(), now)

        eventually { phones.all { p -> p.book.members().toSet() == setOf(master.id, a.id, b.id) } }
        assertEquals(master.id, a.book.masterId, "learned from the master's list")
        eventually { b.book.card(a.id)?.offers?.singleOrNull()?.addr == "+380001" }
        assertEquals("Misha", master.book.card(b.id)?.name)
        assertTrue(master.changes > 0 && a.changes > 0)

        // The master drops b: the list spreads, and b's beacons count for nobody.
        assertTrue(master.book.remove(b.id))
        master.session.syncBook(force = true)
        eventually { a.book.isRemoved(b.id) }
        a.session.peerFlow.value[b.id]?.let { before ->
            val seen = before.lastSeenMs
            beacons()
            assertEquals(seen, a.session.peerFlow.value[b.id]?.lastSeenMs, "b is ignored")
        }
        assertNull(a.book.card(b.id), "its card is dropped too")
    }
}
