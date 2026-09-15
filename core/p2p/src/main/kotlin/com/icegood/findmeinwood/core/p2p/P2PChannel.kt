package com.icegood.findmeinwood.core.p2p

import kotlinx.coroutines.flow.Flow

sealed interface P2PEvent {
    data class Message(val peerId: String, val payload: ByteArray) : P2PEvent
    data class PeerDiscovered(val peerId: String, val name: String?) : P2PEvent
    data class PeerConnected(val peerId: String) : P2PEvent
    data class PeerDisconnected(val peerId: String) : P2PEvent
}

interface P2PChannel {
    val id: String
    val protocol: String
    fun open(): Flow<P2PEvent>
    suspend fun send(peerId: String, payload: ByteArray): Boolean
    suspend fun close()
    fun isAvailable(): Boolean
}
