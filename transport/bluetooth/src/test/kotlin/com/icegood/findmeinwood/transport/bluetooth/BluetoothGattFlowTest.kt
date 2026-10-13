package com.icegood.findmeinwood.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.same
import org.mockito.kotlin.whenever

/** Fakes ONLY the Android BT stack (border). Domain logic runs for real. */
class BluetoothGattFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.BLUETOOTH))

    private fun <T> m(k: Class<T>): T = Mockito.mock(k)

    private fun wire(seq: Int) = FrameCodec.encode(
        EncryptedFrame(
            NetworkId(ByteArray(8)), MemberId(ByteArray(32) { 4 }), seq.toUInt(), 3u, 0L,
            ByteArray(12) { (seq + it).toByte() },
        ),
    )

    private fun field(target: Any, name: String): Any {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun callbackOf(target: Any, name: String): Any = field(target, name)

    @Test
    fun `server side - rx frames from write requests, tx via notifications`() = runBlocking<Unit> {
        val ctx = m(Context::class.java)
        val manager = m(BluetoothManager::class.java)
        val adapter = m(BluetoothAdapter::class.java)
        whenever(manager.adapter).thenReturn(adapter)
        Mockito.`when`(adapter.isEnabled).thenReturn(true)
        whenever(ctx.getSystemService(BluetoothManager::class.java)).thenReturn(manager)

        val wiring = Mockito.mock(BluetoothGattWiring::class.java)
        val gattServer = m(BluetoothGattServer::class.java)
        val service = m(BluetoothGattService::class.java)
        val notifyChar = m(BluetoothGattCharacteristic::class.java)
        val writeChar = m(BluetoothGattCharacteristic::class.java)
        Mockito.`when`(writeChar.uuid).thenReturn(BluetoothTransport.CHAR_WRITE_UUID)
        whenever(wiring.openServer(same(manager), any())).thenReturn(gattServer)
        Mockito.`when`(wiring.buildService()).thenReturn(service)
        Mockito.`when`(gattServer.getService(BluetoothTransport.SERVICE_UUID)).thenReturn(service)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_NOTIFY_UUID)).thenReturn(notifyChar)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_WRITE_UUID)).thenReturn(writeChar)
        Mockito.`when`(adapter.bluetoothLeAdvertiser).thenReturn(null)
        Mockito.`when`(adapter.bluetoothLeScanner).thenReturn(null)

        val t = BluetoothTransport(ctx, wiring)
        val events = t.start(cfg)

        val readyEv = withTimeout(5_000) {
            events.first { (it as? TransportEvent.StateChanged)?.state == RadioState.READY }
                as TransportEvent.StateChanged
        }
        assertEquals(RadioState.READY, readyEv.state)
        Mockito.verify(gattServer).addService(service)

        // HW event surface: GATT server callbacks
        @Suppress("UNCHECKED_CAST")
        val serverCb = callbackOf(t, "serverCallback") as android.bluetooth.BluetoothGattServerCallback
        val device = m(BluetoothDevice::class.java)
        Mockito.`when`(device.address).thenReturn("AA:BB:CC:DD:EE:FF")
        serverCb.onConnectionStateChange(device, 0, 2 /*STATE_CONNECTED*/)
        // Only a phone that subscribes to notifications is a link we send to.
        val cccd = m(android.bluetooth.BluetoothGattDescriptor::class.java)
        Mockito.`when`(cccd.uuid).thenReturn(BluetoothTransport.CCCD_UUID)
        assertTrue(t.send(wire(5)) is SendResult.Failed, "connected but not subscribed: no link")
        serverCb.onDescriptorWriteRequest(device, 2, cccd, false, true, 0, byteArrayOf(1, 0))

        // incoming framed frame delivered through write requests
        val wire0 = wire(5)
        @Suppress("UNCHECKED_CAST")
        val framers = field(t, "serverFramers") as java.util.concurrent.ConcurrentHashMap<String, FrameFramer.Reassembler>
        FrameFramer.frameToChunks(wire0, 8).forEachIndexed { i, chunk ->
            serverCb.onCharacteristicWriteRequest(device, i, writeChar, false, true, 0, chunk)
        }
        Mockito.verify(gattServer, atLeastOnce()).sendResponse(
            eq(device), anyInt(), eq(0), anyInt(), isNull(),
        )
        val got = withTimeout(5_000) {
            events.first { it is TransportEvent.FrameReceived } as TransportEvent.FrameReceived
        }
        assertEquals(5u, got.frame.seq)

        // A malformed frame from an unpaired device is dropped, never thrown from the callback.
        FrameFramer.frameToChunks(ByteArray(80) { 0x55 }, 20).forEachIndexed { i, chunk ->
            serverCb.onCharacteristicWriteRequest(device, 100 + i, writeChar, false, true, 0, chunk)
        }

        // send(): fans out to connected server clients via notifications
        val sent = t.send(wire0)
        assertTrue(sent is SendResult.Sent || sent.toString().contains("Sent"), "was $sent")
        Mockito.verify(gattServer, atLeastOnce()).notifyCharacteristicChanged(
            eq(device), same(notifyChar), org.mockito.ArgumentMatchers.anyBoolean(),
        )

        // mtu bookkeeping + disconnect cleanup
        serverCb.onMtuChanged(device, 185)
        serverCb.onDescriptorWriteRequest(device, 3, null, false, true, 0, byteArrayOf(1))
        serverCb.onExecuteWrite(device, 4, false)
        serverCb.onConnectionStateChange(device, 0, 0 /*DISCONNECTED*/)
        t.stop()
    }

    @Test
    fun `client side - scan connects, cccd marks ready, send pumps writes`() = runBlocking<Unit> {
        val ctx = m(Context::class.java)
        val manager = m(BluetoothManager::class.java)
        val adapter = m(BluetoothAdapter::class.java)
        whenever(manager.adapter).thenReturn(adapter)
        Mockito.`when`(adapter.isEnabled).thenReturn(true)
        whenever(ctx.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        Mockito.`when`(adapter.bluetoothLeAdvertiser).thenReturn(null)

        val wiring = Mockito.mock(BluetoothGattWiring::class.java)
        Mockito.`when`(wiring.openServer(any(), any())).thenReturn(null)
        var capturedOnResult: ((ScanResult) -> Unit)? = null
        whenever(wiring.startScan(same(adapter), any()))
            .thenAnswer { inv ->
                val onResult = inv.getArgument<(ScanResult) -> Unit>(1)
                capturedOnResult = onResult
                object : ScanCallback() {}
            }

        val gatt = m(BluetoothGatt::class.java)
        val service = m(BluetoothGattService::class.java)
        val notifyChar = m(BluetoothGattCharacteristic::class.java)
        val writeChar = m(BluetoothGattCharacteristic::class.java)
        val desc = m(BluetoothGattDescriptor::class.java)
        Mockito.`when`(writeChar.uuid).thenReturn(BluetoothTransport.CHAR_WRITE_UUID)
        Mockito.`when`(notifyChar.uuid).thenReturn(BluetoothTransport.CHAR_NOTIFY_UUID)
        Mockito.`when`(gatt.getService(BluetoothTransport.SERVICE_UUID)).thenReturn(service)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_NOTIFY_UUID)).thenReturn(notifyChar)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_WRITE_UUID)).thenReturn(writeChar)
        Mockito.`when`(notifyChar.getDescriptor(BluetoothTransport.CCCD_UUID)).thenReturn(desc)
        Mockito.`when`(gatt.writeDescriptor(desc)).thenReturn(true)
        Mockito.`when`(gatt.writeCharacteristic(writeChar)).thenReturn(true)

        val device = m(BluetoothDevice::class.java)
        Mockito.`when`(device.address).thenReturn("11:22:33:44:55:66")
        whenever(
            device.connectGatt(
                same(ctx), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                eq(BluetoothDevice.TRANSPORT_LE),
            ),
        ).thenAnswer { inv ->
            inv.getArgument<BluetoothGattCallback>(2)
                .onConnectionStateChange(gatt, 0, 2 /*CONNECTED*/)
            gatt
        }

        val t = BluetoothTransport(ctx, wiring)
        val events = t.start(cfg)
        kotlin.test.assertNotNull(capturedOnResult, "scanner must have been started")

        // scan hit -> connectGatt -> CONNECTED already propagated into clientGatt
        val result = m(ScanResult::class.java)
        Mockito.`when`(result.device).thenReturn(device)
        capturedOnResult!!.invoke(result)

        @Suppress("UNCHECKED_CAST")
        val clientCb = callbackOf(t, "clientCallback") as BluetoothGattCallback
        clientCb.onMtuChanged(gatt, 185, 0 /*SUCCESS*/)
        clientCb.onServicesDiscovered(gatt, 0 /*SUCCESS*/)
        Mockito.verify(gatt).setCharacteristicNotification(notifyChar, true)
        Mockito.verify(gatt).writeDescriptor(desc)

        // CCCD written -> edge ready -> READY state + pump armed
        val readyEv = withTimeout(5_000) {
            events.first { (it as? TransportEvent.StateChanged)?.state == RadioState.READY }
                as TransportEvent.StateChanged
        }
        assertEquals(RadioState.READY, readyEv.state)

        // send(): chunks go out over the mocked client link
        val w = meshWire(9)
        val sent = t.send(w)
        assertIs<SendResult.Sent>(sent)
        Mockito.verify(gatt, Mockito.atLeastOnce()).writeCharacteristic(writeChar)

        // drain: each completed write pumps the next chunk until queue empties
        repeat(6) { clientCb.onCharacteristicWrite(gatt, writeChar, 0) }
        Mockito.verify(gatt, Mockito.atLeast(1)).discoverServices()

        // disconnect path clears client link
        clientCb.onConnectionStateChange(gatt, 0, 0 /*DISCONNECTED*/)
        t.stop()
    }
}

