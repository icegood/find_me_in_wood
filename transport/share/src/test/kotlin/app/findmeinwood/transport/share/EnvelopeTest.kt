package app.findmeinwood.transport.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {
    @Test
    fun `envelope roundtrip preserves wire bytes`() {
        val wire = ByteArray(149) { (it * 13).toByte() }
        val text = ShareTransport.encodeEnvelope(wire)
        assertTrue(text.startsWith("fmiw1:"))
        assertEquals(wire.toList(), ShareTransport.parseEnvelope(text)!!.toList())
    }

    @Test
    fun `parses envelope embedded in chat text with newlines`() {
        val wire = ByteArray(10) { 5 }
        val chat = "hey, position:\n${ShareTransport.encodeEnvelope(wire)}\n(copied from app)"
        assertEquals(wire.toList(), ShareTransport.parseEnvelope(chat)!!.toList())
    }

    @Test
    fun `rejects non-envelope text`() {
        assertNull(ShareTransport.parseEnvelope("hello there"))
        assertNull(ShareTransport.parseEnvelope("fmiw1:!!!not-base64!!!"))
    }
}
