package app.findmeinwood.core.model

import kotlinx.serialization.cbor.Cbor

object PayloadCodec {
    fun encode(payload: BeaconPayload): ByteArray =
        Cbor.encodeToByteArray(BeaconPayload.serializer(), payload)

    fun decode(bytes: ByteArray): BeaconPayload =
        Cbor.decodeFromByteArray(BeaconPayload.serializer(), bytes)

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
