package app.findmeinwood.core.chat

import android.content.Context
import app.findmeinwood.core.model.ChatPayload
import app.findmeinwood.core.model.ChatPayloadCodec
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.PayloadCodec
import app.findmeinwood.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val myMemberId: MemberId,
    private val scope: CoroutineScope,
) {
    private val store by lazy { ChatStore(context) }

    val groupChannelId: String = "group"

    fun observeMessages(channelId: String): Flow<List<ChatMessage>> =
        store.observe(channelId)

    fun getMessages(channelId: String): List<ChatMessage> =
        store.getMessages(channelId)

    fun getChannelIds(): List<String> =
        store.getChannelIds()

    fun getLatestMessage(channelId: String): ChatMessage? =
        store.getLatestMessage(channelId)

    suspend fun sendText(text: String, channelId: String = groupChannelId) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            channelId = channelId,
            senderId = myMemberId.bytes.joinToString("") { "%02x".format(it) },
            senderName = "me",
            text = text,
            timestamp = System.currentTimeMillis(),
            isOwn = true,
        )
        store.insert(msg)

        val payload = ChatPayload(
            id = msg.id,
            senderName = msg.senderName,
            text = msg.text,
            photoRef = msg.photoRef,
            timestamp = msg.timestamp,
        )
        sessionManager.sendRaw(ChatPayloadCodec.encode(payload))
    }

    suspend fun sendPhoto(photoUri: String, channelId: String = groupChannelId) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            channelId = channelId,
            senderId = myMemberId.bytes.joinToString("") { "%02x".format(it) },
            senderName = "me",
            text = "",
            photoRef = photoUri,
            timestamp = System.currentTimeMillis(),
            isOwn = true,
        )
        store.insert(msg)

        val payload = ChatPayload(
            id = msg.id,
            senderName = msg.senderName,
            text = msg.text,
            photoRef = msg.photoRef,
            timestamp = msg.timestamp,
        )
        sessionManager.sendRaw(ChatPayloadCodec.encode(payload))
    }

    fun handleIncomingChatPayload(payload: ChatPayload, senderId: MemberId) {
        val msg = ChatMessage(
            id = payload.id,
            channelId = groupChannelId,
            senderId = senderId.bytes.joinToString("") { "%02x".format(it) },
            senderName = payload.senderName,
            text = payload.text,
            photoRef = payload.photoRef,
            timestamp = payload.timestamp,
            isOwn = false,
        )
        scope.launch {
            store.insert(msg)
        }
    }

    fun handleIncomingPayload(rawPayload: ByteArray, senderId: MemberId) {
        val chat = PayloadCodec.tryDecodeChat(rawPayload) ?: return
        handleIncomingChatPayload(chat, senderId)
    }
}
