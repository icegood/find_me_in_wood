package com.icegood.findmeinwood.core.model

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.Serializable

@Serializable
data class ChatPayload(
    val id: String,
    val senderName: String,
    val text: String,
    val photoRef: String? = null,
    /** Image bytes: a path only works on the sender, so the photo travels with the message. */
    val photoBytes: ByteArray? = null,
    val timestamp: Long,
    val replyTo: String? = null,
)

object ChatPayloadCodec {
    fun encode(payload: ChatPayload): ByteArray =
        Cbor.encodeToByteArray(ChatPayload.serializer(), payload)

    fun decode(bytes: ByteArray): ChatPayload =
        Cbor.decodeFromByteArray(ChatPayload.serializer(), bytes)
}
