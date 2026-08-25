package app.findmeinwood.core.model

enum class TransportId { WIFI_DIRECT, BLUETOOTH, LORA, INTERNET }

enum class JoinPolicy { OPEN, PRIVATE, CODE }

class NetworkId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is NetworkId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "NetworkId(${bytes.size}B)"
}

class MemberId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is MemberId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "MemberId(${bytes.size}B)"
}

data class NetworkProfile(
    val name: String,
    val networkId: NetworkId,
    val trafficKey: ByteArray,
    val myMemberId: MemberId,
    val ownerMemberId: MemberId,
    val policy: JoinPolicy,
)

data class GnssFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val epochMs: Long,
)

@kotlinx.serialization.Serializable
data class BeaconPosition(
    val latE7: Int,
    val lonE7: Int,
    val accuracyCm: Int,
    val altitudeCm: Int,
)

@kotlinx.serialization.Serializable
data class BeaconPayload(
    val position: BeaconPosition? = null,
    val fixAgeS: Int? = null,
    val batteryPct: Int? = null,
)

@kotlinx.serialization.Serializable
data class HelloPayload(
    val name: String? = null,
    val ownerMemberId: ByteArray,
    val ownerNick: String? = null,
    val policy: JoinPolicy = JoinPolicy.CODE,
    val activeTransports: List<TransportId> = emptyList(),
)
