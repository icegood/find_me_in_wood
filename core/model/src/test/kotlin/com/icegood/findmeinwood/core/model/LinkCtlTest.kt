package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkCtlTest {
    @Test
    fun `link control survives the beacon codec and is absent by default`() {
        val sel = LinkSel(byteArrayOf(1, 2, 3, 4), TransportId.SMS, 7)
        val ctl = LinkCtl(
            offers = listOf(LinkOffer(TransportId.BLUETOOTH), LinkOffer(TransportId.SMS, "+380001")),
            acks = listOf(LinkAck(byteArrayOf(5, 6, 7, 8), 42)),
            sel = listOf(sel),
            name = "Ira",
        )
        val back = PayloadCodec.decode(PayloadCodec.encode(BeaconPayload(link = ctl))).link!!
        assertEquals(3, back.v)
        assertEquals(ctl.offers, back.offers)
        assertTrue(back.sel.single().p.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(TransportId.SMS, back.sel.single().t)
        assertEquals(7, back.sel.single().e)
        assertEquals(42, back.acks.single().h)
        assertEquals("Ira", back.name)
        assertNull(PayloadCodec.decode(PayloadCodec.encode(BeaconPayload())).link)
        assertEquals(LinkCtl(), LinkCtl(v = 3, offers = emptyList(), acks = emptyList(), sel = emptyList()))
        assertEquals("+1", LinkOffer(TransportId.SMS, "+1").copy().addr)
    }

    @Test
    fun `link status - usable without a reason, greyed with one`() {
        val ok = LinkStatus("SMS", TransportId.SMS, addr = "+1")
        assertEquals(null, ok.reason)
        assertEquals(null, ok.fix)
        val off = ok.copy(reason = "set this phone's number", fix = LinkFix.SET_PHONE_NUMBER, addr = null)
        kotlin.test.assertNotEquals(ok, off)
        assertEquals(5, LinkFix.entries.size)
        assertEquals(LinkFix.ALLOW_SMS, LinkFix.valueOf("ALLOW_SMS"))
    }
}
