package com.icegood.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MgmtPayloadTest {
    @Test
    fun `hello payload roundtrip`() {
        val p = HelloPayload(
            name = "hunt-2026",
            ownerMemberId = ByteArray(32) { 7 },
            ownerNick = "Misha",
            policy = JoinPolicy.PRIVATE,
            activeTransports = listOf(TransportId.BLUETOOTH, TransportId.LORA),
        )
        val back = PayloadCodec.decodeHello(PayloadCodec.encodeHello(p))
        assertEquals("hunt-2026", back.name)
        assertEquals(JoinPolicy.PRIVATE, back.policy)
        assertEquals(listOf(TransportId.BLUETOOTH, TransportId.LORA), back.activeTransports)
        assertTrue(back.ownerMemberId.contentEquals(p.ownerMemberId))
    }

    @Test
    fun `join request accept and reject roundtrip`() {
        val req = JoinRequestPayload(ByteArray(44) { 1 }, "Misha")
        val reqBack = PayloadCodec.decodeJoinRequest(PayloadCodec.encodeJoinRequest(req))
        assertEquals("Misha", reqBack.joinerNick)
        assertTrue(reqBack.joinerPubkey.contentEquals(req.joinerPubkey))

        val accept = JoinAcceptPayload(
            name = "net", policy = JoinPolicy.OPEN, secret = "s3cret",
            ownerPub = null, sealedSecret = null, nonce = ByteArray(8) { 2 },
        )
        val acceptBack = PayloadCodec.decodeJoinAccept(PayloadCodec.encodeJoinAccept(accept))
        assertEquals("s3cret", acceptBack.secret)
        assertEquals(JoinPolicy.OPEN, acceptBack.policy)

        val sealed = JoinAcceptPayload(
            name = "net", policy = JoinPolicy.PRIVATE, secret = null,
            ownerPub = ByteArray(44) { 3 }, sealedSecret = ByteArray(16) { 4 }, nonce = ByteArray(8) { 5 },
        )
        val sealedBack = PayloadCodec.decodeJoinAccept(PayloadCodec.encodeJoinAccept(sealed))
        assertEquals(null, sealedBack.secret)
        assertTrue(sealedBack.sealedSecret!!.contentEquals(sealed.sealedSecret!!))

        val rej = PayloadCodec.decodeJoinReject(PayloadCodec.encodeJoinReject(JoinRejectPayload("nope")))
        assertEquals("nope", rej.reason)
    }

    @Test
    fun `beacon payload carries signature and public key`() {
        val p = BeaconPayload(
            position = BeaconPosition(1, 2, 3, 4),
            fixAgeS = 5,
            batteryPct = 80,
            sig = ByteArray(64) { 9 },
            pub = ByteArray(44) { 8 },
        )
        val back = PayloadCodec.decode(PayloadCodec.encode(p))
        assertTrue(back.sig!!.contentEquals(p.sig!!))
        assertTrue(back.pub!!.contentEquals(p.pub!!))
        assertEquals(80, back.batteryPct)
    }
}
