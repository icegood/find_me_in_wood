package com.icegood.findmeinwood.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.annotation.SuppressLint
import android.os.ParcelUuid
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * BLE GATT dual-role transport (T2.3, bt-transport.wsd).
 * Every phone is peripheral (advertises + GATT server) and central (scans + connects).
 * No pairing: trust = AEAD under network keys (FR-2.3).
 *
 * BLUETOOTH_SCAN/CONNECT/ADVERTISE permissions are requested and gated by the app layer
 * before this transport is started; runtime rejections surface as SecurityException.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport internal constructor(
    private val context: Context,
    private val wiring: BluetoothGattWiring,
    /** Delayed-runner used by the connect watchdog; overridable in tests. */
    private val scheduleDelayed: (Long, () -> Unit) -> Unit = { ms, block ->
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(block, ms) }
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Link supervision: BLE writes/notify give no delivery feedback, so a peer we heard
     * nothing from for this long is treated as dead and its link is dropped (FR-3.6a).
     */
    private val linkTimeoutMs: Long = LINK_TIMEOUT_MS,
) : Transport {

    constructor(context: Context) : this(context, BluetoothGattWiring(context))

    /** Public entry point for callers that need a custom link timeout. */
    constructor(context: Context, linkTimeoutMs: Long) : this(
        context, BluetoothGattWiring(context), linkTimeoutMs = linkTimeoutMs,
    )
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

    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val serverDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val serverFramers = ConcurrentHashMap<String, FrameFramer.Reassembler>()
    private val clientFramer = FrameFramer.Reassembler()

    /** One outgoing chunk queue per peer link (a mesh has several links at once). */
    private val clientQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val clientWriting = ConcurrentHashMap.newKeySet<String>()
    /** One central link per peer address: a mesh needs more than a single connection. */
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientMtu = ConcurrentHashMap<String, Int>()
    private val clientReady = ConcurrentHashMap.newKeySet<String>()
    private val lastInbound = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            running = true
            try {
                startPeripheral()
                startCentral()
                armSupervisor()
                emit(RadioState.READY)
                ready = true
            } catch (e: Exception) {
                // Transport contract: start must not throw for normal failures
                // (missing radios/permissions surface as LINK_DOWN).
                emit(RadioState.LINK_DOWN)
            }
        }
        return events
    }

    override suspend fun stop() {
        running = false
        ready = false
        try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: SecurityException) {}
        scannerCallback = null
        advertiser?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiser = null
        clients.values.forEach { g -> try { g.disconnect(); g.close() } catch (_: Exception) {} }
        clients.clear(); clientMtu.clear(); clientReady.clear(); lastInbound.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(lock) { clientQueues.clear(); clientWriting.clear() }
        serverFramers.clear(); serverDevices.clear(); serverMtu.clear()
        clientFramer.reset()
        emit(RadioState.STOPPED)
    }

    override suspend fun send(wireFrame: ByteArray): SendResult {
        if (!running || !ready) return SendResult.Failed("link not ready")
        pruneStaleLinks()
        val hasClientLink = clients.isNotEmpty()
        val hasServerClients = serverDevices.isNotEmpty()
        if (!hasClientLink && !hasServerClients) return SendResult.Failed("no ble peer")

        if (hasClientLink) {
            synchronized(lock) {
                clients.values.forEach { g ->
                    val mtu = (clientMtu[g.key()] ?: DEFAULT_MTU) - ATT_OVERHEAD
                    val q = clientQueues.getOrPut(g.key()) { ArrayDeque() }
                    FrameFramer.frameToChunks(wireFrame, mtu).forEach(q::add)
                }
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

    /** Frees a slot when a peer connects but never finishes discovery/subscription. */
    private fun watchdog(address: String) {
        // Reclaims the slot if a peer connects but never finishes discovery/subscription.
        scheduleDelayed(CONNECT_TIMEOUT_MS) {
            if (!clientReady.contains(address)) {
                clients.remove(address)?.let { g ->
                    try { g.disconnect() } catch (_: Exception) {}
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Drops links we have heard nothing from within [linkTimeoutMs]: without this the
     * transport keeps writing into a dead link and reports success (FR-3.6a failover).
     */
    internal fun pruneStaleLinks(now: Long = clock()) {
        clients.entries.removeIf { (key, g) ->
            val last = lastInbound[key] ?: now
            val dead = now - last > linkTimeoutMs
            if (dead) {
                try { g.disconnect() } catch (_: Exception) {}
                try { g.close() } catch (_: Exception) {}
                clientMtu.remove(key); clientReady.remove(key); clientQueues.remove(key)
                clientWriting.remove(key)
            }
            dead
        }
        serverDevices.removeIf { d ->
            val last = lastInbound[d.address] ?: now
            val dead = now - last > linkTimeoutMs
            if (dead) {
                serverFramers.remove(d.address); serverMtu.remove(d.address)
                try { gattServer?.cancelConnection(d) } catch (_: Exception) {}
            }
            dead
        }
    }

    /** Identity of a link; falls back to the gatt handle when the device is stubbed. */
    private fun BluetoothGatt.key(): String = device?.address ?: toString()

    private fun emit(state: RadioState) {
        events.tryEmit(TransportEvent.StateChanged(state))
    }

    private fun startPeripheral() {
        gattServer = wiring.openServer(manager!!, serverCallback)
        gattServer?.addService(wiring.buildService())
        advertiser = wiring.startAdvertising(adapter)
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
            device?.let { lastInbound[it.address] = clock() }
            val frame = serverFramers
                .getOrPut(device.address) { FrameFramer.Reassembler() }
                .offer(value) ?: return
            if (MgmtCodec.isMgmt(frame)) events.tryEmit(TransportEvent.MgmtFrameReceived(frame, id))
            else events.tryEmit(TransportEvent.FrameReceived(decodeOrThrow(frame), id))
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

    private val scanConsumer: (android.bluetooth.le.ScanResult) -> Unit = { result ->
        val address = result.device.address
        if (running && clients.size < MAX_PEERS && !clients.containsKey(address)) {
            events.tryEmit(TransportEvent.PeerRadioVisible(address, id))
            try {
                result.device.connectGatt(
                    context, false, clientCallback, BluetoothDevice.TRANSPORT_LE,
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun startCentral() {
        scannerCallback = wiring.startScan(adapter, scanConsumer)
    }

    /**
     * Link supervisor: BLE gives no delivery feedback, so when nothing is connected we
     * re-arm the scan periodically instead of waiting forever (FR-3.6a failover).
     */
    private fun armSupervisor() {
        scheduleDelayed(SUPERVISOR_INTERVAL_MS) {
            if (running) {
                pruneStaleLinks()
                if (clients.isEmpty() && serverDevices.isEmpty()) {
                    try {
                        adapter?.bluetoothLeScanner?.stopScan(scannerCallback)
                    } catch (_: SecurityException) {
                    }
                    scannerCallback = wiring.startScan(adapter, scanConsumer)
                }
                armSupervisor()
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val g = gatt ?: return
            val address = g.key()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clients[address] = g
                lastInbound[address] = clock()
                try { g.requestMtu(MTU_REQUEST) } catch (_: SecurityException) {}
                // if this peer never completes discovery, free the slot so the scan retries
                watchdog(address)
            } else {
                clients.remove(address)
                clientMtu.remove(address)
                clientReady.remove(address)
                try { g.close() } catch (_: Exception) {}
                ready = running || serverDevices.isNotEmpty()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            val g = gatt ?: return
            clientMtu[g.key()] = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            try { g.discoverServices() } catch (_: SecurityException) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val g = gatt ?: return
            val address = g.key()
            val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(SERVICE_UUID) else null
            if (service == null) {
                // not one of our peers: release the connection instead of holding the slot
                clients.remove(address)
                try { g.disconnect() } catch (_: Exception) {}
                try { g.close() } catch (_: Exception) {}
                return
            }
            val notify = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            try {
                gatt.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(CCCD_UUID) ?: return
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } catch (_: SecurityException) {}
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            gatt?.let { clientReady.add(it.key()) }
            ready = true
            emit(RadioState.READY)
            synchronized(lock) { pumpClientWrites() }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            val g = gatt
            synchronized(lock) {
                if (g != null) clientWriting.remove(g.key())
                pumpClientWrites()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (characteristic?.uuid != CHAR_NOTIFY_UUID) return
            val value = characteristic.value ?: return
            gatt?.let { lastInbound[it.key()] = clock() }
            clientFramer.offer(value)?.let { frame ->
                if (MgmtCodec.isMgmt(frame)) events.tryEmit(TransportEvent.MgmtFrameReceived(frame, id))
                else events.tryEmit(TransportEvent.FrameReceived(decodeOrThrow(frame), id))
            }
        }
    }

    internal fun notifyClient(device: BluetoothDevice, chunk: ByteArray, serverOverride: BluetoothGattServer? = gattServer) {
        val server = serverOverride ?: return
        try {
            val service = server.getService(SERVICE_UUID) ?: return
            val notify = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            notify.value = chunk
            server.notifyCharacteristicChanged(device, notify, false)
        } catch (_: SecurityException) {
        } catch (_: android.os.DeadObjectException) {
            // peer/stack went away mid-notify: drop this chunk, never crash the session
        } catch (_: Exception) {
        }
    }

    internal fun pumpClientWrites(gattOverride: BluetoothGatt? = null) {
        val targets = gattOverride?.let { listOf(it) }
            ?: clients.values.toList()
        for (gatt in targets) {
            val key = gatt.key()
            if (clientWriting.contains(key)) continue
            val queue = clientQueues[key] ?: continue
            val chunk = queue.firstOrNull() ?: continue
            try {
                val service = gatt.getService(SERVICE_UUID) ?: continue
                val write = service.getCharacteristic(CHAR_WRITE_UUID) ?: continue
                write.value = chunk
                // Acknowledged writes: WRITE_TYPE_NO_RESPONSE gives no onCharacteristicWrite
                // on many stacks, which stalled the pump after the first chunk so frames
                // never completed at the peer. Ordered + acked is correct for ~300 B beacons.
                write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (gatt.writeCharacteristic(write)) {
                    queue.removeFirst()
                    clientWriting.add(key)
                }
            } catch (_: SecurityException) {
            }
        }
    }

    private fun decodeOrThrow(frame: ByteArray) = FrameCodec.decode(frame)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f11d0a01-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CHAR_WRITE_UUID: UUID = UUID.fromString("f11d0a02-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CHAR_NOTIFY_UUID: UUID = UUID.fromString("f11d0a03-5c1e-4a9b-9d3f-2a7b8c6d5e4f")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU_REQUEST = 247
        private const val MAX_PEERS = 4
        private const val CONNECT_TIMEOUT_MS = 12_000L
        /** Must exceed the beacon interval: peers only speak once per delta. */
        internal const val LINK_TIMEOUT_MS = 180_000L
        internal const val SUPERVISOR_INTERVAL_MS = 15_000L
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }
}
