package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlinx.coroutines.flow.Flow

enum class RadioState { STOPPED, SCANNING, CONNECTING, READY, LINK_DOWN }

sealed interface TransportEvent {
    /** [link]: the radio link it came in on (socket, Bluetooth address); null when unknown. */
    data class FrameReceived(val frame: EncryptedFrame, val source: TransportId, val link: String? = null) : TransportEvent
    data class PeerRadioVisible(val radioPeerId: String, val transport: TransportId) : TransportEvent
    /** A link to a peer became usable for sending (BLE notifications on, socket open). */
    data class LinkUp(val radioPeerId: String, val transport: TransportId) : TransportEvent
    /** Cleartext management frame (US-8 discovery/handshake) as raw wire bytes. */
    data class MgmtFrameReceived(val wire: ByteArray, val source: TransportId) : TransportEvent
    data class StateChanged(val state: RadioState) : TransportEvent
    /** A findable network's advertised info (BleAdvert.Info bytes) and the radio peer advertising it. */
    data class NetworkAdvertSeen(val radioPeerId: String, val info: ByteArray, val transport: TransportId) : TransportEvent
}

sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: String) : SendResult

    /** Handed to the user for delivery (e.g. share sheet -> Viber, US-9). */
    data object HandedToUser : SendResult
}

/** This phone's part in a radio group (a Wi-Fi Direct group). */
enum class GroupRole { HOST, JOIN }

/**
 * One Wi-Fi Direct group this phone takes part in (FR-3.5b): its credentials come from
 * [secret]; [role] is this phone's in it, as its pairs agreed. [pairs] = how many members
 * this phone meets in it.
 */
class GroupPlan(val secret: ByteArray, val role: GroupRole, val pairs: Int = 1) {
    /** The group's Wi-Fi name, as phones nearby list it. */
    val networkName: String get() = groupNetworkName(secret)

    override fun equals(other: Any?) =
        other is GroupPlan && secret.contentEquals(other.secret) && role == other.role && pairs == other.pairs
    override fun hashCode() = (secret.contentHashCode() * 31 + role.hashCode()) * 31 + pairs
}

/** Wi-Fi name of the group made from [secret] (Android requires "DIRECT-" + two characters). */
fun groupNetworkName(secret: ByteArray): String =
    "DIRECT-fm-" + secret.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }

data class SessionConfig(
    val networkId: NetworkId,
    val transports: Set<TransportId>,
    /**
     * Secret only members of the network know (derived from its traffic key): radios
     * that need credentials (the Wi-Fi Direct group) derive them from it.
     */
    val groupSecret: ByteArray? = null,
    /**
     * Only looking for networks to join: no members code, no automatic connections (a
     * link is opened with [Transport.connect] to ask a member), no Wi-Fi Direct group.
     */
    val listening: Boolean = false,
)

interface Transport {
    val id: TransportId

    /** Starts radio activity; emits events until [stop]. Must not throw for normal failures. */
    fun start(config: SessionConfig): Flow<TransportEvent>

    suspend fun stop()

    /** Sends one encoded wire frame. Must not throw for normal failures. */
    suspend fun send(wireFrame: ByteArray): SendResult

    /**
     * Relays a frame back out on this radio to every link but [except], the one it came in
     * on: a phone in the middle (Wi-Fi Direct group owner, Bluetooth with several links)
     * passes frames between members that only reach it. Null: this radio does not (a LoRa
     * node is a shared-air pipe).
     */
    suspend fun relay(wireFrame: ByteArray, except: String): SendResult? = null

    /**
     * Network info to advertise to new people (BleAdvert.Info bytes), or null to stop
     * being findable. Members reach each other either way.
     */
    fun setFindable(info: ByteArray?) {}

    /** Opens a link to [radioPeerId] (seen in [TransportEvent.NetworkAdvertSeen]) to ask to join. */
    fun connect(radioPeerId: String) {}

    /**
     * The groups this phone serves (LNP/3, FR-3.5b): the one it hosts, then one per member
     * it joins. Several groups are served in turn. Radios without groups ignore it.
     */
    fun setGroups(groups: List<GroupPlan>) {}

    /** Serve the group of [groups] at [index] now: its pair has something urgent. */
    fun serveNow(index: Int) {}

    /** Closes the radio link [link] (a member that may not use this radio with us). */
    fun closeLink(link: String) {}

    /**
     * The network key moved to a new epoch (a member was removed): radios use [current]
     * from now on ([SessionConfig.groupSecret] was the one at start). [older] secrets only
     * recognise members that missed the rekey, so they can be handed the new key.
     */
    fun setGroupSecrets(current: ByteArray, older: List<ByteArray>) {}
}
