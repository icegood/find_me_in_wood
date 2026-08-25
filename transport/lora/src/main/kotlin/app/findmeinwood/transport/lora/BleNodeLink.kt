package app.findmeinwood.transport.lora

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * BLE serial link to the member's LoRa node (T5.1, lora-node.wsd):
 * Nordic UART Service, write-without-response TX, notify RX. Kable-free
 * platform implementation (single role: central only).
 */
class BleNodeLink(
    private val context: Context,
    private val pinnedAddress: String? = null,
) : LoraNodeLink {
    override val mtuPayload: Int get() = _mtu.value - ATT_OVERHEAD

    private val events = MutableSharedFlow<LoraNodeLink.NodeLinkEvent>(
        replay = 16, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _mtu = MutableStateFlow(DEFAULT_MTU)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var running = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writePending = false
    private val lock = Any()

    override fun connect(): Flow<LoraNodeLink.NodeLinkEvent> {
        if (!running) {
            running = true
            try { startScan() } catch (e: Exception) {
                events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
            }
        }
        return events
    }

    override suspend fun disconnect() {
        running = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
    }

    override suspend fun write(bytes: ByteArray): Boolean {
        if (!running || gatt == null) return false
        synchronized(lock) {
            FrameChunker.split(bytes, mtuPayload).forEach(writeQueue::add)
            pump()
        }
        return true
    }

    private fun startScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: run {
            events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN)); return
        }
        events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.CONNECTING))
        val filter = if (pinnedAddress != null) {
            // pinned node may not advertise NUS; match by address
            ScanFilter.Builder().setDeviceAddress(pinnedAddress).build()
        } else {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        }
        scanner.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    if (!running || gatt != null) return
                    scanner.stopScan(this)
                    try {
                        result.device.connectGatt(
                            context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE,
                        )
                    } catch (e: SecurityException) {
                        events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
                    }
                }
            },
        )
    }

    /** One-shot discovery of nearby nodes (T5.3 picker): emits MAC addresses. */
    fun scan(durationMs: Long = 5000): Flow<ScannedNode> = kotlinx.coroutines.flow.callbackFlow {
        val scanScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { close(); return@callbackFlow }
        val seen = mutableSetOf<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = try { result.device.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
                val info = ScannedNode(result.device.address, name)
                if (seen.add(info.address)) trySend(info)
            }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        scanScope.launch {
            delay(durationMs)
            scanner.stopScan(cb)
            close()
            scanScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    data class ScannedNode(val address: String, val name: String?)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt = g
                try { g?.requestMtu(MTU_REQUEST) } catch (e: SecurityException) {}
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                try { g?.close() } catch (e: Exception) {}
                gatt = null
                if (running) events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            _mtu.value = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            try { g?.discoverServices() } catch (e: SecurityException) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g?.getService(NUS_SERVICE) ?: run {
                events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.DOWN)); return
            }
            val tx = svc.getCharacteristic(NUS_TX) ?: return
            try {
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD) ?: return
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } catch (e: SecurityException) {}
        }

        override fun onDescriptorWrite(g: BluetoothGatt?, d: BluetoothGattDescriptor?, status: Int) {
            events.tryEmit(LoraNodeLink.NodeLinkEvent.State(NodeLinkState.READY))
            synchronized(lock) { pump() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
            synchronized(lock) {
                writePending = false
                pump()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
            if (c?.uuid != NUS_TX) return
            val v = c.value ?: return
            events.tryEmit(LoraNodeLink.NodeLinkEvent.Bytes(v))
        }
    }

    private fun pump() {
        val g = gatt ?: return
        if (writePending) return
        val svc = g.getService(NUS_SERVICE) ?: return
        val rx = svc.getCharacteristic(NUS_RX) ?: return
        val next = writeQueue.firstOrNull() ?: return
        try {
            rx.value = writeQueue.removeFirst()
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (g.writeCharacteristic(rx)) writePending = true
        } catch (_: SecurityException) {
        }
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // app -> node
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // node -> app
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU_REQUEST = 247
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }
}

/** Splits a packet into MTU-sized chunks (pure). */
object FrameChunker {
    fun split(bytes: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk > 0)
        return bytes.toList().chunked(maxChunk).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
    }
}
