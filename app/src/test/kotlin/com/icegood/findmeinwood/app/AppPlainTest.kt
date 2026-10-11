package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.storage.SecretBox
import com.icegood.findmeinwood.core.storage.SecureStorage
import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.PendingJoin
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.session.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class SessionBusTest {
    @Test
    fun `discovery channels publish and clear`() = runTest {
        val net = DiscoveredNetwork(
            NetworkId(ByteArray(8)), "hunt", MemberId(ByteArray(32)), "Misha",
            JoinPolicy.OPEN, listOf(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH), 1L,
        )
        SessionBus.publishDiscovered(listOf(net))
        assertEquals(1, SessionBus.discovered.value.size)
        val join = PendingJoin(NetworkId(ByteArray(8)), MemberId(ByteArray(32)), "Misha", 2L)
        SessionBus.publishPending(listOf(join))
        assertEquals(1, SessionBus.pendingJoins.value.size)
        SessionBus.publishOwnerSas("123456")
        assertEquals("123456", SessionBus.ownerSas.value)
        SessionBus.publishJoinOutcome(
            com.icegood.findmeinwood.core.session.JoinOutcome.Joined("net", "secret", "123456"),
        )
        assertTrue(SessionBus.joinOutcome.value is com.icegood.findmeinwood.core.session.JoinOutcome.Joined)
        SessionBus.clear()
        assertTrue(SessionBus.discovered.value.isEmpty())
        assertTrue(SessionBus.pendingJoins.value.isEmpty())
        assertEquals(null, SessionBus.ownerSas.value)
        assertEquals(null, SessionBus.joinOutcome.value)
    }

    @Test
    fun `a secret learned over the air joins the same network`() {
        val created = ProfileStore.create("wood", null) as CreateResult.Ok
        val joined = ProfileStore.join("wood", created.password) as JoinResult.Ok
        assertEquals(created.profile.networkIdHex, joined.profile.networkIdHex)
    }

    @Test
    fun `session running flag is observable`() = runTest {
        assertEquals(false, SessionBus.sessionRunning.value)
        SessionBus.setSessionRunning(true)
        assertEquals(true, SessionBus.sessionRunning.value)
        SessionBus.clear()
        assertEquals(false, SessionBus.sessionRunning.value)
    }

    @Test
    fun `incoming payload replay buffer is dropped on clear`() = runTest {
        SessionBus.publishIncoming(byteArrayOf(1), MemberId(ByteArray(32)))
        val before = SessionBus.incomingPayloads
        SessionBus.clear()
        assertTrue(before !== SessionBus.incomingPayloads)
        val seen = mutableListOf<Pair<ByteArray, MemberId>>()
        val job = launch {
            SessionBus.incomingPayloads.collect { seen += it }
        }
        delay(20)
        job.cancel()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `publish and clear roundtrip`() {
        val profile = NetworkProfile(
            name = "n", networkId = NetworkId(ByteArray(8)), trafficKey = ByteArray(32),
            myMemberId = MemberId(ByteArray(32)), ownerMemberId = MemberId(ByteArray(32)),
            policy = JoinPolicy.PRIVATE,
        )
        SessionBus.publish(profile, emptyMap(), byteArrayOf(1))
        assertEquals(profile, SessionBus.profile.value)
        assertEquals(1, SessionBus.lastWireFrame.value!!.size)
        SessionBus.publishDiagnostics(Diagnostics(sent = 5))
        assertEquals(5, SessionBus.diagnostics.value.sent)
        SessionBus.clear()
        assertNull(SessionBus.profile.value)
        assertEquals(0, SessionBus.peers.value.size)
        assertEquals(Diagnostics(), SessionBus.diagnostics.value)
    }
}

class ProfileStoreDirTest {
    init { SecureStorage.box = TestBox }

    private fun dir() = Files.createTempDirectory("fmiw").toFile()

    /** Border mock: only filesDir is answered; nothing platform-specific executes. */
    private fun ctxWithDir(d: java.io.File): android.content.Context =
        Mockito.mock(android.content.Context::class.java).also {
            Mockito.`when`(it.filesDir).thenReturn(d)
        }

    private fun stored(name: String = "hunt") = StoredProfile(
        name = name,
        networkIdHex = "aabbccddeeff0011",
        trafficKeyHex = "ab".repeat(32),
        vtagHex = "00112233",
        myMemberIdHex = "cd".repeat(32),
        ownerMemberIdHex = "cd".repeat(32),
        policy = JoinPolicy.CODE,
    )

    @Test
    fun `create derives the network from the given password`() {
        val r = ProfileStore.create("wood", " correct horse battery staple again ") as CreateResult.Ok
        assertEquals("wood", r.profile.name)
        assertEquals(64, r.profile.trafficKeyHex.length)
        assertEquals("correct horse battery staple again", r.password, "surrounding spaces are dropped")
        assertEquals(16, r.profile.networkIdHex.length) // 8 bytes as hex
    }

    @Test
    fun `create generates a 130 bit secret when omitted`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        assertEquals(26, r.password.length) // 26 chars x 5 bits = 130 bits (FR-2.3)
        assertTrue(r.password.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
    }

    @Test
    fun `create refuses weak passphrase and bad name`() {
        assertTrue(ProfileStore.create("wood", "short") is CreateResult.Error)
        assertTrue(ProfileStore.create("", null) is CreateResult.Error)
        assertTrue(ProfileStore.create("w".repeat(65), null) is CreateResult.Error)
    }

    @Test
    fun `entropy rule accepts 6 words or 16 chars`() {
        assertTrue(ProfileStore.strongEnough("one two three four five six"))
        assertTrue(ProfileStore.strongEnough("0123456789abcdef"))
        assertTrue(!ProfileStore.strongEnough("short"))
        assertTrue(!ProfileStore.strongEnough("one two three"))
    }

    @Test
    fun `join with the password derives the same network`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        val joined = ProfileStore.join("my label", r.password) as JoinResult.Ok
        assertEquals(r.profile.networkIdHex, joined.profile.networkIdHex)
        assertEquals(r.profile.trafficKeyHex, joined.profile.trafficKeyHex)
        assertEquals("my label", joined.profile.name)
        // each member keeps its own identity
        assertTrue(joined.profile.myMemberIdHex != r.profile.myMemberIdHex)
    }

    @Test
    fun `wrong password for a network heard nearby fails before any radio`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        val heard = ProfileStore.networkIdOf(r.password)
        assertTrue(ProfileStore.join("wood", r.password + "x", expected = heard) is JoinResult.Error)
        assertTrue(ProfileStore.join("wood", r.password, expected = heard) is JoinResult.Ok)
        assertTrue(ProfileStore.join("wood", "") is JoinResult.Error)
        assertTrue(ProfileStore.join("", r.password) is JoinResult.Error)
    }

    @Test
    fun `load save add remove over real files via dir api`() {
        val d = dir()
        assertEquals(0, ProfileStore.load(d).size)
        ProfileStore.add(d, stored())
        assertEquals(1, ProfileStore.load(d).size)
        ProfileStore.add(d, stored()) // same network id -> replaced, not duplicated
        assertEquals(1, ProfileStore.load(d).size)
        ProfileStore.remove(d, "aabbccddeeff0011")
        assertEquals(0, ProfileStore.load(d).size)
    }

    @Test
    fun `context delegates hit the same store`() {
        val d = dir()
        val ctx = ctxWithDir(d)
        ProfileStore.add(ctx, stored())
        assertEquals(1, ProfileStore.load(ctx).size)
        ProfileStore.remove(ctx, "aabbccddeeff0011")
        assertEquals(0, ProfileStore.load(ctx).size)
        ProfileStore.save(ctx, listOf(stored()))
        assertEquals(1, ProfileStore.load(ctx).size)
    }

    @Test
    fun `corrupt store yields empty list`() {
        val d = dir()
        d.resolve("profiles.json").writeText("{not json")
        assertEquals(0, ProfileStore.load(d).size)
    }

    @Test
    fun `toProfile maps hex fields`() {
        val p = ProfileStore.toProfile(stored())
        assertEquals("hunt", p.name)
        assertEquals(8, p.networkId.bytes.size)
        assertEquals(32, p.trafficKey.size)
        assertEquals(JoinPolicy.CODE, p.policy)
    }

    @Test
    fun `profiles are sealed on disk`() {
        val d = dir()
        ProfileStore.add(d, stored())
        val raw = d.resolve("profiles.json").readBytes()
        assertTrue(SecretBox.isSealed(raw))
        assertTrue(!raw.decodeToString().contains(stored().trafficKeyHex), "traffic key must not be readable")
    }

    @Test
    fun `plain profiles are not trusted`() {
        val d = dir()
        val plain = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoredProfile.serializer()), listOf(stored()),
        )
        d.resolve("profiles.json").writeText(plain)
        assertEquals(0, ProfileStore.load(d).size)
    }

    @Test
    fun `profiles sealed under another key read as empty`() {
        val d = dir()
        ProfileStore.add(d, stored())
        SecureStorage.box = TestBox.other()
        try {
            assertEquals(0, ProfileStore.load(d).size)
        } finally {
            SecureStorage.box = TestBox
        }
    }
}

