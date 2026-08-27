package app.findmeinwood.core.p2p

import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified P2P manager that orchestrates all available transports.
 * Peers discovered via any protocol are merged into a single view.
 * Messages are sent via the best available transport with fallback.
 */
class MultiProtocolP2PManager(
    private val transports: Map<TransportId, Transport>,
    private val scope: CoroutineScope,
) {
    private val _connectedPeers = MutableStateFlow<Map<String, Set<TransportId>>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, Set<TransportId>>> = _connectedPeers.asStateFlow()

    private val _transportStates = MutableStateFlow<Map<TransportId, TransportState>>(emptyMap())
    val transportStates: StateFlow<Map<TransportId, TransportState>> = _transportStates.asStateFlow()

    private val activeChannels = mutableListOf<P2PChannel>()

    fun registerChannel(channel: P2PChannel) {
        activeChannels.add(channel)
        scope.launch {
            channel.open().collect { event ->
                when (event) {
                    is P2PEvent.PeerDiscovered -> {
                        val current = _connectedPeers.value.toMutableMap()
                        val peerTransports = current.getOrPut(event.peerId) { emptySet() }
                        current[event.peerId] = peerTransports
                        _connectedPeers.value = current
                    }
                    is P2PEvent.PeerConnected -> {
                        val current = _connectedPeers.value.toMutableMap()
                        val peerTransports = current.getOrPut(event.peerId) { emptySet() }
                        current[event.peerId] = peerTransports
                        _connectedPeers.value = current
                    }
                    is P2PEvent.PeerDisconnected -> {
                        val current = _connectedPeers.value.toMutableMap()
                        current.remove(event.peerId)
                        _connectedPeers.value = current
                    }
                    is P2PEvent.Message -> { /* handled by consumer */ }
                }
            }
        }
    }

    suspend fun sendViaTransport(transportId: TransportId, wireFrame: ByteArray): SendResult {
        val transport = transports[transportId] ?: return SendResult.Failed("Transport not available")
        return transport.send(wireFrame)
    }

    suspend fun sendViaBestTransport(wireFrame: ByteArray): SendResult {
        for ((id, transport) in transports) {
            val state = _transportStates.value[id]
            if (state == TransportState.READY || state == null) {
                val result = transport.send(wireFrame)
                if (result is SendResult.Sent) return result
            }
        }
        return SendResult.Failed("No transport available")
    }

    suspend fun sendViaChannel(channelId: String, peerId: String, payload: ByteArray): Boolean {
        val channel = activeChannels.find { it.id == channelId } ?: return false
        return channel.send(peerId, payload)
    }

    fun getAvailableProtocols(): Set<String> =
        transports.keys.map { it.name }.toSet() +
            activeChannels.map { it.protocol }.toSet()

    fun getProtocolForPeer(peerId: String): Set<TransportId> =
        _connectedPeers.value[peerId] ?: emptySet()

    suspend fun stopAll() {
        activeChannels.forEach { it.close() }
        activeChannels.clear()
        transports.values.forEach { it.stop() }
    }

    companion object {
        enum class TransportState { STOPPED, SCANNING, CONNECTING, READY, LINK_DOWN }
    }
}
