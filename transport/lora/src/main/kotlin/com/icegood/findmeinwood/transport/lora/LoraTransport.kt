package com.icegood.findmeinwood.transport.lora

import android.content.Context
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * LoRa transport (T5.2, US-7): node = opaque byte pipe (BLE or USB link),
 * stream framing with magic, watchdog + auto-reconnect (FR-7.4), TX queue N=5 (FR-7.5).
 * States (the link to the node; LoRa itself is a shared-air pipe behind it):
 *
 * ```
 *                  ┌─────────┐
 *  stop (any) ────▶│ Stopped │
 *                  └────┬────┘
 *                 start │
 *                       ▼
 *                ┌────────────┐   node found, its link opened    ┌───────┐
 *         ┌─────▶│ Connecting │─────────────────────────────────▶│ Ready │
 *         │      └─────┬──────┘                                  └───┬───┘
 *         │            │ (1)                                         │ (2)
 *         │            ▼                                             │
 *         │      ┌──────────┐                                        │
 *         └─(3)──│ LinkDown │◀───────────────────────────────────────┘
 *                └──────────┘
 *
 *  (1) no Bluetooth scanner, the connect refused, or the node has no serial service
 *  (2) the node disconnected, or nothing was received for watchdogTimeoutMs
 *  (3) after reconnectDelayMs, doubled on every failure in a row up to
 *     maxReconnectDelayMs (back to the start once Ready): connect again
 * ```
 *
 * Sending: Ready flushes the queue at once; in any other running state the last
 * [TX_QUEUE] frames wait for Ready.
 */
class LoraTransport(
    private val context: Context,
    private val link: LoraNodeLink = BleNodeLink(context),
    private val reconnectDelayMs: Long = 5_000,
    /** The reconnect delay doubles on every failure in a row, up to this. */
    private val maxReconnectDelayMs: Long = 60_000,
    private val watchdogPeriodMs: Long = 10_000,
    private val watchdogTimeoutMs: Long = 30_000,
) : Transport {
    override val id: TransportId = TransportId.LORA

    private val events = MutableSharedFlow<TransportEvent>(
        replay = 64, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val framer = StreamFramer()
    private val txQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var linkReady = false
    @Volatile private var lastRxMs = 0L
    /** Delay before the next reconnect; back to [reconnectDelayMs] once the link is ready. */
    private var nextReconnectMs = reconnectDelayMs
    private var reconnectJob: kotlinx.coroutines.Job? = null

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            emit(RadioState.SCANNING)
            scope.launch {
                link.connect().collect { ev ->
                    when (ev) {
                        is LoraNodeLink.NodeLinkEvent.State -> onLinkState(ev.state)
                        is LoraNodeLink.NodeLinkEvent.Bytes -> onLinkBytes(ev.data)
                    }
                }
            }
            scope.launch { watchdogLoop() }
        }
        return events
    }

    override suspend fun stop() {
        running = false
        linkReady = false
        synchronized(this) { reconnectJob?.cancel(); reconnectJob = null; nextReconnectMs = reconnectDelayMs }
        txQueue.clear()
        link.disconnect()
        emit(RadioState.STOPPED)
    }

    override suspend fun send(wireFrame: ByteArray): SendResult {
        if (!running) return SendResult.Failed("stopped")
        txQueue.add(StreamFramer.packet(wireFrame))
        while (txQueue.size > TX_QUEUE) txQueue.poll() // keep last N (FR-7.5)
        return if (linkReady) { flushQueue(); SendResult.Sent } else SendResult.Failed("node link down")
    }

    private fun flushQueue() {
        scope.launch {
            while (linkReady) {
                val packet = txQueue.peek() ?: return@launch
                if (link.write(packet)) txQueue.poll() else break
            }
        }
    }

    private fun onLinkState(state: NodeLinkState) {
        when (state) {
            NodeLinkState.READY -> {
                linkReady = true
                synchronized(this) { nextReconnectMs = reconnectDelayMs }
                lastRxMs = System.currentTimeMillis()
                emit(RadioState.READY)
                flushQueue()
            }
            NodeLinkState.DOWN -> {
                linkReady = false
                emit(RadioState.LINK_DOWN)
                if (running) scheduleReconnect()
            }
            NodeLinkState.CONNECTING -> emit(RadioState.CONNECTING)
        }
    }

    /**
     * One reconnect at a time, each failure in a row waiting twice as long as the one
     * before (capped): a node that is gone is not hammered, one that is back within
     * seconds is found at once.
     */
    @Synchronized
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val wait = nextReconnectMs
        nextReconnectMs = (wait * 2).coerceAtMost(maxReconnectDelayMs)
        reconnectJob = scope.launch {
            delay(wait)
            if (running && !linkReady) link.connect() // a DOWN it reports schedules the next
        }
    }

    private suspend fun watchdogLoop() {
        while (running) {
            delay(watchdogPeriodMs)
            if (linkReady && System.currentTimeMillis() - lastRxMs > watchdogTimeoutMs) {
                onLinkState(NodeLinkState.DOWN)
            }
        }
    }

    private fun onLinkBytes(data: ByteArray) {
        lastRxMs = System.currentTimeMillis()
        framer.offer(data).forEach { frame ->
            if (MgmtCodec.isMgmt(frame)) {
                events.tryEmit(TransportEvent.MgmtFrameReceived(frame, id))
            } else {
                // A resync on a false magic yields garbage: drop it instead of letting the
                // exception kill the link collector (and with it all LoRa reception).
                runCatching { FrameCodec.decode(frame) }.getOrNull()
                    ?.let { events.tryEmit(TransportEvent.FrameReceived(it, id)) }
            }
        }
    }

    private fun emit(state: RadioState) = events.tryEmit(TransportEvent.StateChanged(state))

    companion object {
        const val TX_QUEUE = 5
    }
}
