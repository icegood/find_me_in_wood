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
            JoinPolicy.PRIVATE, listOf(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH), 1L,
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
        // The join result outlives the restart onto the joined network (code still to compare).
        assertTrue(SessionBus.joinOutcome.value is com.icegood.findmeinwood.core.session.JoinOutcome.Joined)
        SessionBus.publishJoinOutcome(null)
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
        policy = JoinPolicy.PRIVATE,
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
    fun `network key epochs and the newest rekey persist with the profile`() {
        val d = dir()
        ProfileStore.add(d, stored())
        val salt = ByteArray(32) { 7 }
        ProfileStore.setEpochs(d, "aabbccddeeff0011", mapOf(2 to salt), byteArrayOf(1, 2, 3))
        val p = ProfileStore.load(d).single()
        assertTrue(ProfileStore.salts(p)[2]!!.contentEquals(salt))
        assertTrue(ProfileStore.rekey(p)!!.contentEquals(byteArrayOf(1, 2, 3)))
        ProfileStore.setEpochs(d, "aabbccddeeff0011", emptyMap(), null)
        assertEquals(null, ProfileStore.rekey(ProfileStore.load(d).single()))
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
        val me = MemberId(ByteArray(32) { 7 })
        val p = ProfileStore.toProfile(stored(), me, master = null)
        assertEquals(me, p.myMemberId)
        assertEquals(MemberId(ByteArray(32)), p.ownerMemberId, "master unknown")
        assertEquals(me, ProfileStore.toProfile(stored(), me, master = me).ownerMemberId)
        assertEquals("hunt", p.name)
        assertEquals(8, p.networkId.bytes.size)
        assertEquals(32, p.trafficKey.size)
        assertEquals(JoinPolicy.PRIVATE, p.policy)
    }

    @Test
    fun `profiles from older builds still load`() {
        val d = dir()
        val old = """[{"name":"old","networkIdHex":"aabbccddeeff0011","trafficKeyHex":"${"ab".repeat(32)}",""" +
            """"vtagHex":"00112233","myMemberIdHex":"cd","ownerMemberIdHex":"cd","policy":"PRIVATE"}]"""
        d.resolve("profiles.json").writeBytes(SecureStorage.box.seal(old.toByteArray()))
        assertEquals("old", ProfileStore.load(d).single().name)
        assertEquals("", ProfileStore.load(d).single().masterIdHex, "master unknown")
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
    fun `box key pair survives a restart and is an agreement key`() {
        val file = Files.createTempDirectory("id").toFile().resolve("device_box.key")
        val gen = { com.icegood.findmeinwood.core.crypto.EpochKeys.boxKeyPair() }
        val first = IdentityHolder.loadOrCreate(file, gen) { true }
        val again = IdentityHolder.loadOrCreate(file, gen) { true }
        assertTrue(first.public.encoded.contentEquals(again.public.encoded))
        assertEquals("EC", again.private.algorithm)
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
    private val ctx = EnglishTexts.context

    @Test
    fun `findability reads as minutes left, or members-only`() {
        assertEquals("findable by new members: 10 min left", UiText.findable(ctx, 600_000, 0))
        assertEquals("findable by new members: 1 min left", UiText.findable(ctx, 1_000, 0))
        assertTrue(UiText.findable(ctx, 0, 5).startsWith("not findable"))
        // FR-12.3: launchers that already put every app on the home screen are not asked.
        assertTrue(homeScreenNeedsOffer("com.google.android.apps.nexuslauncher"))
        assertTrue(!homeScreenNeedsOffer("com.huawei.android.launcher"))
        assertTrue(!homeScreenNeedsOffer("com.hihonor.android.launcher"))
        assertTrue(!homeScreenNeedsOffer(""))
        // FR-8.8: radios that stop discovery are named, for the master and for the joiner.
        assertNull(UiText.discoveryBlocked(ctx, bluetoothOn = true, locationOn = true, finding = true))
        assertEquals("Nobody can find this network: Bluetooth is off.", UiText.discoveryBlocked(ctx, false, true, finding = false))
        assertEquals(
            "Bluetooth and Location are off: no network nearby can show up here.",
            UiText.discoveryBlocked(ctx, false, false, finding = true),
        )
        assertEquals("Location is off: no network nearby can show up here.", UiText.discoveryBlocked(ctx, true, false, finding = true))
    }

    @Test
    fun `findable for ten minutes, then off by itself`() {
        SessionBus.setFindable(true, now = 1_000)
        assertTrue(SessionBus.isFindable(now = 1_000 + SessionBus.FINDABLE_MS - 1))
        assertTrue(!SessionBus.isFindable(now = 1_000 + SessionBus.FINDABLE_MS))
        SessionBus.setFindable(false)
        assertEquals(0L, SessionBus.findableUntil.value)
    }

    @Test
    fun `radios read as plain words, optional ones only when working`() {
        val lines = UiText.radios(ctx, 
            mapOf(
                com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT to com.icegood.findmeinwood.transport.api.RadioState.LINK_DOWN,
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to com.icegood.findmeinwood.transport.api.RadioState.READY,
                com.icegood.findmeinwood.core.model.TransportId.LORA to com.icegood.findmeinwood.transport.api.RadioState.SCANNING,
                com.icegood.findmeinwood.core.model.TransportId.INTERNET to com.icegood.findmeinwood.transport.api.RadioState.STOPPED,
            ),
        )
        assertEquals(listOf("Bluetooth: connected", "Wi-Fi Direct: off or unavailable"), lines)
        assertEquals("via SMS", UiText.via(ctx, com.icegood.findmeinwood.core.model.TransportId.SMS))
        assertEquals("via other members", UiText.via(ctx, null))
        val lora = UiText.radios(ctx, 
            mapOf(
                com.icegood.findmeinwood.core.model.TransportId.LORA to com.icegood.findmeinwood.transport.api.RadioState.READY,
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to com.icegood.findmeinwood.transport.api.RadioState.SCANNING,
            ),
        )
        assertEquals(listOf("Bluetooth: searching", "LoRa: connected"), lora)
        assertEquals(
            emptyList(),
            UiText.radios(ctx, mapOf(com.icegood.findmeinwood.core.model.TransportId.INTERNET to com.icegood.findmeinwood.transport.api.RadioState.READY)),
        )
    }

    @Test
    fun `members read as links and when they were heard`() {
        assertEquals("links not announced yet", UiText.offers(ctx, emptyList()))
        assertEquals(
            "Bluetooth · SMS +380",
            UiText.offers(ctx, 
                listOf(
                    com.icegood.findmeinwood.core.model.LinkOffer(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH),
                    com.icegood.findmeinwood.core.model.LinkOffer(com.icegood.findmeinwood.core.model.TransportId.SMS, "+380"),
                ),
            ),
        )
        assertEquals("not heard yet", UiText.heard(ctx, null, 0))
        assertEquals("heard just now", UiText.heard(ctx, 1_000, 30_000))
        assertEquals("heard 5 min ago", UiText.heard(ctx, 0, 300_000))
        assertEquals("heard 2 h ago", UiText.heard(ctx, 0, 7_200_000))
    }
}

class AuthErrorTextTest {
    @Test
    fun `every sign-in failure has its own words`() {
        val texts = com.icegood.findmeinwood.core.auth.AuthError.entries.map { authErrorText(EnglishTexts.context, it) }
        assertEquals(texts.size, texts.toSet().size)
        assertEquals("Wrong email or password", authErrorText(EnglishTexts.context, com.icegood.findmeinwood.core.auth.AuthError.INVALID_CREDENTIALS))
    }
}

class MemberNamingTest {
    private val id = com.icegood.findmeinwood.core.model.MemberId(ByteArray(32) { 7 })
    private val peer = com.icegood.findmeinwood.core.session.PeerState(id, nickname = "Iryna", lastSeenMs = 0, lastSeq = 1u)

    @Test
    fun `a label given on this phone overrides the announced name, blank removes it`() {
        val labels = MemberNaming.rename(emptyMap(), id, "  Mom ")
        assertEquals("Mom", MemberNaming.apply(mapOf(id to peer), labels)[id]?.nickname)
        val cleared = MemberNaming.rename(labels, id, " ")
        assertTrue(cleared.isEmpty())
        assertEquals("Iryna", MemberNaming.apply(mapOf(id to peer), cleared)[id]?.nickname)
        assertEquals(32, MemberNaming.rename(emptyMap(), id, "x".repeat(50)).values.single().length)
    }
}

class LinkAvailabilityTest {
    private val ctx = EnglishTexts.context

    private val all = LinkAvailability.Inputs(
        bluetoothOn = true, wifiOn = true, lora = com.icegood.findmeinwood.transport.api.RadioState.READY,
        phoneNumber = " +380001 ", smsPermitted = true, sdk = 35, onSatellite = false,
    )

    @Test
    fun `every supported link is listed, the usable ones are offered`() {
        val s = LinkAvailability.statuses(ctx, all)
        assertEquals(listOf("Bluetooth", "Wi-Fi Direct", "LoRa", "SMS", "Satellite"), s.map { it.label })
        val offers = LinkAvailability.offers(s)
        assertEquals(
            listOf(
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH,
                com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT,
                com.icegood.findmeinwood.core.model.TransportId.LORA,
                com.icegood.findmeinwood.core.model.TransportId.SMS,
            ),
            offers.map { it.t },
        )
        assertEquals("+380001", offers.last().addr)
        assertTrue(s.last().reason!!.contains("not now"), "satellite is a note, never offered")
        assertEquals("on satellite now: SMS goes through it", LinkAvailability.statuses(ctx, all.copy(onSatellite = true)).last().reason)
    }

    @Test
    fun `unusable links are kept, greyed with the reason and the fix`() {
        val s = LinkAvailability.statuses(ctx, all.copy(bluetoothOn = false, wifiOn = false, lora = null, phoneNumber = "", sdk = 29))
        assertTrue(LinkAvailability.offers(s).isEmpty())
        assertEquals(
            listOf(
                com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_BLUETOOTH,
                com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_WIFI,
                null,
                com.icegood.findmeinwood.core.model.LinkFix.SET_PHONE_NUMBER,
                null,
            ),
            s.map { it.fix },
        )
        assertEquals("no LoRa node connected", s[2].reason)
        assertEquals("not supported before Android 15", s[4].reason)
        val noPerm = LinkAvailability.statuses(ctx, all.copy(smsPermitted = false))
        assertEquals(com.icegood.findmeinwood.core.model.LinkFix.ALLOW_SMS, noPerm[3].fix)
        assertEquals("goes by SMS: set SMS up first", noPerm[4].reason)
        assertEquals(com.icegood.findmeinwood.core.model.LinkFix.ALLOW_SMS, noPerm[4].fix)
    }

    @Test
    fun `Wi-Fi Direct needs Location on up to Android 12 only`() {
        val old = LinkAvailability.statuses(ctx, all.copy(locationOn = false, sdk = 29))[1]
        assertEquals("Location is off: Wi-Fi Direct needs it on this phone", old.reason)
        assertEquals(com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_LOCATION, old.fix)
        assertEquals(null, LinkAvailability.statuses(ctx, all.copy(locationOn = false, sdk = 33))[1].reason)
    }
}

class MemberBookStoreTest {
    init { SecureStorage.box = TestBox }

    private val net = com.icegood.findmeinwood.core.model.NetworkId(ByteArray(8) { 3 })
    private val masterPair = com.icegood.findmeinwood.core.crypto.Identity.generate()
    private val masterId = MemberId(com.icegood.findmeinwood.core.crypto.Identity.memberIdOf(masterPair))
    private val aPair = com.icegood.findmeinwood.core.crypto.Identity.generate()
    private val aId = MemberId(com.icegood.findmeinwood.core.crypto.Identity.memberIdOf(aPair))

    @Test
    fun `the member list survives a restart and reads master first, removed last`() {
        val dir = Files.createTempDirectory("fmiw").toFile()
        val master = MemberBookStore.load(dir, net, masterPair, masterId)
        master.add(aId, aPair.public.encoded)
        val a = com.icegood.findmeinwood.core.session.MemberBook(net, aPair, masterId)
        a.updateMyCard("Ira", listOf(com.icegood.findmeinwood.core.model.LinkOffer(com.icegood.findmeinwood.core.model.TransportId.SMS, "+1")), 5)
        master.merge(a.toSync())
        MemberBookStore.save(dir, net, master)

        val again = MemberBookStore.load(dir, net, masterPair, masterId)
        assertEquals(setOf(masterId, aId), again.members().toSet())
        assertEquals("+1", again.card(aId)!!.offers.single().addr)
        val v = MemberBookStore.view(again)
        assertTrue(v.iAmMaster && v.masterKnown)
        assertEquals(listOf(true, false), v.members.map { it.isMaster })
        assertEquals("Ira", v.members[1].name)

        again.remove(aId)
        val after = MemberBookStore.view(again)
        assertTrue(after.members.last().removed)
        // A member that has not heard the master yet: only itself, master unknown.
        val lone = MemberBookStore.view(MemberBookStore.load(Files.createTempDirectory("fmiw").toFile(), net, aPair, null))
        assertTrue(!lone.iAmMaster && !lone.masterKnown)
        assertTrue(lone.members.single().isMe)
    }

    @Test
    fun `the master is recorded on the profile`() {
        val dir = Files.createTempDirectory("fmiw").toFile()
        val created = (ProfileStore.create("wood", null) as CreateResult.Ok).profile
        assertEquals(MASTER_ME, created.masterIdHex)
        val joined = (ProfileStore.join("wood", "correct horse battery staple again") as JoinResult.Ok).profile
        assertEquals("", joined.masterIdHex, "joined by password: not known yet")
        ProfileStore.add(dir, joined)
        ProfileStore.setMaster(dir, joined.networkIdHex, "ab".repeat(32))
        assertEquals("ab".repeat(32), ProfileStore.load(dir).single().masterIdHex)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "uk")
class TranslationTest {
    private val ctx: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    fun `texts follow the phone's language`() {
        assertEquals("видима для нових учасників: ще 10 хв", UiText.findable(ctx, 600_000, 0))
        assertEquals("Цю мережу ніхто не знайде: Bluetooth вимкнено.", UiText.discoveryBlocked(ctx, false, true, finding = false))
        assertEquals("Неправильний пароль для «ліс»", JoinResult.Error(R.string.err_wrong_password, "ліс").text(ctx))
    }
}
