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
import com.icegood.findmeinwood.core.crypto.BleAdvert
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.transport.api.DebugLog
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
    /** GATT server, advertising and scanning are active (only possible while Bluetooth is on). */
    @Volatile private var radiosUp = false

    private val manager: BluetoothManager? get() = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: AdvertiseCallback? = null
    private var scannerCallback: ScanCallback? = null

    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val serverDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val serverFramers = ConcurrentHashMap<String, FrameFramer.Reassembler>()
    /** One reassembler per central link: chunks from different peers must never mix. */
    private val clientFramers = ConcurrentHashMap<String, FrameFramer.Reassembler>()

    /** One outgoing chunk queue per peer link (a mesh has several links at once). */
    private val clientQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val clientWriting = ConcurrentHashMap.newKeySet<String>()
    /** One central link per peer address: a mesh needs more than a single connection. */
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    /** Addresses with a connectGatt in flight (not in [clients] yet). */
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val clientMtu = ConcurrentHashMap<String, Int>()
    private val clientReady = ConcurrentHashMap.newKeySet<String>()
    private val lastInbound = ConcurrentHashMap<String, Long>()
    /** Last signal strength per peer address (scan results + periodic readRemoteRssi). */
    private val rssi = ConcurrentHashMap<String, RssiSample>()
    private val lock = Any()

    /** Members-only secret (null: connect to anyone with our service — tests, old setups). */
    @Volatile private var secret: ByteArray? = null
    /** Secrets of older network keys: members that missed a rekey still advertise those. */
    @Volatile private var olderSecrets: List<ByteArray> = emptyList()
    @Volatile private var listening = false
    /** Network info advertised to new people; null while not findable. */
    @Volatile private var findable: ByteArray? = null
    private var advertisedWindow = Long.MIN_VALUE
    private var advertisedInfo: ByteArray? = null
    private var tagWindow = Long.MIN_VALUE
    private var memberTags: List<ByteArray> = emptyList()
    /** Scan continuously until then (start, link changes); afterwards in short bursts. */
    @Volatile private var fastScanUntil = 0L
    @Volatile private var scanning = false
    private var scanStartedAt = 0L
    private var nextBurstAt = 0L
    private var lastLinkCount = 0
    private var supervisorTicks = 0
    private val lastAdvertSeen = ConcurrentHashMap<String, Long>()
    private val lastScanLog = ConcurrentHashMap<String, Long>()

    override fun start(config: SessionConfig): Flow<TransportEvent> {
        if (!running) {
            secret = config.groupSecret
            listening = config.listening
            fastScanUntil = clock() + FAST_SCAN_MS
            running = true
            bringUp()
            armSupervisor()
        }
        return events
    }

    override fun setGroupSecrets(current: ByteArray, older: List<ByteArray>) {
        synchronized(this) {
            if (current.contentEquals(secret) && older.size == olderSecrets.size) return
            secret = current
            olderSecrets = older
            tagWindow = Long.MIN_VALUE // recompute the codes
        }
        // Advertise the new members code at once.
        if (radiosUp) refreshAdvert(clock(), force = true)
    }

    override fun setFindable(info: ByteArray?) {
        findable = info
        if (radiosUp) refreshAdvert(clock())
    }

    override fun connect(radioPeerId: String) {
        val device = runCatching { adapter?.getRemoteDevice(radioPeerId) }.getOrNull() ?: return
        connectTo(device)
    }

    /**
     * Starts GATT server, advertising and scanning — only when Bluetooth is really on.
     * With Bluetooth off (or only in "BLE scanning always available" mode) the stack
     * refuses scans and has no advertiser, so report LINK_DOWN instead of a false READY;
     * the supervisor retries until the user turns Bluetooth on.
     */
    private fun bringUp() {
        if (radiosUp) return
        if (adapter?.isEnabled != true) {
            ready = false
            emit(RadioState.LINK_DOWN)
            return
        }
        try {
            startPeripheral()
            startCentral()
            radiosUp = true
            ready = true
            emit(RadioState.READY)
        } catch (e: Exception) {
            // Transport contract: start must not throw for normal failures
            // (missing radios/permissions surface as LINK_DOWN).
            ready = false
            emit(RadioState.LINK_DOWN)
        }
    }

    /** Bluetooth was switched off under us: drop every link and wait for it to return. */
    private fun bringDown() {
        radiosUp = false
        ready = false
        clients.values.forEach { g -> try { g.disconnect(); g.close() } catch (_: Exception) {} }
        clients.clear(); clientMtu.clear(); clientReady.clear(); clientFramers.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        advertiser = null
        advertisedWindow = Long.MIN_VALUE
        scannerCallback = null
        scanning = false
        synchronized(lock) { clientQueues.clear(); clientWriting.clear() }
        serverFramers.clear(); serverDevices.clear(); serverMtu.clear()
        emit(RadioState.LINK_DOWN)
    }

    override suspend fun stop() {
        running = false
        ready = false
        radiosUp = false
        wiring.stopScan(adapter, scannerCallback)
        scannerCallback = null
        scanning = false
        wiring.stopAdvertising(adapter, advertiser)
        advertiser = null
        advertisedWindow = Long.MIN_VALUE
        clients.values.forEach { g -> try { g.disconnect(); g.close() } catch (_: Exception) {} }
        clients.clear(); clientMtu.clear(); clientReady.clear(); lastInbound.clear(); connecting.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(lock) { clientQueues.clear(); clientWriting.clear() }
        serverFramers.clear(); serverDevices.clear(); serverMtu.clear()
        clientFramers.clear()
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
                synchronized(lock) { clientWriting.remove(address); clientQueues.remove(address) }
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
                clientWriting.remove(key); clientFramers.remove(key)
            }
            dead
        }
        serverDevices.removeIf { d ->
            val last = lastInbound[d.address] ?: now
            val dead = now - last > linkTimeoutMs
            if (dead) {
                DebugLog.d { "Bluetooth server link ${d.address} silent: dropped" }
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
        refreshAdvert(clock(), force = true)
    }

    /**
     * (Re)starts advertising when the members code rolls over or findability changes. A
     * listening phone advertises nothing: it only looks, and connects itself to ask.
     */
    @Synchronized
    private fun refreshAdvert(now: Long, force: Boolean = false) {
        if (listening) return
        val w = BleAdvert.window(now)
        val info = findable
        if (!force && w == advertisedWindow && info.contentEquals(advertisedInfo)) return
        wiring.stopAdvertising(adapter, advertiser)
        advertiser = wiring.startAdvertising(adapter, secret?.let { BleAdvert.memberTag(it, w) }, info)
        advertisedWindow = w
        advertisedInfo = info
    }

    /** The members code of this window and its neighbours, cached per window. */
    @Synchronized
    private fun isMemberTag(tag: ByteArray?, now: Long): Boolean {
        val s = secret ?: return false
        val w = BleAdvert.window(now)
        if (w != tagWindow) {
            memberTags = (listOf(s) + olderSecrets).flatMap { k -> (w - 1..w + 1).map { BleAdvert.memberTag(k, it) } }
            tagWindow = w
        }
        return tag != null && memberTags.any { it.contentEquals(tag) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            DebugLog.d { "Bluetooth server link ${device.address} ${if (newState == BluetoothProfile.STATE_CONNECTED) "up" else "down"}" }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Not a link yet: it becomes one when the phone subscribes to notifications
                // (our own outgoing connection shows up here too, and must get no traffic).
                lastInbound[device.address] = clock()
            } else {
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
                .getOrPut(device.address) { framer() }
                .offer(value) ?: return
            emitFrame(frame)
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
            // The peer subscribed to our notifications: we can reach it now.
            if (device != null && descriptor?.uuid == CCCD_UUID) {
                serverDevices += device
                // Counts from now: a subscriber that falls silent (e.g. it restarted under a
                // new address) is dropped after the link timeout.
                lastInbound[device.address] = clock()
                DebugLog.d { "Bluetooth server link ${device.address} subscribed" }
                events.tryEmit(TransportEvent.LinkUp(device.address, id))
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            if (device != null) gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
            )
        }
    }

    private val scanConsumer: (android.bluetooth.le.ScanResult) -> Unit = { result ->
        val address = result.device.address
        val now = clock()
        rssi[address] = RssiSample(result.rssi, now)
        val parsed = BleAdvert.parseRecord(result.scanRecord?.bytes)
        parsed.info?.let { info ->
            if (now - (lastAdvertSeen[address] ?: 0L) >= ADVERT_EVENT_MS) {
                lastAdvertSeen[address] = now
                events.tryEmit(TransportEvent.NetworkAdvertSeen(address, info, id))
            }
        }
        // Only our own members: phones of other networks never take a connection slot,
        // and a listening phone connects only when asked to (connect()).
        val wanted = when {
            listening -> false
            secret == null -> true
            else -> isMemberTag(parsed.tag, now)
        }
        if (now - (lastScanLog[address] ?: 0L) >= ADVERT_EVENT_MS * 2) {
            lastScanLog[address] = now
            DebugLog.d {
                "Bluetooth saw $address: code=${parsed.tag?.let { if (isMemberTag(it, now)) "member" else "other" } ?: "none"}" +
                    " info=${parsed.info != null} raw=${result.scanRecord?.bytes?.size ?: -1}B connect=$wanted"
            }
        }
        if (wanted) connectTo(result.device)
    }

    private fun connectTo(device: BluetoothDevice) {
        val address = device.address
        // A scan reports the same phone many times a second: one connection attempt at a
        // time, or each report opens another BluetoothGatt to it.
        if (running && clients.size < MAX_PEERS && !clients.containsKey(address) && connecting.add(address)) {
            events.tryEmit(TransportEvent.PeerRadioVisible(address, id))
            scheduleDelayed(CONNECT_TIMEOUT_MS) { connecting.remove(address) }
            try {
                device.connectGatt(
                    context, false, clientCallback, BluetoothDevice.TRANSPORT_LE,
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun startCentral() {
        startScanNow(clock())
    }

    private fun startScanNow(now: Long) {
        wiring.stopScan(adapter, scannerCallback)
        scannerCallback = wiring.startScan(adapter, scanConsumer)
        scanning = true
        scanStartedAt = now
    }

    private fun stopScanNow() {
        wiring.stopScan(adapter, scannerCallback)
        scannerCallback = null
        scanning = false
    }

    /**
     * Battery: scanning is the costly part. Continuous for [FAST_SCAN_MS] after the start
     * and after any link appears or drops (a member is missing or just came), always while
     * listening; otherwise a [BURST_MS] burst every [SLOW_PERIOD_MS] (enough to notice a
     * new member). Existing links never depend on scanning. Also rolls the members code.
     */
    internal fun tickRadios(now: Long = clock()) {
        if (!running || !radiosUp) return
        val links = clients.size + serverDevices.size
        if (links != lastLinkCount) {
            lastLinkCount = links
            fastScanUntil = now + FAST_SCAN_MS
        }
        val fast = listening || now < fastScanUntil
        when {
            // Nothing connected: re-arm the scan now and then, it can stall silently.
            fast && scanning && links == 0 && now - scanStartedAt >= RESCAN_MS -> startScanNow(now)
            fast && !scanning -> {
                DebugLog.d { "Bluetooth scan: continuous ($links links)" }
                startScanNow(now)
            }
            !fast && scanning && now - scanStartedAt >= BURST_MS -> {
                stopScanNow()
                nextBurstAt = now + SLOW_PERIOD_MS - BURST_MS
                DebugLog.d { "Bluetooth scan: paused, next burst in ${(SLOW_PERIOD_MS - BURST_MS) / 1000} s ($links links)" }
            }
            !fast && !scanning && now >= nextBurstAt -> startScanNow(now)
        }
        refreshAdvert(now)
    }

    /**
     * Link supervisor: brings the radios up or down with Bluetooth, drops dead links,
     * refreshes signal strength and runs the scan/advertising policy ([tickRadios]).
     */
    private fun armSupervisor() {
        scheduleDelayed(SUPERVISOR_INTERVAL_MS) {
            if (running) {
                val wasUp = radiosUp
                if (!wasUp) bringUp() else if (adapter?.isEnabled != true) bringDown()
                if (wasUp && radiosUp) {
                    pruneStaleLinks()
                    // Fresh signal strength for the link metrics (answered in onReadRemoteRssi).
                    if (supervisorTicks++ % RSSI_EVERY_TICKS == 0) {
                        clients.values.forEach { g -> try { g.readRemoteRssi() } catch (_: Exception) {} }
                    }
                    tickRadios()
                }
                // Keep supervising while Bluetooth is off too, so it comes back on its own.
                armSupervisor()
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val g = gatt ?: return
            val address = g.key()
            connecting.remove(address)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (clients[address].let { it != null && it !== g }) {
                    // Already linked to this phone: a duplicate would double every frame.
                    DebugLog.d { "Bluetooth duplicate link to $address closed" }
                    try { g.disconnect() } catch (_: Exception) {}
                    try { g.close() } catch (_: Exception) {}
                    return
                }
                clients[address] = g
                lastInbound[address] = clock()
                DebugLog.d { "Bluetooth client link $address up" }
                try { g.requestMtu(MTU_REQUEST) } catch (_: SecurityException) {}
                // if this peer never completes discovery, free the slot so the scan retries
                watchdog(address)
            } else {
                if (clients[address].let { it != null && it !== g }) {
                    try { g.close() } catch (_: Exception) {}
                    return // a duplicate went away: the real link stays
                }
                clients.remove(address)
                DebugLog.d { "Bluetooth client link $address down" }
                clientMtu.remove(address)
                clientReady.remove(address)
                clientFramers.remove(address)
                // A stale "write in flight" flag would stall this peer's queue forever
                // once it reconnects (no onCharacteristicWrite ever comes for it).
                synchronized(lock) { clientWriting.remove(address); clientQueues.remove(address) }
                try { g.close() } catch (_: Exception) {}
                ready = running || serverDevices.isNotEmpty()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssiDbm: Int, status: Int) {
            val g = gatt ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) rssi[g.key()] = RssiSample(rssiDbm, clock())
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
            gatt?.let { events.tryEmit(TransportEvent.LinkUp(it.key(), id)) }
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
            val key = gatt?.key() ?: return
            lastInbound[key] = clock()
            // A second BluetoothGatt to the same phone gets every notification too: feeding
            // both copies into one reassembler garbles every frame (discovery never worked).
            if (clients[key] !== gatt) {
                DebugLog.d { "Bluetooth notify from a duplicate link to $key ignored" }
                return
            }
            clientFramers.getOrPut(key) { framer() }.offer(value)?.let(::emitFrame)
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

    /**
     * The GATT server accepts writes from any nearby device (no pairing), so a malformed
     * frame must be dropped here: throwing from a binder callback crashes the app.
     */
    /** Every frame (mesh or management) starts with the "FM" magic: lets a stream resync. */
    private fun framer() = FrameFramer.Reassembler(FrameCodec.MAGIC)

    private fun emitFrame(frame: ByteArray) {
        DebugLog.d { "Bluetooth rx frame ${frame.size}B mgmt=${MgmtCodec.isMgmt(frame)}" }
        if (MgmtCodec.isMgmt(frame)) {
            events.tryEmit(TransportEvent.MgmtFrameReceived(frame, id))
            return
        }
        val decoded = runCatching { FrameCodec.decode(frame) }.getOrNull() ?: return
        events.tryEmit(TransportEvent.FrameReceived(decoded, id))
    }

    /** Signal strength of one peer link and when it was measured. */
    data class RssiSample(val dbm: Int, val atMs: Long)

    /**
     * Link metrics for diagnostics: last RSSI per peer address (kept after the link drops,
     * so the value just before a loss stays visible) and the current link counts.
     */
    fun rssiByLink(): Map<String, RssiSample> = HashMap(rssi)
    fun centralLinkCount(): Int = clients.size
    fun peripheralLinkCount(): Int = serverDevices.size

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
        internal const val SUPERVISOR_INTERVAL_MS = 5_000L
        private const val RSSI_EVERY_TICKS = 3
        internal const val FAST_SCAN_MS = 120_000L
        internal const val BURST_MS = 5_000L
        internal const val SLOW_PERIOD_MS = 60_000L
        internal const val RESCAN_MS = 15_000L
        /** A findable network is reported at most this often per phone. */
        private const val ADVERT_EVENT_MS = 5_000L
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }
}
