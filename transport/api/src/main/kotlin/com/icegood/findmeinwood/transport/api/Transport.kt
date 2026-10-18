package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlinx.coroutines.flow.Flow

enum class RadioState { STOPPED, SCANNING, CONNECTING, READY, LINK_DOWN }

sealed interface TransportEvent {
    data class FrameReceived(val frame: EncryptedFrame, val source: TransportId) : TransportEvent
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

/** This phone's part in a radio group shared by members (the Wi-Fi Direct group). */
enum class GroupRole { HOST, JOIN }

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
     * Network info to advertise to new people (BleAdvert.Info bytes), or null to stop
     * being findable. Members reach each other either way.
     */
    fun setFindable(info: ByteArray?) {}

    /** Opens a link to [radioPeerId] (seen in [TransportEvent.NetworkAdvertSeen]) to ask to join. */
    fun connect(radioPeerId: String) {}

    /**
     * The role members agreed for this phone in a shared group (LNP/3), or null while none
     * is agreed: the radio then picks one itself. Radios without groups ignore it.
     */
    fun setGroupRole(role: GroupRole?) {}
}
