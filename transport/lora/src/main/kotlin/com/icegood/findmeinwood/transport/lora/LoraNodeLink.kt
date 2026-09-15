package com.icegood.findmeinwood.transport.lora

import kotlinx.coroutines.flow.Flow

enum class NodeLinkState { CONNECTING, READY, DOWN }

/**
 * Byte pipe to the member's own LoRa node (US-7, FR-7.2): app never sees LoRa.
 * Implementations: BleNodeLink (T5.1), UsbSerialLink (T5.1b).
 */
interface LoraNodeLink {
    val mtuPayload: Int

    /** Brings the link up; state + incoming bytes flow until [disconnect]. */
    fun connect(): Flow<NodeLinkEvent>

    suspend fun disconnect()

    suspend fun write(bytes: ByteArray): Boolean

    sealed interface NodeLinkEvent {
        data class State(val state: NodeLinkState) : NodeLinkEvent
        data class Bytes(val data: ByteArray) : NodeLinkEvent
    }
}
