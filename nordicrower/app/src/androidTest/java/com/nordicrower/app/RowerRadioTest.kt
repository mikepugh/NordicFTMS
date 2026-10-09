package com.nordicrower.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nettarion.hyperborea.core.model.ExerciseData
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in two-device radio tests. The peripheral never opens a USB controller. */
@SuppressLint("MissingPermission")
class RowerRadioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test fun glassConsoleRejectionPreservesError() = runBlocking<Unit> {
        assumeTrue(arguments.getString("role") == "safety")
        val context = instrumentation.targetContext
        context.packageManager.getApplicationInfo("com.ifit.mithlond", 0)
        try {
            context.startForegroundService(Intent(context, RowerService::class.java))
            withTimeout(10_000) {
                while (!RowerLog.state.startsWith("Erreur :")) delay(100)
            }
            delay(1000)
            assertTrue(RowerLog.state.contains("GlassOS console detected"))
            Log.i("NordicRowerRadio", "PASS: GlassOS rejected before USB; error remains visible")
        } finally {
            context.stopService(Intent(context, RowerService::class.java))
        }
    }

    @Test fun syntheticPeripheral() = runBlocking<Unit> {
        assumeTrue(arguments.getString("role") == "peripheral")
        val seconds = arguments.getString("seconds")?.toLong()?.coerceIn(10, 600) ?: 180
        val ble = RowerBle(instrumentation.targetContext, "NordicRower Test")
        try {
            ble.start()
            Log.i("NordicRowerRadio", "FIXTURE_READY: 123 W, 24 strokes/min, real USB untouched")
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < seconds * 1000) {
                val count = ((SystemClock.elapsedRealtime() - start) / 2500).toInt()
                val data = ExerciseData(123, null, null, null, null, null, null, null, 0,
                    strokeCount = count, strokeRate = 24)
                ble.publish(RowerFrames.encode(data)!!)
                delay(500)
            }
        } finally {
            ble.close()
            Log.i("NordicRowerRadio", "FIXTURE_CLOSED")
        }
    }

    @Test fun centralReceivesRowerPowerAndStrokes() = runBlocking<Unit> {
        assumeTrue(arguments.getString("role") == "central")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION)
        val adapter = instrumentation.targetContext.getSystemService(BluetoothManager::class.java).adapter
        val targetName = arguments.getString("name") ?: "NordicRower Test"
        val found = CompletableDeferred<BluetoothDevice>()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName == targetName) {
                    Log.i("NordicRowerRadio", "Discovered fixture RSSI=${result.rssi}, connectable=${result.isConnectable}")
                    found.complete(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("Scan error=$errorCode"))
            }
        }
        var gatt: BluetoothGatt? = null
        try {
            adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(RowerBle.uuid(0x1826))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            val device = withTimeout(30_000) { found.await() }
            adapter.bluetoothLeScanner.stopScan(scan)
            val connected = CompletableDeferred<Unit>()
            val discovered = CompletableDeferred<Unit>()
            val feature = CompletableDeferred<ByteArray>()
            val measurement = CompletableDeferred<ByteArray>()
            var descriptorWrite = CompletableDeferred<Unit>()
            val callbacks = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(client: BluetoothGatt, status: Int, state: Int) {
                    Log.i("NordicRowerRadio", "Connect status=$status state=$state")
                    if (status != 0) connected.completeExceptionally(IllegalStateException("Connect=$status"))
                    else if (state == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
                }
                override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
                    if (status == 0) discovered.complete(Unit)
                    else discovered.completeExceptionally(IllegalStateException("Discovery=$status"))
                }
                @Deprecated("Android callback compatibility")
                override fun onCharacteristicRead(client: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    if (status == 0) feature.complete(c.value.copyOf())
                    else feature.completeExceptionally(IllegalStateException("Read=$status"))
                }
                override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (status == 0) descriptorWrite.complete(Unit)
                    else descriptorWrite.completeExceptionally(IllegalStateException("CCCD=$status"))
                }
                @Deprecated("Android callback compatibility")
                override fun onCharacteristicChanged(client: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    when (c.uuid) {
                        RowerBle.uuid(0x2ad1) -> measurement.complete(c.value.copyOf())
                    }
                }
            }
            gatt = device.connectGatt(instrumentation.targetContext, false, callbacks, BluetoothDevice.TRANSPORT_LE)
            withTimeout(30_000) { connected.await() }
            check(gatt.discoverServices())
            withTimeout(10_000) { discovered.await() }
            val service = requireNotNull(gatt.getService(RowerBle.uuid(0x1826)))
            if (arguments.getString("transport_only") == "true") {
                Log.i("NordicRowerRadio", "PASS: transport/discovery $targetName, UUIDs=${service.characteristics.map { it.uuid }}")
                return@runBlocking
            }
            check(gatt.readCharacteristic(service.getCharacteristic(RowerBle.uuid(0x2acc))))
            assertArrayEquals(byteArrayOf(0, 0x40, 0, 0, 0, 0, 0, 0), withTimeout(10_000) { feature.await() })
            suspend fun subscribe(short: Int, indicate: Boolean) {
                val c = requireNotNull(service.getCharacteristic(RowerBle.uuid(short)))
                check(gatt.setCharacteristicNotification(c, true))
                val descriptor = requireNotNull(c.getDescriptor(RowerBle.uuid(0x2902)))
                descriptor.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                descriptorWrite = CompletableDeferred()
                check(gatt.writeDescriptor(descriptor))
                withTimeout(10_000) { descriptorWrite.await() }
            }
            assertNull("Sensor-only profile must not expose an incomplete control point",
                service.getCharacteristic(RowerBle.uuid(0x2ad9)))
            subscribe(0x2ad1, false)
            val bytes = withTimeout(10_000) { measurement.await() }
            assertEquals(7, bytes.size)
            val frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x20, frame.short.toInt())
            assertEquals(48, frame.get().toInt() and 0xff)
            val strokes = frame.short.toInt() and 0xffff
            assertEquals(123, frame.short.toInt())
            Log.i("NordicRowerRadio", "PASS: read-only FTMS power=123 W, strokeRate=24/min, strokes=$strokes")
        } finally {
            adapter.bluetoothLeScanner.stopScan(scan)
            gatt?.disconnect()
            gatt?.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
