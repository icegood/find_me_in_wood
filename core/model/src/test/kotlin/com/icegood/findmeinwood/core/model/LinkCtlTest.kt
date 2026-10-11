package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkCtlTest {
    @Test
    fun `link control survives the beacon codec and is absent by default`() {
        val prop = LinkProp(byteArrayOf(1, 2, 3, 4), TransportId.SMS, 7)
        val ctl = LinkCtl(
            offers = listOf(LinkOffer(TransportId.BLUETOOTH), LinkOffer(TransportId.SMS, "+380001")),
            prop = listOf(prop),
            acc = listOf(prop.copy(e = 8)),
            name = "Ira",
        )
        val back = PayloadCodec.decode(PayloadCodec.encode(BeaconPayload(link = ctl))).link!!
        assertEquals(2, back.v)
        assertEquals(ctl.offers, back.offers)
        assertTrue(back.prop.single().p.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(TransportId.SMS, back.prop.single().t)
        assertEquals(8, back.acc.single().e)
        assertEquals("Ira", back.name)
        assertNull(PayloadCodec.decode(PayloadCodec.encode(BeaconPayload())).link)
        assertEquals(LinkCtl(), LinkCtl(v = 2, offers = emptyList(), prop = emptyList(), acc = emptyList()))
        assertEquals("+1", LinkOffer(TransportId.SMS, "+1").copy().addr)
    }
}