class NetworkNameTest {
    @Test
    fun `any characters are fine in names and passwords now that there is no join code`() {
        val ok = ProfileStore.create("camp#1", "a#very#long#passphrase#here") as CreateResult.Ok
        assertEquals("camp#1", (ProfileStore.join("camp#1", ok.password) as JoinResult.Ok).profile.name)
    }
}

class IdentityHolderTest {
    init { SecureStorage.box = TestBox }

    @Test
    fun `identity survives a restart`() {
        val file = Files.createTempDirectory("id").toFile().resolve("device_identity.key")
        val first = IdentityHolder.loadOrCreate(file)
        val again = IdentityHolder.loadOrCreate(file)
        assertTrue(first.public.encoded.contentEquals(again.public.encoded))
        assertTrue(first.private.encoded.contentEquals(again.private.encoded))
        assertEquals(32, com.icegood.findmeinwood.core.crypto.Identity.memberIdOf(again).size)
    }

    @Test
    fun `corrupt identity file is replaced`() {
        val file = Files.createTempDirectory("id").toFile().resolve("device_identity.key")
        file.writeText("garbage")
        val pair = IdentityHolder.loadOrCreate(file)
        assertTrue(IdentityHolder.loadOrCreate(file).public.encoded.contentEquals(pair.public.encoded))
    }
}

