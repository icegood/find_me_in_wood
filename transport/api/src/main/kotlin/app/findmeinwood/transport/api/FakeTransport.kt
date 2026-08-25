package app.findmeinwood.transport.api

import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.TransportId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** Deterministic in-memory transport for JVM tests (constitution P6). */
class FakeTransport(override val id: TransportId) : Transport {
    private val sent = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val events = MutableSharedFlow<TransportEvent>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Everything this transport "sent", for assertions and pumping. */
    val sentHistory: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()

    /** Live stream of sent wire frames — pump this into the peer in tests. */
    val sentWire: Flow<ByteArray> = sent

    var linkUp: Boolean = true
    private var running: Boolean = false

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        running = true
        events.tryEmit(TransportEvent.StateChanged(RadioState.READY))
        return events
    }

    override suspend fun stop() {
        running = false
        events.tryEmit(TransportEvent.StateChanged(RadioState.STOPPED))
    }

    override suspend fun send(wireFrame: ByteArray): SendResult =
        if (!running || !linkUp) SendResult.Failed("link down")
        else {
            sentHistory.add(wireFrame)
            sent.emit(wireFrame)
            SendResult.Sent
        }

    /** Test helper: inject a frame as if received over this transport. */
    suspend fun deliver(frame: EncryptedFrame) {
        events.emit(TransportEvent.FrameReceived(frame, id))
    }
}
