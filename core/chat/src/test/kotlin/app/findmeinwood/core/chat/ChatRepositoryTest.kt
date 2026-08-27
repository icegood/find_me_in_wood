package app.findmeinwood.core.chat

import android.content.ContentValues
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], instrumentedPackages = ["androidx.sqlite"])
class ChatRepositoryTest {

    private lateinit var repo: ChatRepository
    private lateinit var testMemberId: MemberId
    private val scope = CoroutineScope(Dispatchers.Default)

    private class FakeTransport : Transport {
        override val id = TransportId.BLUETOOTH
        var lastWire: ByteArray? = null
        override fun start(config: SessionConfig): Flow<TransportEvent> = flowOf()
        override suspend fun stop() {}
        override suspend fun send(wireFrame: ByteArray): SendResult {
            lastWire = wireFrame
            return SendResult.Sent
        }
    }

    private class FakeSessionManager(
        private val onSend: (ByteArray) -> Unit = {},
    ) {
        var sentPayloads = mutableListOf<ByteArray>()
        suspend fun sendRaw(payload: ByteArray) {
            sentPayloads.add(payload)
            onSend(payload)
        }
    }

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val fakeTransport = FakeTransport()
        val memberId = MemberId(
            app.findmeinwood.core.crypto.Identity.memberIdOf(
                app.findmeinwood.core.crypto.Identity.generate()
            )
        )
        val keys = app.findmeinwood.core.crypto.NetworkKeysFactory.derive("chat-test-secret".toByteArray())
        val profile = NetworkProfile(
            name = "test",
            networkId = keys.networkId,
            trafficKey = keys.trafficKey,
            myMemberId = memberId,
            ownerMemberId = memberId,
            policy = JoinPolicy.PRIVATE,
        )
        testMemberId = memberId
        val sessionManager = app.findmeinwood.core.session.SessionManager(
            profile = profile,
            transports = listOf(fakeTransport),
            fixes = flowOf(),
            clock = { System.currentTimeMillis() },
            scope = scope,
        )
        repo = ChatRepository(ctx, sessionManager, memberId, scope)
    }

    @Test
    fun `sendText stores message locally`() = runTest {
        repo.sendText("hello world")
        val messages = repo.getMessages(repo.groupChannelId)
        assertEquals(1, messages.size)
        assertEquals("hello world", messages[0].text)
        assertTrue(messages[0].isOwn)
    }

    @Test
    fun `sendPhoto stores message with photoRef`() = runTest {
        repo.sendPhoto("content://photo/test.jpg")
        val messages = repo.getMessages(repo.groupChannelId)
        assertEquals(1, messages.size)
        assertEquals("content://photo/test.jpg", messages[0].photoRef)
        assertTrue(messages[0].isOwn)
    }

    @Test
    fun `handleIncomingChatPayload stores remote message`() = runTest {
        val payload = app.findmeinwood.core.model.ChatPayload(
            id = "remote-1",
            senderName = "Alice",
            text = "hi from mesh",
            timestamp = 1000L,
        )
        repo.handleIncomingChatPayload(payload, testMemberId)
        kotlinx.coroutines.delay(100)
        val messages = repo.getMessages(repo.groupChannelId)
        assertEquals(1, messages.size)
        assertEquals("hi from mesh", messages[0].text)
        assertEquals("Alice", messages[0].senderName)
        assertTrue(!messages[0].isOwn)
    }

    @Test
    fun `handleIncomingPayload decodes and stores chat message`() = runTest {
        val payload = app.findmeinwood.core.model.ChatPayload(
            id = "raw-1",
            senderName = "Bob",
            text = "raw payload",
            timestamp = 2000L,
        )
        val bytes = app.findmeinwood.core.model.ChatPayloadCodec.encode(payload)
        repo.handleIncomingPayload(bytes, testMemberId)
        kotlinx.coroutines.delay(100)
        val messages = repo.getMessages(repo.groupChannelId)
        assertEquals(1, messages.size)
        assertEquals("raw payload", messages[0].text)
    }

    @Test
    fun `handleIncomingPayload ignores non-chat payload`() = runTest {
        val beaconBytes = app.findmeinwood.core.model.PayloadCodec.encode(
            app.findmeinwood.core.model.BeaconPayload()
        )
        repo.handleIncomingPayload(beaconBytes, testMemberId)
        kotlinx.coroutines.delay(100)
        val messages = repo.getMessages(repo.groupChannelId)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `getChannelIds returns group channel after messages`() = runTest {
        repo.sendText("test")
        val channels = repo.getChannelIds()
        assertTrue(channels.contains("group"))
    }

    @Test
    fun `observeChannelIds emits group channel after message`() = runTest {
        repo.sendText("test")
        val channels = repo.observeChannelIds().first()
        assertTrue(channels.contains("group"))
    }

    @Test
    fun `getLatestMessage returns most recent`() = runTest {
        repo.sendText("first")
        repo.sendText("second")
        val latest = repo.getLatestMessage(repo.groupChannelId)
        assertNotNull(latest)
        assertEquals("second", latest.text)
    }
}
