package com.icegood.findmeinwood.transport.lora

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Wired USB serial link to the member's LoRa node (T5.1b, FR-7.6):
 * usb-serial-for-android (CH340/CP210x/FTDI/CDC-ACM), 115200 8N1.
 */
class UsbSerialLink(private val context: Context) : LoraNodeLink {
    override val mtuPayload: Int get() = 4096

    private val events = MutableSharedFlow<LoraNodeLink.NodeLinkEvent>(
        replay = 16, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var running = false

    override fun connect(): Flow<LoraNodeLink.NodeLinkEvent> {
        if (!running) {
            running = true
            scope.launch { open() }
        }
        return events
    }

    private fun open() {
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
                ?: run { fail(); return }
            val connection = manager.openDevice(driver.device) ?: run { fail(); return }
            this.connection = connection
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            this.port = port
            events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.READY))
            val buf = ByteArray(4096)
            while (running) {
                val n = port.read(buf, 1000)
                if (n > 0) events.tryEmit(LoraNodeLink.NodeLinkEvent.Bytes(buf.copyOf(n)))
            }
        } catch (e: Exception) {
            fail()
        }
    }

    private fun fail() {
        events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
    }

    override suspend fun disconnect() {
        running = false
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null; connection = null
        events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
    }

    override suspend fun write(bytes: ByteArray): Boolean {
        val p = port ?: return false
        return try {
            p.write(bytes, 1000)
            true
        } catch (e: Exception) {
            false
        }
    }
}
