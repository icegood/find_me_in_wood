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

    /** A device with no network yet: listens on a throwaway one and never advertises it. */
    private fun listener(id: MemberId, pair: java.security.KeyPair): DiscoveryManager {
        val own = NetworkKeysFactory.derive("listener-${id.bytes[0]}".toByteArray())
        return DiscoveryManager(
            profile(JoinPolicy.CODE, id).copy(networkId = own.networkId, trafficKey = own.trafficKey),
            pair, { now }, advertise = false,
        )
    }

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
        val peer = listener(peerId, peerPair)
        val frame = MgmtCodec.decode(first)
        peer.pinKey(id, pair.public)
        peer.onMgmtFrame(frame)
        val found = peer.discovered.value.single()
        assertEquals("hunt-2026", found.name)
        assertEquals(JoinPolicy.PRIVATE, found.policy)
        assertEquals(listOf(TransportId.BLUETOOTH), found.activeTransports)

        // CODE networks are never advertised
        val hidden = manager(JoinPolicy.CODE, id, pair)
        val peer2 = listener(peerId, peerPair)
        peer2.pinKey(id, pair.public)
        peer2.onMgmtFrame(MgmtCodec.decode(hidden.buildHello(emptyList())))
        assertTrue(peer2.discovered.value.isEmpty())
    }

    @Test
    fun `stale networks are pruned`() {
        val (id, pair) = member()
        val m = manager(JoinPolicy.OPEN, id, pair)
        val peer = listener(MemberId(ByteArray(32)), Identity.generate()).apply {
            pinKey(id, pair.public)
        }
        peer.onMgmtFrame(MgmtCodec.decode(m.buildHello(emptyList())))
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
        val joiner = listener(joinerId, joinerPair)

        val req = joiner.buildJoinRequest(keys.networkId, "Misha")
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(req))

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
        owner.ownerSecret = "discovery-net"
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)

        val req = joiner.buildJoinRequest(keys.networkId, "Misha")
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(req))

        val accept = owner.approve(joinerId)
        assertNotNull(accept)
        val ownerSas = owner.ownerSas.value
        assertNotNull(ownerSas)
        assertEquals(6, ownerSas.length)
        assertTrue(ownerSas.all { it.isDigit() })

        joiner.pinKey(ownerId, ownerPair.public)
        joiner.onMgmtFrame(MgmtCodec.decode(accept))

        val outcome = joiner.outcome.value
        assertTrue(outcome is JoinOutcome.Joined)
        assertEquals("discovery-net", outcome.secret)
        assertEquals(ownerSas, outcome.sas)
    }

    @Test
    fun `open join delivers the secret in clear`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair)
        owner.ownerSecret = "discovery-net"
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)

        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)))
        val accept = owner.approve(joinerId)!!
        joiner.pinKey(ownerId, ownerPair.public)
        joiner.onMgmtFrame(MgmtCodec.decode(accept))

        val outcome = joiner.outcome.value as JoinOutcome.Joined
        assertEquals("discovery-net", outcome.secret)
    }

    @Test
    fun `rejection is surfaced to the joiner`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)

        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)))
        val rej = owner.reject(joinerId, "not today")
        joiner.onMgmtFrame(MgmtCodec.decode(rej))

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
        owner.onMgmtFrame(MgmtCodec.decode(joiner2.buildJoinRequest(keys.networkId, null)))
        assertTrue(owner.pendingJoins.value.isEmpty())
    }

    @Test
    fun `frames for another network are ignored`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)
        owner.pinKey(joinerId, joinerPair.public)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(NetworkId(ByteArray(8) { 9 }), null)))
        assertTrue(owner.pendingJoins.value.isEmpty())
    }

    @Test
    fun `join accept for a network we did not ask for is ignored`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair)
        owner.ownerSecret = "attacker-network-secret"
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)

        // Owner answers a request; the joiner, however, asked to join a different network.
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)))
        val accept = owner.approve(joinerId)!!
        joiner.buildJoinRequest(NetworkId(ByteArray(8) { 9 }), null)
        joiner.onMgmtFrame(MgmtCodec.decode(accept))
        assertEquals(null, joiner.outcome.value)
    }

    @Test
    fun `approved request cannot be approved twice`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair)
        owner.ownerSecret = "discovery-net"
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)))
        assertNotNull(owner.approve(joinerId))
        assertEquals(null, owner.approve(joinerId))
    }

    @Test
    fun `unsigned frames are dropped even with no key pinned`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)
        val signed = MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null))
        owner.onMgmtFrame(signed.copy(signature = ByteArray(0)))
        assertTrue(owner.pendingJoins.value.isEmpty(), "empty signature must not count as valid")
        owner.onMgmtFrame(signed.copy(senderKey = ByteArray(0)))
        assertTrue(owner.pendingJoins.value.isEmpty(), "frame without a sender key must be dropped")
        owner.onMgmtFrame(signed)
        assertEquals(joinerId, owner.pendingJoins.value.single().joinerId)
    }

    @Test
    fun `the first key seen is pinned and a replacement key is refused`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (joinerId, joinerPair) = member()
        owner.onMgmtFrame(MgmtCodec.decode(manager(JoinPolicy.PRIVATE, joinerId, joinerPair).buildJoinRequest(keys.networkId, "real")))
        // Same member id, different device key: a validly self-signed frame that must not verify.
        val spoof = manager(JoinPolicy.PRIVATE, joinerId, Identity.generate())
        owner.onMgmtFrame(MgmtCodec.decode(spoof.buildJoinRequest(keys.networkId, "spoof")))
        assertEquals("real", owner.pendingJoins.value.single().joinerNick)
    }

    @Test
    fun `join answers are only accepted from devices advertising the network`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair).apply { ownerSecret = "discovery-net" }
        val (rogueId, roguePair) = member()
        val rogue = DiscoveryManager(
            profile(JoinPolicy.OPEN, rogueId).copy(ownerMemberId = rogueId),
            roguePair, { now },
        ).apply { ownerSecret = "rogue-secret" }
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)

        joiner.onMgmtFrame(MgmtCodec.decode(owner.buildHello(emptyList())))
        val req = MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null))
        owner.onMgmtFrame(req)
        rogue.onMgmtFrame(req)

        joiner.onMgmtFrame(MgmtCodec.decode(rogue.approve(joinerId)!!))
        assertNull(joiner.outcome.value, "a reply from a device that never advertised must be ignored")
        joiner.onMgmtFrame(MgmtCodec.decode(rogue.reject(joinerId)))
        assertNull(joiner.outcome.value, "a reject from a device that never advertised must be ignored")

        joiner.onMgmtFrame(MgmtCodec.decode(owner.approve(joinerId)!!))
        assertEquals("discovery-net", (joiner.outcome.value as JoinOutcome.Joined).secret)
    }

    @Test
    fun `any advertising member may answer, not only the owner`() {
        val (ownerId, ownerPair) = member()
        val owner = manager(JoinPolicy.PRIVATE, ownerId, ownerPair)
        val (memberId, memberPair) = member()
        // A member that joined earlier holds the secret and advertises the same network.
        val other = manager(JoinPolicy.PRIVATE, memberId, memberPair).apply { ownerSecret = "discovery-net" }
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)
        joiner.onMgmtFrame(MgmtCodec.decode(owner.buildHello(emptyList())))
        joiner.onMgmtFrame(MgmtCodec.decode(other.buildHello(emptyList())))

        other.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, "Misha")))
        joiner.onMgmtFrame(MgmtCodec.decode(other.approve(joinerId)!!))
        assertEquals("discovery-net", (joiner.outcome.value as JoinOutcome.Joined).secret)
    }

    @Test
    fun `own network is not listed and a listener never advertises`() {
        val (aId, aPair) = member()
        val (bId, bPair) = member()
        val b = manager(JoinPolicy.PRIVATE, bId, bPair)
        b.onMgmtFrame(MgmtCodec.decode(manager(JoinPolicy.PRIVATE, aId, aPair).buildHello(emptyList())))
        assertTrue(b.discovered.value.isEmpty())
        assertFalse(listener(aId, aPair).helloDue())
    }

    @Test
    fun `accept carrying a secret for another network is refused`() {
        val (ownerId, ownerPair) = member()
        // Answers for keys.networkId but hands out a secret that derives a different network.
        val owner = manager(JoinPolicy.OPEN, ownerId, ownerPair).apply { ownerSecret = "not-this-network" }
        val (joinerId, joinerPair) = member()
        val joiner = listener(joinerId, joinerPair)
        owner.onMgmtFrame(MgmtCodec.decode(joiner.buildJoinRequest(keys.networkId, null)))
        joiner.onMgmtFrame(MgmtCodec.decode(owner.approve(joinerId)!!))
        assertEquals(null, joiner.outcome.value)
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
