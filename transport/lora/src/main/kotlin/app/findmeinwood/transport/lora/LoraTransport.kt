package app.findmeinwood.transport.lora

import android.content.Context
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
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
 */
class LoraTransport(
    private val context: Context,
    private val link: LoraNodeLink = BleNodeLink(context),
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
            scope.launch { link.connect() }
        }
        return events
    }

    override suspend fun stop() {
        running = false
        linkReady = false
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
                lastRxMs = System.currentTimeMillis()
                emit(RadioState.READY)
                flushQueue()
            }
            NodeLinkState.DOWN -> {
                linkReady = false
                emit(RadioState.LINK_DOWN)
                if (running) scope.launch { reconnectWithBackoff() }
            }
            NodeLinkState.CONNECTING -> emit(RadioState.CONNECTING)
        }
    }

    private suspend fun reconnectWithBackoff() {
        var delayMs = 5_000L
        while (running && !linkReady) {
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(60_000)
            link.connect()
            return // connect() re-subscribes; DOWN events re-trigger backoff
        }
    }

    private suspend fun watchdogLoop() {
        while (running) {
            delay(10_000)
            if (linkReady && System.currentTimeMillis() - lastRxMs > 30_000) {
                onLinkState(NodeLinkState.DOWN)
            }
        }
    }

    private fun onLinkBytes(data: ByteArray) {
        lastRxMs = System.currentTimeMillis()
        framer.offer(data).forEach { frame ->
            events.tryEmit(TransportEvent.FrameReceived(FrameCodec.decode(frame), id))
        }
    }

    private fun emit(state: RadioState) = events.tryEmit(TransportEvent.StateChanged(state))

    companion object {
        const val TX_QUEUE = 5
    }
}
