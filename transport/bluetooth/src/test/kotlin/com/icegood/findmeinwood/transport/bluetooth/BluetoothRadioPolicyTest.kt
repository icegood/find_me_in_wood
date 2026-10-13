package com.icegood.findmeinwood.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.icegood.findmeinwood.core.crypto.BleAdvert
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.same
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Members-only connections, findability and the scan duty cycle (battery). */
class BluetoothRadioPolicyTest {
    private lateinit var ctx: android.content.Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var wiring: BluetoothGattWiring
    private var onResult: ((ScanResult) -> Unit)? = null
    private var now = 1_700_000_000_000L
    private val secret = ByteArray(32) { 5 }
    private val advertised = mutableListOf<Pair<ByteArray?, ByteArray?>>()

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
        Mockito.`when`(wiring.startAdvertising(same(adapter), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            advertised += inv.getArgument<ByteArray?>(1) to inv.getArgument<ByteArray?>(2)
            object : AdvertiseCallback() {}
        }
    }

    private fun transport() = BluetoothTransport(ctx, wiring, { _: Long, _: () -> Unit -> }, { now }, 60_000L)

    private fun device(address: String): BluetoothDevice {
        val d = Mockito.mock(BluetoothDevice::class.java)
        Mockito.`when`(d.address).thenReturn(address)
        Mockito.`when`(d.connectGatt(any(), Mockito.anyBoolean(), any(), eq(BluetoothDevice.TRANSPORT_LE)))
            .thenReturn(Mockito.mock(BluetoothGatt::class.java))
        return d
    }

    private fun manuf(data: ByteArray) =
        byteArrayOf((data.size + 3).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + data

    private fun scan(d: BluetoothDevice, raw: ByteArray?) {
        val r = Mockito.mock(ScanResult::class.java)
        Mockito.`when`(r.device).thenReturn(d)
        val rec = Mockito.mock(ScanRecord::class.java)
        Mockito.`when`(rec.bytes).thenReturn(raw)
        Mockito.`when`(r.scanRecord).thenReturn(rec)
        onResult!!.invoke(r)
    }

    private fun connects(d: BluetoothDevice) =
        Mockito.mockingDetails(d).invocations.count { it.method.name == "connectGatt" }

    private fun scans() = Mockito.mockingDetails(wiring).invocations.count { it.method.name == "startScan" }
    /** Whether the last scan call left a scan running. */
    private fun scanning() = Mockito.mockingDetails(wiring).invocations
        .last { it.method.name == "startScan" || it.method.name == "stopScan" }.method.name == "startScan"

    private fun scanStops() = Mockito.mockingDetails(wiring).invocations.count { it.method.name == "stopScan" }

    @Test
    fun `members connect only to phones showing this window's code`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet(), groupSecret = secret))
        val member = device("AA:00:00:00:00:01")
        val stranger = device("AA:00:00:00:00:02")
        val silent = device("AA:00:00:00:00:03")
        scan(member, manuf(BleAdvert.memberTag(secret, BleAdvert.window(now))))
        scan(stranger, manuf(BleAdvert.memberTag(ByteArray(32) { 6 }, BleAdvert.window(now))))
        scan(silent, null)
        assertEquals(1, connects(member))
        assertEquals(0, connects(stranger), "another network's phone takes no slot")
        assertEquals(0, connects(silent))
        // Own advertisement: the members code, no network info until findable.
        assertContentEquals(BleAdvert.memberTag(secret, BleAdvert.window(now)), advertised.single().first)
        assertNull(advertised.single().second)
        t.stop()
    }

    @Test
    fun `findable adds the network info, and the code rolls over each window`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet(), groupSecret = secret))
        val info = BleAdvert.encodeInfo(BleAdvert.Info(NetworkId(ByteArray(8)), JoinPolicy.PRIVATE, "test"))
        t.setFindable(info)
        assertContentEquals(info, advertised.last().second)
        t.setFindable(info) // unchanged: no restart
        assertEquals(2, advertised.size)
        t.setFindable(null)
        assertNull(advertised.last().second)
        now += BleAdvert.WINDOW_MS
        t.tickRadios(now)
        assertContentEquals(BleAdvert.memberTag(secret, BleAdvert.window(now)), advertised.last().first)
        assertEquals(4, advertised.size)
        t.stop()
    }

    @Test
    fun `listening reports findable networks, never advertises or connects by itself`() = runBlocking {
        val t = transport()
        val events = t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet(), listening = true))
            as SharedFlow<TransportEvent>
        val member = device("AA:00:00:00:00:04")
        val info = BleAdvert.encodeInfo(BleAdvert.Info(NetworkId(ByteArray(8) { 1 }), JoinPolicy.PRIVATE, "test"))
        scan(member, manuf(ByteArray(6)) + manuf(info))
        scan(member, manuf(ByteArray(6)) + manuf(info)) // again at once: reported once
        val seen = events.replayCache.filterIsInstance<TransportEvent.NetworkAdvertSeen>()
        assertEquals(1, seen.size)
        assertContentEquals(info, seen.single().info)
        assertEquals("AA:00:00:00:00:04", seen.single().radioPeerId)
        assertEquals(0, connects(member))
        assertTrue(advertised.isEmpty(), "a listening phone advertises nothing")
        // Asking to join opens a link to that phone.
        Mockito.`when`(adapter.getRemoteDevice("AA:00:00:00:00:04")).thenReturn(member)
        t.connect("AA:00:00:00:00:04")
        assertEquals(1, connects(member))
        t.connect("AA:00:00:00:00:04") // already connecting
        assertEquals(1, connects(member))
        t.stop()
    }

    @Test
    fun `scan is continuous after a change, then bursts`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet(), groupSecret = secret))
        assertEquals(1, scans())
        now += BluetoothTransport.FAST_SCAN_MS - 1
        t.tickRadios(now)
        assertTrue(scanning(), "still in the fast phase (re-armed, never paused)")
        now += 1 + BluetoothTransport.BURST_MS // the running scan ends as a burst
        val stops = scanStops()
        t.tickRadios(now)
        assertTrue(scanStops() > stops, "fast phase over: scan paused")
        val paused = scans()
        now += BluetoothTransport.SLOW_PERIOD_MS - BluetoothTransport.BURST_MS - 1
        t.tickRadios(now)
        assertEquals(paused, scans(), "paused until the next burst")
        now += 1
        t.tickRadios(now)
        assertEquals(paused + 1, scans(), "burst")
        now += BluetoothTransport.BURST_MS
        t.tickRadios(now)
        assertTrue(scanStops() > stops + 1, "burst over")
        // A link appearing (here: a phone connecting to our server) makes it fast again.
        val server = BluetoothTransport::class.java.getDeclaredField("serverCallback").let { it.isAccessible = true; it.get(t) }
            as android.bluetooth.BluetoothGattServerCallback
        server.onConnectionStateChange(device("AA:00:00:00:00:05"), 0, 2)
        t.tickRadios(now)
        assertEquals(paused + 2, scans(), "link change: scanning continuously again")
        t.stop()
    }

    @Test
    fun `listening scans continuously and re-arms a quiet scan`() = runBlocking {
        val t = transport()
        t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet(), listening = true))
        now += BluetoothTransport.FAST_SCAN_MS * 3
        t.tickRadios(now)
        assertEquals(2, scans(), "nothing connected for a while: scan restarted, never paused")
        t.stop()
    }
}
