package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatPayloadTest {

    @Test
    fun `round-trip encode decode chat payload`() {
        val payload = ChatPayload(
            id = "msg-123",
            senderName = "Alice",
            text = "Hello world",
            photoRef = "content://photo/1",
            timestamp = 1700000000000L,
            replyTo = null,
        )
        val bytes = ChatPayloadCodec.encode(payload)
        val decoded = ChatPayloadCodec.decode(bytes)
        assertEquals(payload.id, decoded.id)
        assertEquals(payload.senderName, decoded.senderName)
        assertEquals(payload.text, decoded.text)
        assertEquals(payload.photoRef, decoded.photoRef)
        assertEquals(payload.timestamp, decoded.timestamp)
        assertNull(decoded.replyTo)
    }

    @Test
    fun `chat payload with reply-to`() {
        val payload = ChatPayload(
            id = "msg-456",
            senderName = "Bob",
            text = "Reply",
            timestamp = 1700000001000L,
            replyTo = "msg-123",
        )
        val bytes = ChatPayloadCodec.encode(payload)
        val decoded = ChatPayloadCodec.decode(bytes)
        assertEquals("msg-123", decoded.replyTo)
    }

    @Test
    fun `tryDecodeChat returns null for beacon payload`() {
        val beaconBytes = PayloadCodec.encode(BeaconPayload())
        val result = PayloadCodec.tryDecodeChat(beaconBytes)
        assertNull(result)
    }

    @Test
    fun `tryDecodeChat returns chat payload for chat bytes`() {
        val chat = ChatPayload("id", "name", "text", timestamp = 1L)
        val result = PayloadCodec.tryDecodeChat(PayloadCodec.encodeChat(chat))
        assertEquals("text", result?.text)
    }

    @Test
    fun `encodeChat and decodeChat work through PayloadCodec`() {
        val chat = ChatPayload("x", "me", "test", timestamp = 42L)
        val bytes = PayloadCodec.encodeChat(chat)
        val decoded = PayloadCodec.decodeChat(bytes)
        assertEquals("test", decoded.text)
        assertEquals(42L, decoded.timestamp)
    }
}
