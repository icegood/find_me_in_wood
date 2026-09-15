package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises every public type of the transport contract surface. */
class TransportTypesTest {
    @Test
    fun `all transports ids and radio states exist`() {
        assertEquals(
            setOf("WIFI_DIRECT", "BLUETOOTH", "LORA", "INTERNET"),
            TransportId.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("STOPPED", "SCANNING", "CONNECTING", "READY", "LINK_DOWN"),
            RadioState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `send result variants`() {
        assertIs<SendResult.Sent>(SendResult.Sent)
        assertIs<SendResult.HandedToUser>(SendResult.HandedToUser)
        val f = SendResult.Failed("why")
        assertEquals("why", f.reason)
    }

    @Test
    fun `event variants carry payloads`() {
        val frame = EncryptedFrame(
            NetworkId(ByteArray(8)), MemberId(ByteArray(32)), 0u, 1u, 0L, ByteArray(2),
        )
        val received = TransportEvent.FrameReceived(frame, TransportId.LORA)
        assertEquals(frame, received.frame)
        assertEquals(TransportId.LORA, received.source)
        val visible = TransportEvent.PeerRadioVisible("AA:BB", TransportId.BLUETOOTH)
        assertEquals("AA:BB", visible.radioPeerId)
        assertNotNull(TransportEvent.StateChanged(RadioState.SCANNING).state)
    }

    @Test
    fun `session config holds provisioned set`() {
        val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.LORA))
        assertTrue(cfg.transports.contains(TransportId.LORA))
    }
}
