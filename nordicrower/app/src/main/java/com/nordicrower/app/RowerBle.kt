package com.nordicrower.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class RowerBle(context: Context, private val name: String) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter ?: error("Bluetooth adapter unavailable")
    private val advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE advertising unavailable; enable Bluetooth")
    private val originalName = adapter.name
    private val appContext = context.applicationContext
    private val registered = CompletableDeferred<Unit>()
    private val advertised = CompletableDeferred<Unit>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val cccds = ConcurrentHashMap<String, ConcurrentHashMap<UUID, Int>>()
    private var server: BluetoothGattServer? = null
    @Volatile private var closed = false
    private val rowerData = characteristic(0x2ad1, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private val training = characteristic(0x2ad3,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private val machineStatus = characteristic(0x2ada, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private val values = mapOf(
        uuid(0x2acc) to ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1 shl 14).putInt(0).array(),
        uuid(0x2ad3) to byteArrayOf(0, 1)
    )

    private val advertisement = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            RowerLog.i("BLE", "Advertising $name; read-only FTMS 1826, rower 2AD1, no control point")
            advertised.complete(Unit)
        }
        override fun onStartFailure(errorCode: Int) {
            advertised.completeExceptionally(IllegalStateException("Advertising failed: code=$errorCode"))
        }
    }
    private val callbacks = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) registered.complete(Unit)
            else registered.completeExceptionally(IllegalStateException("GATT service registration failed: $status"))
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) devices[device.address] = device
            else { devices.remove(device.address); cccds.remove(device.address) }
            RowerLog.clients = devices.size
            RowerLog.i("BLE", "Connection state=$newState status=$status clients=${devices.size}")
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                characteristic: BluetoothGattCharacteristic) {
            RowerLog.d("BLE", "Read ${characteristic.uuid} offset=$offset")
            respondRead(device, requestId, offset, values[characteristic.uuid])
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                            descriptor: BluetoothGattDescriptor) {
            val flags = cccds[device.address]?.get(descriptor.characteristic.uuid) ?: 0
            respondRead(device, requestId, offset, byteArrayOf(flags.toByte(), 0))
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                             preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val props = descriptor.characteristic.properties
            val flags = if (value.size == 2 && value[1].toInt() == 0) value[0].toInt() and 0xff else -1
            val valid = descriptor.uuid == uuid(0x2902) && !preparedWrite && offset == 0 &&
                (flags == 0 || flags == 1 && props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    flags == 2 && props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            if (valid) cccds.getOrPut(device.address) { ConcurrentHashMap() }[descriptor.characteristic.uuid] = flags
            val status = if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
            RowerLog.i("BLE", "CCCD ${descriptor.characteristic.uuid} flags=$flags status=$status")
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                 characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
                                                 responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId,
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
        }
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != 0) RowerLog.w("BLE", "Notification completion status=$status")
        }
    }

    suspend fun start() {
        check(adapter.isEnabled) { "Bluetooth is off" }
        try {
            check(adapter.setName(name)) { "Could not set BLE device name" }
            server = manager.openGattServer(appContext, callbacks) ?: error("Could not open GATT server")
            val service = BluetoothGattService(uuid(0x1826), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic(0x2acc, BluetoothGattCharacteristic.PROPERTY_READ))
            listOf(rowerData, training, machineStatus).forEach { service.addCharacteristic(it) }
            check(server!!.addService(service)) { "Could not register FTMS service" }
            withTimeout(10_000) { registered.await() }
            advertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setTimeout(0).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(uuid(0x1826)))
                    .addServiceData(ParcelUuid(uuid(0x1826)), byteArrayOf(1, 0x10, 0)).build(),
                AdvertiseData.Builder().setIncludeDeviceName(true).build(), advertisement)
            withTimeout(10_000) { advertised.await() }
        } catch (e: Exception) { close(); throw e }
    }
    fun publish(value: ByteArray) {
        for (device in devices.values) {
            if (cccds[device.address]?.get(rowerData.uuid) == 1) send(device, rowerData, value, false)
        }
    }
    private fun send(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray, confirm: Boolean) {
        if (closed) return
        @Suppress("DEPRECATION")
        synchronized(characteristic) {
            characteristic.value = value
            if (server?.notifyCharacteristicChanged(device, characteristic, confirm) == true) RowerLog.notifications++
            else RowerLog.w("BLE", "Notification queue rejected ${characteristic.uuid}")
        }
    }
    private fun respondRead(device: BluetoothDevice, id: Int, offset: Int, value: ByteArray?) {
        val status = when {
            value == null -> BluetoothGatt.GATT_READ_NOT_PERMITTED
            offset !in 0..value.size -> BluetoothGatt.GATT_INVALID_OFFSET
            else -> BluetoothGatt.GATT_SUCCESS
        }
        server?.sendResponse(device, id, status, offset,
            if (status == 0) value!!.copyOfRange(offset, value.size) else null)
    }
    fun close() {
        if (closed) return
        closed = true
        try {
            advertiser.stopAdvertising(advertisement)
        } finally {
            try { server?.close() } finally {
                server = null
                devices.clear(); cccds.clear(); RowerLog.clients = 0
                if (originalName != null && adapter.name == name) adapter.setName(originalName)
            }
        }
    }
    companion object {
        fun uuid(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
        private fun characteristic(short: Int, properties: Int): BluetoothGattCharacteristic {
            val permissions = (if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                (if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.PERMISSION_WRITE else 0)
            return BluetoothGattCharacteristic(uuid(short), properties, permissions).apply {
                if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                    addDescriptor(BluetoothGattDescriptor(uuid(0x2902), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            }
        }
    }
}
