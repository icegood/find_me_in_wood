package app.findmeinwood.transport.bluetooth

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
import app.findmeinwood.core.crypto.FrameCodec
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.TransportEvent
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
        val w = wire(9)
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