private fun meshWire(n: Int): ByteArray = ByteArray(n) { it.toByte() }

class BluetoothTransportMeshTest {
    private lateinit var ctx: android.content.Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var wiring: BluetoothGattWiring
    private var onResult: ((android.bluetooth.le.ScanResult) -> Unit)? = null
    private val pending = mutableListOf<() -> Unit>()

    @BeforeTest
    fun setup() {
        ctx = Mockito.mock(android.content.Context::class.java)
        val manager = Mockito.mock(BluetoothManager::class.java)
        adapter = Mockito.mock(BluetoothAdapter::class.java)
        Mockito.`when`(manager.adapter).thenReturn(adapter)
        Mockito.`when`(adapter.isEnabled).thenReturn(true)
        Mockito.`when`(ctx.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        wiring = Mockito.mock(BluetoothGattWiring::class.java)
        Mockito.`when`(wiring.openServer(any(), any())).thenReturn(null)
        Mockito.`when`(wiring.startScan(same(adapter), any())).thenAnswer { inv ->
            onResult = inv.getArgument(1)
            object : ScanCallback() {}
        }
    }

    private fun gattFor(address: String, withService: Boolean = true): BluetoothGatt {
        val gatt = Mockito.mock(BluetoothGatt::class.java)
        val device = Mockito.mock(BluetoothDevice::class.java)
        Mockito.`when`(device.address).thenReturn(address)
        Mockito.`when`(gatt.device).thenReturn(device)
        if (withService) {
            val service = Mockito.mock(BluetoothGattService::class.java)
            val notify = Mockito.mock(BluetoothGattCharacteristic::class.java)
            val write = Mockito.mock(BluetoothGattCharacteristic::class.java)
            val desc = Mockito.mock(BluetoothGattDescriptor::class.java)
            Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_NOTIFY_UUID)).thenReturn(notify)
            Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_WRITE_UUID)).thenReturn(write)
            Mockito.`when`(notify.getDescriptor(BluetoothTransport.CCCD_UUID)).thenReturn(desc)
            Mockito.`when`(gatt.getService(BluetoothTransport.SERVICE_UUID)).thenReturn(service)
            Mockito.`when`(gatt.writeCharacteristic(write)).thenReturn(true)
            Mockito.`when`(gatt.writeDescriptor(desc)).thenReturn(true)
        }
        return gatt
    }

    private fun transport(): BluetoothTransport =
        BluetoothTransport(ctx, wiring, scheduleDelayed = { _: Long, block: () -> Unit -> pending.add(block) })

    private fun clientCallback(t: BluetoothTransport): BluetoothGattCallback {
        val f = BluetoothTransport::class.java.getDeclaredField("clientCallback")
        f.isAccessible = true
        return f.get(t) as BluetoothGattCallback
    }

    @Test
    fun `bluetooth off reports link down and recovers when switched on`() = runBlocking<Unit> {
        Mockito.`when`(adapter.isEnabled).thenReturn(false)
        val t = transport()
        val events = t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
            as kotlinx.coroutines.flow.SharedFlow<TransportEvent>
        val first = events.replayCache.filterIsInstance<TransportEvent.StateChanged>().last()
        assertEquals(RadioState.LINK_DOWN, first.state, "must not claim READY with Bluetooth off")
        Mockito.verify(wiring, Mockito.never()).startScan(any(), any())
        assertIs<SendResult.Failed>(t.send(meshWire(4)))

        // User turns Bluetooth on: the next supervisor tick brings the radios up.
        Mockito.`when`(adapter.isEnabled).thenReturn(true)
        pending.removeAt(0).invoke()
        val now = events.replayCache.filterIsInstance<TransportEvent.StateChanged>().last()
        assertEquals(RadioState.READY, now.state)
        Mockito.verify(wiring).startScan(same(adapter), any())

        // ...and switched off again: links dropped, LINK_DOWN, supervision continues.
        Mockito.`when`(adapter.isEnabled).thenReturn(false)
        pending.removeAt(0).invoke()
        assertEquals(
            RadioState.LINK_DOWN,
            events.replayCache.filterIsInstance<TransportEvent.StateChanged>().last().state,
        )
        assertTrue(pending.isNotEmpty(), "supervisor keeps running while Bluetooth is off")
        t.stop()
    }

    @Test
    fun `multiple peers each get their own link`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val a = gattFor("AA:00:00:00:00:01")
        val b = gattFor("AA:00:00:00:00:02")
        cb.onConnectionStateChange(a, 0, 2)
        cb.onConnectionStateChange(b, 0, 2)
        cb.onMtuChanged(a, 185, 0)
        cb.onMtuChanged(b, 100, 0)
        cb.onServicesDiscovered(a, 0)
        cb.onServicesDiscovered(b, 0)
        assertIs<SendResult.Sent>(t.send(meshWire(9)))
        // one chunk queued per peer, both links written
        Mockito.verify(a, Mockito.atLeastOnce()).writeCharacteristic(any())
        Mockito.verify(b, Mockito.atLeastOnce()).writeCharacteristic(any())
        t.stop()
    }

    @Test
    fun `peer without our service is disconnected and releases the slot`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val stranger = gattFor("BB:00:00:00:00:09", withService = false)
        cb.onConnectionStateChange(stranger, 0, 2)
        cb.onServicesDiscovered(stranger, 0) // no service -> release
        Mockito.verify(stranger).disconnect()
        assertIs<SendResult.Failed>(t.send(meshWire(4))) // no usable peer left
        t.stop()
    }

    @Test
    fun `watchdog reclaims a slot that never completes`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val stuck = gattFor("CC:00:00:00:00:07")
        cb.onConnectionStateChange(stuck, 0, 2) // connected, never discovered
        assertTrue(pending.isNotEmpty(), "watchdog must be scheduled")
        pending.toList().forEach { it.invoke() }
        assertIs<SendResult.Failed>(t.send(meshWire(4))) // slot reclaimed
        t.stop()
    }

    @Test
    fun `supervisor keeps running until the transport stops`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        assertTrue(pending.isNotEmpty(), "supervisor must be scheduled")
        pending.toList().forEach { it.invoke() }
        assertTrue(pending.isNotEmpty(), "supervisor re-arms itself")
        t.stop()
        // supervisor must not re-arm after stop
        val before = pending.size
        pending.toList().forEach { it.invoke() }
        assertEquals(before, pending.size, "supervisor must stop when the transport stops")
    }

    @Test
    fun `disconnect frees the peer slot`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val g = gattFor("DD:00:00:00:00:03")
        cb.onConnectionStateChange(g, 0, 2)
        cb.onMtuChanged(g, 185, 0)
        cb.onServicesDiscovered(g, 0)
        assertIs<SendResult.Sent>(t.send(meshWire(4)))
        cb.onConnectionStateChange(g, 0, 0) // disconnected
        assertIs<SendResult.Failed>(t.send(meshWire(4)))
        t.stop()
    }
}

