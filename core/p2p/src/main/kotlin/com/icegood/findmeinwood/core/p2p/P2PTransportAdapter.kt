package com.icegood.findmeinwood.core.p2p

import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Wraps an existing [Transport] as a [P2PChannel] for use with [MultiProtocolP2PManager].
 */
class P2PTransportAdapter(
    override val id: String,
    override val protocol: String,
    private val transport: Transport,
) : P2PChannel {

    override fun open(): Flow<P2PEvent> {
        val config = com.icegood.findmeinwood.transport.api.SessionConfig(
            networkId = com.icegood.findmeinwood.core.model.NetworkId(ByteArray(32)),
            transports = setOf(transport.id),
        )
        return transport.start(config).mapNotNull { event ->
            when (event) {
                is TransportEvent.FrameReceived -> {
                    P2PEvent.Message(
                        peerId = event.frame.senderId.bytes.joinToString("") { "%02x".format(it) },
                        payload = event.frame.ciphertext,
                    )
                }
                is TransportEvent.PeerRadioVisible -> {
                    P2PEvent.PeerDiscovered(event.radioPeerId, null)
                }
                is TransportEvent.MgmtFrameReceived -> null
                is TransportEvent.StateChanged -> null
            }
        }
    }

    override suspend fun send(peerId: String, payload: ByteArray): Boolean {
        return transport.send(payload) is SendResult.Sent
    }

    override suspend fun close() {
        transport.stop()
    }

    override fun isAvailable(): Boolean = true
}
