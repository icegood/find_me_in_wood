package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.PendingJoin
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.session.Diagnostics
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
    fun `join code is rebuilt from a secret learned over the air`() {
        val created = ProfileStore.create("wood", null) as CreateResult.Ok
        val secret = created.joinCode.split("#")[1]
        val rebuilt = ProfileStore.joinCodeOf("wood", secret)
        assertEquals(created.joinCode, rebuilt)
        assertTrue(ProfileStore.join(rebuilt) is JoinResult.Ok)
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
    fun `create derives join code with given passphrase`() {
        val r = ProfileStore.create("wood", "correct horse battery") as CreateResult.Ok
        assertEquals("wood", r.profile.name)
        assertEquals(64, r.profile.trafficKeyHex.length)
        assertTrue(r.joinCode.startsWith("wood#correct horse battery#"))
        assertEquals(3, r.joinCode.split("#").size)
        assertEquals(16, r.profile.networkIdHex.length) // 8 bytes as hex
    }

    @Test
    fun `create generates a 130 bit secret when omitted`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        val secret = r.joinCode.split("#")[1]
        assertEquals(26, secret.length) // 26 chars x 5 bits = 130 bits (FR-2.3)
        assertTrue(secret.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
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
    fun `join with correct code derives the same network`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        val joined = ProfileStore.join(r.joinCode) as JoinResult.Ok
        assertEquals(r.profile.networkIdHex, joined.profile.networkIdHex)
        assertEquals(r.profile.trafficKeyHex, joined.profile.trafficKeyHex)
        assertEquals(r.profile.vtagHex, joined.profile.vtagHex)
        // each member keeps its own identity
        assertTrue(joined.profile.myMemberIdHex != r.profile.myMemberIdHex)
    }

    @Test
    fun `join with wrong secret fails on vtag before any radio`() {
        val r = ProfileStore.create("wood", null) as CreateResult.Ok
        val parts = r.joinCode.split("#")
        val bad = "${parts[0]}#${parts[1]}x#${parts[2]}"
        assertTrue(ProfileStore.join(bad) is JoinResult.Error)
        assertTrue(ProfileStore.join("garbage") is JoinResult.Error)
        assertTrue(ProfileStore.join("a#b#nothex") is JoinResult.Error)
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
}

class IdentityHolderTest {
    @Test
    fun `member id is 32 bytes`() {
        assertEquals(32, IdentityHolder.memberId.size)
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
