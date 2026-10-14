package com.nordicrower.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.UsbHidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportResult
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Session
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Session
import kotlinx.coroutines.CoroutineScope

object RowerHardware {
    const val VENDOR = 0x213c
    val PRODUCTS = setOf(2, 3, 4)

    fun readOwnership(context: Context): RowerOwnership {
        val pm = context.packageManager
        val glassConsoleInstalled = try {
            pm.getApplicationInfo("com.ifit.mithlond", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        val wolf = try { pm.getApplicationInfo("com.ifit.standalone", 0) }
            catch (_: PackageManager.NameNotFoundException) { null }
        return RowerOwnership(glassConsoleInstalled, wolf?.enabled)
    }

    fun checkExclusiveMode(context: Context) {
        val ownership = readOwnership(context)
        RowerLog.i("Ownership", "glassConsoleInstalled=${ownership.glassConsoleInstalled} " +
            "wolfInstalled=${ownership.wolfEnabled != null} wolfEnabled=${ownership.wolfEnabled} " +
            "block=${ownership.block?.name ?: "NONE"}; USB ownership has not been tested")
        val usb = context.getSystemService(UsbManager::class.java)
        logInventory(usb)
        // Retain inventory even when package state prevents opening the controller.
        ownership.block?.let { throw RowerOwnershipException(it) }
        val devices = usb.deviceList.values.filter { it.vendorId == VENDOR && it.productId in PRODUCTS }
        check(devices.size == 1) { "Expected one FitPro USB controller; found ${devices.size}" }
        check(usb.hasPermission(devices.single())) { "USB access was not granted" }
    }

    fun logInventory(usb: UsbManager) {
        usb.deviceList.values.forEach {
            RowerLog.i("Inventory", "USB VID=%04x PID=%04x interfaces=%d permission=%s".format(
                it.vendorId, it.productId, it.interfaceCount, usb.hasPermission(it)))
        }
        if (usb.deviceList.isEmpty()) RowerLog.i("Inventory", "No USB devices detected")
    }

    fun createTransport(context: Context): HidTransportResult {
        checkExclusiveMode(context)
        val result = UsbHidTransportFactory(context, RowerLog).create(VENDOR, 2)
        return HidTransportResult(RowerTransport(result.transport, result.productId), result.productId)
    }

    suspend fun connect(context: Context, scope: CoroutineScope): FitProSession {
        val result = createTransport(context)
        val info = DeviceInfo.DEFAULT_INDOOR_BIKE.copy(name = "NordicRower", type = DeviceType.ROWER)
        val session = when (result.productId) {
            2 -> V1Session(result.transport, RowerLog, scope, info)
            3, 4 -> V2Session(result.transport, RowerLog, scope, info)
            else -> error("Unsupported controller product ID ${result.productId}")
        }
        try {
            session.start()
            val status = session.sessionState.value
            if (status is SessionState.Error) throw IllegalStateException(status.message, status.cause)
            check(status is SessionState.Streaming) {
                "Controller handshake did not complete"
            }
            return session
        } catch (e: Exception) {
            // Failed rower validation must release USB without invoking workout teardown writes.
            try { result.transport.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
            throw e
        }
    }
}
