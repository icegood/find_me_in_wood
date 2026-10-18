package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.EpochKeys
import com.icegood.findmeinwood.core.crypto.FrameCodec
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Removing a member re-salts the network key, end to end with real signed and encrypted
 * frames: the remaining members move to the new key, the removed one cannot, a member out
 * of range at the time catches up when heard again, and one who joins by password later
 * (its box key unknown to the master) is sealed in by a new revision.
 */
class RekeyE2ETest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = NetworkKeysFactory.derive("e2e-rekey-secret".toByteArray())

    private inner class Phone(name: String, isMaster: Boolean = false, master: Phone? = null) {
        val pair = Identity.generate()
        val id = MemberId(Identity.memberIdOf(pair))
        val boxPair = EpochKeys.boxKeyPair()
        val radio = FakeTransport(TransportId.BLUETOOTH)
        @Volatile var online = true
        val book = MemberBook(net.networkId, pair, if (isMaster) id else master?.id, myBox = boxPair.public.encoded).apply {
            updateMyCard(name, emptyList(), now)
        }
        val session = SessionManager(
            NetworkProfile(name, net.networkId, net.trafficKey, id, id, JoinPolicy.PRIVATE),
            listOf(radio), flowOf(), { now },
            scope = scope, keepAliveMs = 3_600_000L, signer = pair, book = book, requireSignatures = true,
            box = boxPair, catchUpGapMs = 0,
        )

        fun heard(other: Phone) = session.peerFlow.value[other.id]?.lastSeenMs
    }

    private val master = Phone("M", isMaster = true)
    private val a = Phone("A")
    private val b = Phone("B")
    private val c = Phone("C")
    private val phones = mutableListOf(master, a, b, c)

    private fun wire(from: Phone) {
        scope.launch {
            from.radio.sentWire.collect { w ->
                if (from.online) phones.filter { it !== from && it.online }.forEach { it.radio.deliver(FrameCodec.decode(w)) }
            }
        }
    }

    init {
        phones.forEach { wire(it) }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        phones.forEach { it.session.stop() }
        scope.cancel()
    }

    private suspend fun beacons() {
        now += SessionManager.SYNC_GAP_MS
        phones.filter { it.online }.forEach { it.session.sendBeaconSigned(BeaconPayload()) }
        delay(150)
    }

    private suspend fun eventually(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out: $what" }
            beacons()
        }
    }

    @Test
    fun `removing a member re-salts the key - the rest follow, the removed one cannot`() = runBlocking {
        phones.forEach { it.session.start() }
        eventually("everyone listed, cards with box keys at the master") {
            listOf(a, b, c).all { master.book.boxOf(it.id) != null } &&
                phones.all { p -> p.book.members().toSet() == setOf(master.id, a.id, b.id, c.id) }
        }

        // C is out of range when the master removes B.
        c.online = false
        assertTrue(master.book.remove(b.id))
        assertTrue(master.session.rekey())
        assertEquals(1, master.session.keys.epoch)
        eventually("A on the new key") { a.session.keys.epoch == 1 }
        assertTrue(a.book.isRemoved(b.id), "the rekey carries the list without B")
        assertEquals(0, b.session.keys.epoch, "B was not sealed in")

        // A hears the master under the new key; B hears nothing of the network any more.
        val bHeard = b.heard(master)
        val aHeard = a.heard(master)
        repeat(2) { beacons() }
        assertTrue(a.heard(master)!! > aHeard!!)
        assertEquals(bHeard, b.heard(master), "B cannot read the new key")

        // C comes back: heard on the old key, it is handed the rekey and follows.
        c.online = true
        eventually("C caught up") { c.session.keys.epoch == 1 }
        eventually("C heard on the new key") { (master.heard(c) ?: 0) > 0 && (c.heard(a) ?: 0) > 0 }

        // D joins by password after the rekey: the master lists it, gets its card (box key)
        // and seals the same key to it in a new revision.
        val d = Phone("D")
        phones += d
        wire(d)
        d.session.start()
        eventually("D sealed in") { d.session.keys.epoch == 1 }
        assertEquals(master.id, d.book.masterId, "learned the master from the rekey's list")
        assertTrue(master.session.latestRekey!!.rev >= 2)
        assertEquals(master.session.keys.current().toList(), d.session.keys.current().toList())

        repeat(3) { beacons() }
        assertEquals(0, b.session.keys.epoch, "B never gets the new key")
    }

    @Test
    fun `a rekey not signed by the master, or for another network, is ignored`() = runBlocking {
        phones.forEach { it.session.start() }
        eventually("everyone listed") { phones.all { p -> p.book.members().size == 4 } && c.book.masterId == master.id }
        // A (a member, not the master) tries to move C to a key of its own.
        val salt = EpochKeys.newSalt()
        val eph = EpochKeys.boxKeyPair()
        fun forged(netBytes: ByteArray, signer: java.security.KeyPair): ByteArray {
            val aad = netBytes + byteArrayOf(0, 0, 0, 5) + c.id.bytes
            val r = com.icegood.findmeinwood.core.model.Rekey(
                netBytes, 5, 1, eph.public.encoded,
                listOf(com.icegood.findmeinwood.core.model.SaltWrap(c.id.bytes, EpochKeys.seal(salt, eph, c.boxPair.public.encoded, aad))),
            )
            val sig = Identity.sign(signer, com.icegood.findmeinwood.core.model.RekeyCodec.signedBytes(r))!!
            return com.icegood.findmeinwood.core.model.RekeyCodec.encode(r.copy(sig = sig))
        }
        a.session.sendRaw(forged(net.networkId.bytes, a.pair))
        a.session.sendRaw(forged(ByteArray(8) { 1 }, master.pair)) // signed by the master, another network
        repeat(2) { beacons() }
        assertEquals(0, c.session.keys.epoch)
    }
}

