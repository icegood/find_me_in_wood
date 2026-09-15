package com.icegood.findmeinwood.core.chat

import android.content.Context
import com.icegood.findmeinwood.core.model.ChatPayload
import com.icegood.findmeinwood.core.model.ChatPayloadCodec
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.PayloadCodec
import com.icegood.findmeinwood.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** The group channel exists as soon as a session runs, not only after a message. */
    fun observeChannelIds(): Flow<List<String>> =
        store.observeChannelIds().map { withGroup(it) }

    fun getMessages(channelId: String): List<ChatMessage> =
        store.getMessages(channelId)

    fun getChannelIds(): List<String> = withGroup(store.getChannelIds())

    private fun withGroup(ids: List<String>): List<String> =
        if (ids.contains(groupChannelId)) ids else listOf(groupChannelId) + ids

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
        val bytes = withContext(Dispatchers.IO) { encodePhotoForMesh(photoUri) }
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
            photoBytes = bytes,
            timestamp = msg.timestamp,
        )
        sessionManager.sendRaw(ChatPayloadCodec.encode(payload))
    }

    fun handleIncomingChatPayload(payload: ChatPayload, senderId: MemberId) {
        val ref = payload.photoBytes?.let { saveIncomingPhoto(payload.id, it) } ?: payload.photoRef
        val msg = ChatMessage(
            id = payload.id,
            channelId = groupChannelId,
            senderId = senderId.bytes.joinToString("") { "%02x".format(it) },
            senderName = payload.senderName,
            text = payload.text,
            photoRef = ref,
            timestamp = payload.timestamp,
            isOwn = false,
        )
        scope.launch {
            store.insert(msg)
        }
    }

    /** Store the photo we received so the bubble can render it locally. */
    private fun saveIncomingPhoto(id: String, bytes: ByteArray): String? = runCatching {
        val dir = File(context.filesDir, "chat_photos").apply { mkdirs() }
        val file = File(dir, "in_${id.filter { it.isLetterOrDigit() }}.jpg")
        file.writeBytes(bytes)
        "file://${file.absolutePath}"
    }.getOrNull()

    /**
     * Mesh frames are small (BLE chunks every frame, 65 KB cap on Wi-Fi Direct), so a
     * picked photo is downscaled and compressed until it fits [maxBytes].
     */
    private fun encodePhotoForMesh(uriString: String, maxBytes: Int = 40_000): ByteArray? {
        val uri = android.net.Uri.parse(uriString)
        val src = runCatching {
            if (uri.scheme == "file") {
                android.graphics.BitmapFactory.decodeFile(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }
        }.getOrNull() ?: return null
        var maxDim = 640
        var quality = 70
        while (maxDim >= 160) {
            val scaled = scaleDown(src, maxDim)
            val out = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
            quality -= 15
            if (quality < 35) { quality = 60; maxDim /= 2 }
        }
        return null
    }

    private fun scaleDown(src: android.graphics.Bitmap, maxDim: Int): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        val factor = maxOf(1, maxOf(w, h) / maxDim)
        return android.graphics.Bitmap.createScaledBitmap(src, w / factor, h / factor, true)
    }

    fun handleIncomingPayload(rawPayload: ByteArray, senderId: MemberId) {
        val chat = PayloadCodec.tryDecodeChat(rawPayload) ?: return
        handleIncomingChatPayload(chat, senderId)
    }
}
