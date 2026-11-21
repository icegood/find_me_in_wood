package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LinkHeardTest {
    @Test
    fun `radios with one link per phone ignore which member a link reaches`() {
        val plain = object : Transport {
            override val id = TransportId.LORA
            override fun start(config: SessionConfig): Flow<TransportEvent> = emptyFlow()
            override suspend fun stop() {}
            override suspend fun send(wireFrame: ByteArray): SendResult = SendResult.Sent
        }
        plain.linkHeard("AA:BB", byteArrayOf(1)) // no-op, must not throw
    }

    @Test
    fun `the fake records heard links and delivers frames on a link`() = runTest {
        val t = FakeTransport(TransportId.BLUETOOTH)
        val events = t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        t.linkHeard("AA:01", byteArrayOf(0x0a, 0x1b))
        assertEquals(listOf("AA:01" to "0a1b"), t.heardLinks.toList())
        val frame = EncryptedFrame(NetworkId(ByteArray(8)), MemberId(ByteArray(32)), 1u, 3u, 0L, ByteArray(2))
        t.deliver(frame, link = "AA:01")
        val ev = events.first { it is TransportEvent.FrameReceived } as TransportEvent.FrameReceived
        assertEquals("AA:01", ev.link)
    }
}