class BluetoothLinkSupervisionTest {
    private var now = 1_000_000L
    private lateinit var ctx: android.content.Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var wiring: BluetoothGattWiring

    @BeforeTest
    fun setup() {
        ctx = Mockito.mock(android.content.Context::class.java)
        val manager = Mockito.mock(BluetoothManager::class.java)
        adapter = Mockito.mock(BluetoothAdapter::class.java)
        Mockito.`when`(manager.adapter).thenReturn(adapter)
        Mockito.`when`(adapter.isEnabled).thenReturn(true)
        Mockito.`when`(ctx.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        wiring = Mockito.mock(BluetoothGattWiring::class.java)
        Mockito.`when`(wiring.openServer(any(), any())).thenReturn(null)
        Mockito.`when`(wiring.startScan(same(adapter), any())).thenReturn(object : ScanCallback() {})
    }

    private fun gatt(address: String): BluetoothGatt {
        val g = Mockito.mock(BluetoothGatt::class.java)
        val device = Mockito.mock(BluetoothDevice::class.java)
        Mockito.`when`(device.address).thenReturn(address)
        Mockito.`when`(g.device).thenReturn(device)
        val service = Mockito.mock(BluetoothGattService::class.java)
        val notify = Mockito.mock(BluetoothGattCharacteristic::class.java)
        val write = Mockito.mock(BluetoothGattCharacteristic::class.java)
        val desc = Mockito.mock(BluetoothGattDescriptor::class.java)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_NOTIFY_UUID)).thenReturn(notify)
        Mockito.`when`(service.getCharacteristic(BluetoothTransport.CHAR_WRITE_UUID)).thenReturn(write)
        Mockito.`when`(notify.getDescriptor(BluetoothTransport.CCCD_UUID)).thenReturn(desc)
        Mockito.`when`(g.getService(BluetoothTransport.SERVICE_UUID)).thenReturn(service)
        Mockito.`when`(g.writeDescriptor(desc)).thenReturn(true)
        Mockito.`when`(g.writeCharacteristic(write)).thenReturn(true)
        return g
    }

    private fun transport(): BluetoothTransport =
        BluetoothTransport(ctx, wiring, { _: Long, _: () -> Unit -> }, { now }, 60_000L)

    private fun clientCallback(t: BluetoothTransport): BluetoothGattCallback {
        val f = BluetoothTransport::class.java.getDeclaredField("clientCallback")
        f.isAccessible = true
        return f.get(t) as BluetoothGattCallback
    }

    @Test
    fun `silent peer is dropped and sends then fail`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val g = gatt("EE:00:00:00:00:01")
        cb.onConnectionStateChange(g, 0, 2)
        cb.onMtuChanged(g, 185, 0)
        cb.onServicesDiscovered(g, 0)
        assertIs<SendResult.Sent>(t.send(meshWire(4)))

        now += 120_000 // nothing heard from the peer for two timeouts
        assertIs<SendResult.Failed>(t.send(meshWire(4)))
        Mockito.verify(g).disconnect()
        t.stop()
    }

    @Test
    fun `peer that keeps talking is kept`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
        val cb = clientCallback(t)
        val g = gatt("EE:00:00:00:00:02")
        cb.onConnectionStateChange(g, 0, 2)
        cb.onMtuChanged(g, 185, 0)
        cb.onServicesDiscovered(g, 0)

        val notify = Mockito.mock(BluetoothGattCharacteristic::class.java)
        val chunk = ByteArray(6)
        chunk[0] = 0; chunk[1] = 4
        chunk[2] = 0x46; chunk[3] = 0x4D
        Mockito.`when`(notify.value).thenReturn(chunk)
        now += 30_000
        cb.onCharacteristicChanged(g, notify) // peer is alive
        assertIs<SendResult.Sent>(t.send(meshWire(4)))
        t.stop()
    }

    @Test
    fun `a second gatt to the same phone is closed and its notifications ignored`() = runBlocking {
        val t = transport()
        val events = t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet()))
            as kotlinx.coroutines.flow.SharedFlow<TransportEvent>
        val cb = clientCallback(t)
        val first = gatt("EE:00:00:00:00:03")
        val dup = gatt("EE:00:00:00:00:03")
        cb.onConnectionStateChange(first, 0, 2)
        cb.onConnectionStateChange(dup, 0, 2)
        Mockito.verify(dup).close()
        Mockito.verify(first, Mockito.never()).close()

        // A management frame ("FM" + mgmt version) arrives on both: delivered once.
        val wire = byteArrayOf(0x46, 0x4D, 3 /* mgmt version */, 1, 9)
        val chunk = FrameFramer.frameToChunks(wire, 64).single()
        val notify = Mockito.mock(BluetoothGattCharacteristic::class.java)
        Mockito.`when`(notify.uuid).thenReturn(BluetoothTransport.CHAR_NOTIFY_UUID)
        Mockito.`when`(notify.value).thenReturn(chunk)
        cb.onCharacteristicChanged(dup, notify)
        cb.onCharacteristicChanged(first, notify)
        assertEquals(1, events.replayCache.count { it is TransportEvent.MgmtFrameReceived })

        // The duplicate going away leaves the real link up.
        cb.onConnectionStateChange(dup, 0, 0)
        cb.onMtuChanged(first, 185, 0)
        cb.onServicesDiscovered(first, 0)
        assertIs<SendResult.Sent>(t.send(meshWire(4)))
        t.stop()
    }
}
