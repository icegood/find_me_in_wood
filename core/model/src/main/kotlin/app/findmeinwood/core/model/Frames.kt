package app.findmeinwood.core.model

data class EncryptedFrame(
    val networkId: NetworkId,
    val senderId: MemberId,
    val seq: UInt,
    val ttl: UByte,
    val sentAtMs: Long,
    val ciphertext: ByteArray,
)

data class DecryptedBeacon(
    val senderId: MemberId,
    val seq: UInt,
    val sentAtMs: Long,
    val payload: BeaconPayload,
)
