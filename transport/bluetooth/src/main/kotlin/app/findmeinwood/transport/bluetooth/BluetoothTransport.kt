package app.findmeinwood.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.annotation.SuppressLint
import android.os.ParcelUuid
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * BLE GATT dual-role transport (T2.3, bt-transport.wsd).
 * Every phone is peripheral (advertises + GATT server) and central (scans + connects).
 * No pairing: trust = AEAD under network keys (FR-2.3).
 */
class BluetoothTransport(private val context: Context) : Transport {
    override val id: TransportId = TransportId.BLUETOOTH

    private val events = MutableSharedFlow<TransportEvent>(
        replay = 64, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var running = false
    @Volatile private var ready = false

    private val manager: BluetoothManager? get() = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: AdvertiseCallback? = null
    private var scannerCallback: ScanCallback? = null
    private var clientGatt: BluetoothGatt? = null

    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val serverDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val serverFramers = ConcurrentHashMap<String, FrameFramer.Reassembler>()
    private val clientFramer = FrameFramer.Reassembler()

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writePending = false
    private var clientMtu = DEFAULT_MTU
    private val lock = Any()

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            try {
                startPeripheral()
                startCentral()
                emit(RadioState.READY)
                ready = true
            } catch (e: SecurityException) {
                emit(RadioState.LINK_DOWN)
            }
        }
        return events
    }

    @SuppressLint("MissingPermission") // scan permission is gated by callers of start()/stop()
    override suspend fun stop() {
        running = false
        ready = false
        try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: SecurityException) {}
        scannerCallback = null
        advertiser?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiser = null
        try { clientGatt?.disconnect(); clientGatt?.close() } catch (_: Exception) {}
        clientGatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(lock) { writeQueue.clear(); writePending = false }
        serverFramers.clear(); serverDevices.clear(); serverMtu.clear()
        clientFramer.reset()
        emit(RadioState.STOPPED)
    }

    override suspend fun send(wireFrame: ByteArray): SendResult {
        if (!running || !ready) return SendResult.Failed("link not ready")
        val hasClientLink = clientGatt != null
        val hasServerClients = serverDevices.isNotEmpty()
        if (!hasClientLink && !hasServerClients) return SendResult.Failed("no ble peer")

        if (hasClientLink) {
            synchronized(lock) {
                FrameFramer.frameToChunks(wireFrame, clientMtu - ATT_OVERHEAD).forEach(writeQueue::add)
                pumpClientWrites()
            }
        }
        if (hasServerClients) {
            serverDevices.forEach { device ->
                val mtu = (serverMtu[device.address] ?: DEFAULT_MTU) - ATT_OVERHEAD
                FrameFramer.frameToChunks(wireFrame, mtu).forEach { chunk ->
                    notifyClient(device, chunk)
                }
            }
        }
        return SendResult.Sent
    }

    private fun emit(state: RadioState) {
        events.tryEmit(TransportEvent.StateChanged(state))
    }

    private fun startPeripheral() {
        val gattServer = manager!!.openGattServer(context, serverCallback)
        this.gattServer = gattServer
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val write = BluetoothGattCharacteristic(
            CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        service.addCharacteristic(write)
        service.addCharacteristic(notify)
        gattServer.addService(service)

        adapter?.bluetoothLeAdvertiser?.let { adv ->
            val cb = object : AdvertiseCallback() {}
            advertiser = cb
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .build(),
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build(),
                cb,
            )
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) serverDevices += device
            else {
                serverDevices.remove(device)
                serverFramers.remove(device.address)
                serverMtu.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            device?.address?.let { serverMtu[it] = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device == null || value == null) return
            if (responseNeeded) gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
            )
            if (characteristic?.uuid != CHAR_WRITE_UUID) return
            val frame = serverFramers
                .getOrPut(device.address) { FrameFramer.Reassembler() }
                .offer(value) ?: return
            events.tryEmit(TransportEvent.FrameReceived(decodeOrThrow(frame), id))
            events.tryEmit(TransportEvent.PeerRadioVisible(device.address, id))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device != null && responseNeeded) gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
            )
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            if (device != null) gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
            )
        }
    }

    private fun startCentral() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!running || clientGatt != null) return
                events.tryEmit(TransportEvent.PeerRadioVisible(result.device.address, id))
                try {
                    result.device.connectGatt(
                        context, false, clientCallback, BluetoothDevice.TRANSPORT_LE,
                    )
                } catch (_: SecurityException) {
                }
            }
        }
        scannerCallback = cb
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            cb,
        )
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clientGatt = gatt
                try { gatt?.requestMtu(MTU_REQUEST) } catch (_: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt?.close()
                if (clientGatt === gatt) clientGatt = null
                ready = running // may reconnect via scan
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            clientMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            try { gatt?.discoverServices() } catch (_: SecurityException) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt?.getService(SERVICE_UUID) ?: return
            val notify = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            try {
                gatt.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(CCCD_UUID) ?: return
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } catch (_: SecurityException) {}
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            ready = true
            emit(RadioState.READY)
            synchronized(lock) { pumpClientWrites() }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            synchronized(lock) {
                writePending = false
                pumpClientWrites()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (characteristic?.uuid != CHAR_NOTIFY_UUID) return
            val value = characteristic.value ?: return
            clientFramer.offer(value)?.let { frame ->
                events.tryEmit(TransportEvent.FrameReceived(decodeOrThrow(frame), id))
            }
        }
    }

    private fun notifyClient(device: BluetoothDevice, chunk: ByteArray) {
        val server = gattServer ?: return
        try {
            val service = server.getService(SERVICE_UUID) ?: return
            val notify = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            notify.value = chunk
            server.notifyCharacteristicChanged(device, notify, false)
        } catch (_: SecurityException) {
        }
    }

    private fun pumpClientWrites() {
        val gatt = clientGatt ?: return
        if (writePending) return
        val next = writeQueue.firstOrNull() ?: return
        try {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val write = service.getCharacteristic(CHAR_WRITE_UUID) ?: return
            write.value = writeQueue.removeFirst()
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (gatt.writeCharacteristic(write)) writePending = true
        } catch (_: SecurityException) {
        }
    }

    private fun decodeOrThrow(frame: ByteArray) = FrameCodec.decode(frame)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f11d0a01-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CHAR_WRITE_UUID: UUID = UUID.fromString("f11d0a02-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CHAR_NOTIFY_UUID: UUID = UUID.fromString("f11d0a03-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU_REQUEST = 247
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }
}
