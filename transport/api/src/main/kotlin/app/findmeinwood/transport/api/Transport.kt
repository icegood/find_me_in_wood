package app.findmeinwood.transport.api

import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.TransportId
import kotlinx.coroutines.flow.Flow

enum class RadioState { STOPPED, SCANNING, CONNECTING, READY, LINK_DOWN }

sealed interface TransportEvent {
    data class FrameReceived(val frame: EncryptedFrame, val source: TransportId) : TransportEvent
    data class PeerRadioVisible(val radioPeerId: String, val transport: TransportId) : TransportEvent
    data class StateChanged(val state: RadioState) : TransportEvent
}

sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: String) : SendResult

    /** Handed to the user for delivery (e.g. share sheet -> Viber, US-9). */
    data object HandedToUser : SendResult
}

data class SessionConfig(
    val networkId: NetworkId,
    val transports: Set<TransportId>,
)

interface Transport {
    val id: TransportId

    /** Starts radio activity; emits events until [stop]. Must not throw for normal failures. */
    fun start(config: SessionConfig): Flow<TransportEvent>

    suspend fun stop()

    /** Sends one encoded wire frame. Must not throw for normal failures. */
    suspend fun send(wireFrame: ByteArray): SendResult
}
