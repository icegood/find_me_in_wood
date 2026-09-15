package com.icegood.findmeinwood.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid

/**
 * Radio-stack wiring (platform/HW border surface): opens the GATT server, builds the
 * service tree, starts advertising and LE scanning. Thin platform calls only —
 * verified on devices; excluded from the unit-coverage gate.
 */
@SuppressLint("MissingPermission") // same gating as BluetoothTransport (app layer requests)
internal class BluetoothGattWiring(private val context: Context) {

    fun openServer(manager: BluetoothManager?, callback: BluetoothGattServerCallback): BluetoothGattServer? =
        manager!!.openGattServer(context, callback)

    fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            BluetoothTransport.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val write = BluetoothGattCharacteristic(
            BluetoothTransport.CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            BluetoothTransport.CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(BluetoothTransport.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        service.addCharacteristic(write)
        service.addCharacteristic(notify)
        return service
    }

    fun startAdvertising(adapter: BluetoothAdapter?): AdvertiseCallback? =
        adapter?.bluetoothLeAdvertiser?.let { adv ->
            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                }
                override fun onStartFailure(errorCode: Int) {
                }
            }
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .build(),
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(BluetoothTransport.SERVICE_UUID))
                    .build(),
                cb,
            )
            cb
        }

    /**
     * Some controllers (e.g. Huawei P30 Pro) report 0 offloaded scan filters, so a
     * hardware service-UUID filter silently returns nothing. Use the hardware filter only
     * where it is supported and always match the service UUID in software as well.
     */
    fun startScan(adapter: BluetoothAdapter?, onResult: (android.bluetooth.le.ScanResult) -> Unit): ScanCallback? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val self = ParcelUuid(BluetoothTransport.SERVICE_UUID)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                // Only peers advertising our service: connecting to unrelated BLE
                // devices (watches, sensors) wastes the limited connection slots.
                if (result.scanRecord?.serviceUuids?.contains(self) == true) onResult(result)
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
                results?.forEach { r ->
                    val uuids = r.scanRecord?.serviceUuids
                    if (uuids?.contains(self) == true) onResult(r)
                }
            }
        }
        val filters = if (adapter.isOffloadedFilteringSupported == true) {
            listOf(ScanFilter.Builder().setServiceUuid(self).build())
        } else {
            emptyList()
        }
        scanner.startScan(
            filters,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            cb,
        )
        return cb
    }
}