class IdTokenClaimTest {
    private fun token(payloadJson: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"RS256"}""".toByteArray()) + "." +
            enc.encodeToString(payloadJson.toByteArray()) + ".sig"
    }

    @Test
    fun `extracts sub claim`() {
        assertEquals("gid-123", idTokenClaim(token("""{"sub":"gid-123","email":"a@b.c"}"""), "sub"))
    }

    @Test
    fun `missing claim returns null`() {
        assertNull(idTokenClaim(token("""{"sub":"x"}"""), "email"))
    }

    @Test
    fun `malformed token returns null`() {
        assertNull(idTokenClaim("not-a-jwt", "sub"))
    }
}

/** Software stand-in for [KeystoreBox]: same sealed format, key held in memory. */
internal object TestBox : SecretBox() {
    private val k = newKey()
    override fun key(): javax.crypto.SecretKey = k

    /** A box with a different key, like a restore onto another device. */
    fun other(): SecretBox = object : SecretBox() {
        private val k = newKey()
        override fun key(): javax.crypto.SecretKey = k
    }

    private fun newKey() = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

class SecretBoxTest {
    @Test
    fun `seal and open round trip with a fresh iv each time`() {
        val a = TestBox.seal("secret".toByteArray())
        val b = TestBox.seal("secret".toByteArray())
        assertTrue(SecretBox.isSealed(a))
        assertTrue(!a.contentEquals(b))
        assertEquals("secret", TestBox.open(a).decodeToString())
    }

    @Test
    fun `tampering or another key fails to open`() {
        val sealed = TestBox.seal("secret".toByteArray())
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(runCatching { TestBox.open(tampered) }.isFailure)
        assertTrue(runCatching { TestBox.other().open(sealed) }.isFailure)
        assertTrue(!SecretBox.isSealed("{\"plain\":true}".toByteArray()))
    }
}

class IdentityHolderSealingTest {
    init { SecureStorage.box = TestBox }

    @Test
    fun `identity file is sealed and a plain one is replaced`() {
        val file = Files.createTempDirectory("id").toFile().resolve("device_identity.key")
        val pair = IdentityHolder.loadOrCreate(file)
        assertTrue(SecretBox.isSealed(file.readBytes()))

        val enc = java.util.Base64.getEncoder()
        file.writeText(
            listOf(pair.private.algorithm, enc.encodeToString(pair.private.encoded), enc.encodeToString(pair.public.encoded))
                .joinToString("\n"),
        )
        val fresh = IdentityHolder.loadOrCreate(file)
        assertTrue(!fresh.public.encoded.contentEquals(pair.public.encoded), "unsealed key must not be used")
        assertTrue(SecretBox.isSealed(file.readBytes()))
    }
}

class LinkWatchTest {
    private fun peer(silentMs: Long, dist: Double?, via: String? = "BLUETOOTH") =
        LinkWatch.PeerSample("p1", silentMs, dist, via)

    @Test
    fun `loss reports the last distance and trend seen while the peer was heard`() {
        val w = LinkWatch(lostAfterMs = 150_000)
        var now = 0L
        listOf(20.0, 60.0, 110.0).forEach { d ->
            assertTrue(w.update(now, listOf(peer(5_000, d))).isEmpty())
            now += 10_000
        }
        // Peer goes silent; its position stays stale so no new distance arrives.
        assertTrue(w.update(now, listOf(peer(140_000, null))).isEmpty(), "not lost yet")
        val lost = w.update(now + 10_000, listOf(peer(155_000, null))).single() as LinkWatch.Event.Lost
        assertEquals(110.0, lost.lastDistanceM)
        assertEquals(listOf(20.0, 60.0, 110.0), lost.distanceTrend)
        assertEquals("BLUETOOTH", lost.lastTransport)
        // Reported once, not on every sample while silent.
        assertTrue(w.update(now + 20_000, listOf(peer(165_000, null))).isEmpty())

        val back = w.update(now + 70_000, listOf(peer(3_000, 80.0, "WIFI_DIRECT"))).single() as LinkWatch.Event.Back
        assertEquals(155_000L + 60_000L, back.gapMs)
        assertEquals("WIFI_DIRECT", back.transport)
    }

    @Test
    fun `trend keeps only the last minute`() {
        val w = LinkWatch(lostAfterMs = 30_000)
        (1..10).forEach { w.update(it * 10_000L, listOf(peer(1_000, it.toDouble()))) }
        val lost = w.update(200_000, listOf(peer(40_000, null))).single() as LinkWatch.Event.Lost
        assertEquals(listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0), lost.distanceTrend)
    }

    @Test
    fun `distance is the great-circle metres`() {
        // 0.001 deg of latitude is about 111 m.
        val d = LinkWatch.distanceM(49.0, 24.0, 49.001, 24.0)
        assertTrue(d in 110.0..112.5, "got $d")
    }
}

class SmsLogicTest {
    @Test
    fun `phone numbers are parsed from free text`() {
        assertEquals(
            listOf("+380671234567", "0501112233"),
            SmsLogic.parseNumbers("+380 67 123 45 67, 050-111-22-33;\n12; +380671234567"),
        )
    }

    @Test
    fun `sms budget caps sends per hour`() {
        val b = SmsBudget(maxPerHour = 2)
        assertTrue(b.tryTake(0))
        assertTrue(b.tryTake(1_000))
        assertTrue(!b.tryTake(2_000))
        assertTrue(b.tryTake(3_600_000), "an hour later the first send has expired")
    }
}

class UiTextTest {
    @Test
    fun `radios read as plain words, optional ones only when working`() {
        val lines = UiText.radios(
            mapOf(
                com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT to com.icegood.findmeinwood.transport.api.RadioState.LINK_DOWN,
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to com.icegood.findmeinwood.transport.api.RadioState.READY,
                com.icegood.findmeinwood.core.model.TransportId.LORA to com.icegood.findmeinwood.transport.api.RadioState.SCANNING,
                com.icegood.findmeinwood.core.model.TransportId.INTERNET to com.icegood.findmeinwood.transport.api.RadioState.STOPPED,
            ),
        )
        assertEquals(listOf("Bluetooth: connected", "Wi-Fi Direct: off or unavailable"), lines)
        assertEquals("via SMS", UiText.via(com.icegood.findmeinwood.core.model.TransportId.SMS))
        assertEquals("via other members", UiText.via(null))
        val lora = UiText.radios(
            mapOf(
                com.icegood.findmeinwood.core.model.TransportId.LORA to com.icegood.findmeinwood.transport.api.RadioState.READY,
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to com.icegood.findmeinwood.transport.api.RadioState.SCANNING,
            ),
        )
        assertEquals(listOf("Bluetooth: searching", "LoRa: connected"), lora)
        assertEquals(
            emptyList(),
            UiText.radios(mapOf(com.icegood.findmeinwood.core.model.TransportId.INTERNET to com.icegood.findmeinwood.transport.api.RadioState.READY)),
        )
    }
}
