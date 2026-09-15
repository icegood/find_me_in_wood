package com.icegood.findmeinwood.core.model

import kotlinx.serialization.cbor.Cbor

object PayloadCodec {
    fun encode(payload: BeaconPayload): ByteArray =
        Cbor.encodeToByteArray(BeaconPayload.serializer(), payload)

    fun decode(bytes: ByteArray): BeaconPayload =
        Cbor.decodeFromByteArray(BeaconPayload.serializer(), bytes)

    fun encodeHello(payload: HelloPayload): ByteArray = Cbor.encodeToByteArray(HelloPayload.serializer(), payload)

    fun decodeHello(bytes: ByteArray): HelloPayload = Cbor.decodeFromByteArray(HelloPayload.serializer(), bytes)

    fun encodeJoinRequest(payload: JoinRequestPayload): ByteArray = Cbor.encodeToByteArray(JoinRequestPayload.serializer(), payload)

    fun decodeJoinRequest(bytes: ByteArray): JoinRequestPayload = Cbor.decodeFromByteArray(JoinRequestPayload.serializer(), bytes)

    fun encodeJoinAccept(payload: JoinAcceptPayload): ByteArray = Cbor.encodeToByteArray(JoinAcceptPayload.serializer(), payload)

    fun decodeJoinAccept(bytes: ByteArray): JoinAcceptPayload = Cbor.decodeFromByteArray(JoinAcceptPayload.serializer(), bytes)

    fun encodeJoinReject(payload: JoinRejectPayload): ByteArray = Cbor.encodeToByteArray(JoinRejectPayload.serializer(), payload)

    fun decodeJoinReject(bytes: ByteArray): JoinRejectPayload = Cbor.decodeFromByteArray(JoinRejectPayload.serializer(), bytes)

    fun encodeChat(payload: ChatPayload): ByteArray =
        ChatPayloadCodec.encode(payload)

    fun decodeChat(bytes: ByteArray): ChatPayload =
        ChatPayloadCodec.decode(bytes)

    fun tryDecodeChat(bytes: ByteArray): ChatPayload? = try {
        ChatPayloadCodec.decode(bytes)
    } catch (_: Exception) {
        null
    }

    fun fromFix(fix: GnssFix, batteryPct: Int? = null): BeaconPayload = BeaconPayload(
        position = BeaconPosition(
            latE7 = (fix.lat * 1e7).toInt(),
            lonE7 = (fix.lon * 1e7).toInt(),
            accuracyCm = (fix.accuracyM * 100).toInt(),
            altitudeCm = (fix.altitudeM * 100).toInt(),
        ),
        batteryPct = batteryPct,
    )
}
