package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkCtlTest {
    @Test
    fun `link control survives the beacon codec and is absent by default`() {
        val ctl = LinkCtl(
            offers = listOf(LinkOffer(TransportId.BLUETOOTH), LinkOffer(TransportId.SMS, "+380001")),
            acks = listOf(LinkAck(byteArrayOf(1, 2, 3, 4), 42)),
        )
        val back = PayloadCodec.decode(PayloadCodec.encode(BeaconPayload(link = ctl))).link!!
        assertEquals(1, back.v)
        assertEquals(ctl.offers, back.offers)
        assertTrue(back.acks.single().p.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(42, back.acks.single().h)
        assertNull(PayloadCodec.decode(PayloadCodec.encode(BeaconPayload())).link)
        assertEquals(LinkCtl(), LinkCtl(v = 1, offers = emptyList(), acks = emptyList()))
        assertEquals("+1", LinkOffer(TransportId.SMS, "+1").copy().addr)
        assertEquals(7, LinkAck(byteArrayOf(), 7).copy().h)
    }
}
