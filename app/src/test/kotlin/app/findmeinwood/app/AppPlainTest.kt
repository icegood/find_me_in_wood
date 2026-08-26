package app.findmeinwood.app

import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.session.Diagnostics
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class SessionBusTest {
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
        val (sp, code) = ProfileStore.create("wood", "secret")
        assertEquals("wood", sp.name)
        assertEquals(64, sp.trafficKeyHex.length)
        assertTrue(code.startsWith("wood#secret#"))
        assertEquals(3, code.split("#").size)
        assertEquals(16, sp.networkIdHex.length) // 8 bytes as hex
    }

    @Test
    fun `create generates four-word passphrase when omitted`() {
        val (_, code) = ProfileStore.create("wood", null)
        val secret = code.split("#")[1]
        assertEquals(20, secret.length) // 4 words x 5 chars, no-ambiguity alphabet
        assertTrue(secret.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
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
