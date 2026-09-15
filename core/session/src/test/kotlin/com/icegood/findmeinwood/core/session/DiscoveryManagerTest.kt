package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.JoinCrypto
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryManagerTest {

    private var now = 1_000_000L
    private val keys = NetworkKeysFactory.derive("discovery-net".toByteArray())

    private fun profile(policy: JoinPolicy, id: MemberId) = NetworkProfile(
        name = "hunt-2026",
        networkId = keys.networkId,
        trafficKey = keys.trafficKey,
        myMemberId = id,
        ownerMemberId = id,
        policy = policy,
    )

    private fun member(): Pair<MemberId, java.security.KeyPair> {
        val pair = Identity.generate()
        return MemberId(Identity.memberIdOf(pair)) to pair
    }

    private fun manager(policy: JoinPolicy, id: MemberId, pair: java.security.KeyPair) =
        DiscoveryManager(profile(policy, id), pair, { now }, helloIntervalMs = 15_000)

    @Test
    fun `hello is emitted only when due and hides CODE networks`() {
        val (id, pair) = member()
        val m = manager(JoinPolicy.PRIVATE, id, pair)
        assertTrue(m.helloDue())
        val first = m.buildHello(listOf(TransportId.BLUETOOTH))
        assertFalse(m.helloDue())
        now += 20_000
        assertTrue(m.helloDue())

        // peer receives it and lists the network
        val (peerId, peerPair) = member()
        val peer = manager(JoinPolicy.PRIVATE, peerId, peerPair)
        val frame = MgmtCodec.decode(first)
        peer.pinKey(id, pair.public)
        peer.onMgmtFrame(frame, pair.public)
        val found = peer.discovered.value.single()
        assertEquals("hunt-2026", found.name)
        assertEquals(JoinPolicy.PRIVATE, found.policy)
        assertEquals(listOf(TransportId.BLUETOOTH), found.activeTransports)

        // CODE networks are never advertised
        val hidden = manager(JoinPolicy.CODE, id, pair)
        val peer2 = manager(JoinPolicy.PRIVATE, peerId, peerPair)
        peer2.pinKey(id, pair.public)
        peer2.onMgmtFrame(MgmtCodec.decode(hidden.buildHello(emptyList())), pair.public)
        assertTrue(peer2.discovered.value.isEmpty())
    }

    @Test
    fun `stale networks are pruned`() {
        val (id, pair) = member()
        val m = manager(JoinPolicy.OPEN, id, pair)
        val peer = manager(JoinPolicy.OPEN, MemberId(ByteArray(32)), Identity.generate()).apply {
            pinKey(id, pair.public)
        }
        peer.onMgmtFrame(MgmtCodec.decode(m.buildHello(emptyList())), pair.public)
        assertEquals(1, peer.discovered.value.size)
        now += 120_000
        peer.pruneStale()
        assertTrue(peer.discovered.value.isEmpty())
    }

    @Test
    fun `join request reaches the owner as a pending join`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = manager(JoinPolicy.PRIVATE, joinerId, joinerPair)

        val req = joiner.buildJoinRequest(keys.networkId, "Misha")
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(req), joinerPair.public)

        val pending = owner.pendingJoins.value.single()
        assertEquals(joinerId, pending.joinerId)
        assertEquals("Misha", pending.joinerNick)
        // FR-8.2: no member counts are ever exposed
        assertNull(owner.discovered.value.firstOrNull()?.name)
    }

    @Test
    fun `private join delivers the secret with a matching sas`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        owner.ownerSecret = "correct horse battery staple"
        val (joinerId, joinerPair) = member()
        val joiner = manager(JoinPolicy.PRIVATE, joinerId, joinerPair)

        val req = joiner.buildJoinRequest(keys.networkId, "Misha")
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(req), joinerPair.public)

        val accept = owner.approve(joinerId)
        assertNotNull(accept)
        val ownerSas = owner.ownerSas.value
        assertNotNull(ownerSas)
        assertEquals(6, ownerSas.length)
        assertTrue(ownerSas.all { it.isDigit() })

        joiner.pinKey(ownerId, ownerPair.public)
        joiner.onMgmtFrame(MgmtCodec.decode(accept), ownerPair.public)

        val outcome = joiner.outcome.value
        assertTrue(outcome is JoinOutcome.Joined)
        assertEquals("correct horse battery staple", outcome.secret)
        assertEquals(ownerSas, outcome.sas)
    }

    @Test
    fun `open join delivers the secret in clear`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair)
        owner.ownerSecret = "open-secret-123456"
        val (joinerId, joinerPair) = member()
        val joiner = manager(JoinPolicy.OPEN, joinerId, joinerPair)

        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)), joinerPair.public)
        val accept = owner.approve(joinerId)!!
        joiner.pinKey(ownerId, ownerPair.public)
        joiner.onMgmtFrame(MgmtCodec.decode(accept), ownerPair.public)

        val outcome = joiner.outcome.value as JoinOutcome.Joined
        assertEquals("open-secret-123456", outcome.secret)
    }

    @Test
    fun `rejection is surfaced to the joiner`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = manager(JoinPolicy.PRIVATE, joinerId, joinerPair)

        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)), joinerPair.public)
        val rej = owner.reject(joinerId, "not today")
        joiner.onMgmtFrame(MgmtCodec.decode(rej), ownerPair.public)

        val outcome = joiner.outcome.value
        assertTrue(outcome is JoinOutcome.Rejected)
        assertEquals("not today", outcome.reason)
    }

    @Test
    fun `frames with a mismatched pinned key are dropped`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val impostor = Identity.generate()

        owner.pinKey(joinerId, impostor.public) // pinned to the wrong key
        val joiner2 = manager(JoinPolicy.PRIVATE, joinerId, joinerPair)
        owner.onMgmtFrame(MgmtCodec.decode(joiner2.buildJoinRequest(keys.networkId, null)), joinerPair.public)
        assertTrue(owner.pendingJoins.value.isEmpty())
    }

    @Test
    fun `frames for another network are ignored`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = manager(JoinPolicy.PRIVATE, joinerId, joinerPair)
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(NetworkId(ByteArray(8) { 9 }), null)), joinerPair.public)
        assertTrue(owner.pendingJoins.value.isEmpty())
    }

    @Test
    fun `ecdh is symmetric and sas is stable for both sides`() {
        val a = JoinCrypto.ephemeralKeyPair()
        val b = JoinCrypto.ephemeralKeyPair()
        val s1 = JoinCrypto.sharedSecret(a, b.public.encoded)
        val s2 = JoinCrypto.sharedSecret(b, a.public.encoded)
        assertTrue(s1.contentEquals(s2))
        assertEquals(JoinCrypto.sas(s1), JoinCrypto.sas(s2))
    }
}
