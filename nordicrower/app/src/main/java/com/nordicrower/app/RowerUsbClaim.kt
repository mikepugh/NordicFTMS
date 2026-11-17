package com.nordicrower.app

import java.io.File

/** A failed claim alone does not distinguish a kernel HID binding from another client. */
object RowerUsbClaim {
    enum class Mode { NORMAL, KERNEL_HID_DETACHED }

    fun tryClaim(kernelDriver: () -> String?, claim: (force: Boolean) -> Boolean): Mode? {
        if (claim(false)) return Mode.NORMAL
        // Unknown ownership and usbfs (a userspace owner) must use the manual recovery flow.
        if (kernelDriver() != "usbhid") return null
        return if (claim(true)) Mode.KERNEL_HID_DETACHED else null
    }

    /** Best effort: Android may deny sysfs access. Missing evidence never authorizes detachment. */
    fun driverName(deviceName: String, interfaceId: Int, sysfs: File = File("/sys/bus/usb/devices")): String? = try {
        val address = Regex("/dev/bus/usb/([0-9]+)/([0-9]+)").matchEntire(deviceName)
        if (address == null) null else {
            val bus = address.groupValues[1].toInt()
            val device = address.groupValues[2].toInt()
            val entries = sysfs.listFiles().orEmpty()
            val controller = entries.firstOrNull {
                readNumber(File(it, "busnum"), 10) == bus && readNumber(File(it, "devnum"), 10) == device
            }
            val usbInterface = controller?.let { parent -> entries.firstOrNull {
                it.name.startsWith("${parent.name}:") && readNumber(File(it, "bInterfaceNumber"), 16) == interfaceId
            } }
            usbInterface?.let { File(it, "driver").canonicalFile.name.takeUnless { name -> name == "driver" } }
        }
    } catch (_: Exception) { null }

    private fun readNumber(file: File, radix: Int): Int? = try {
        file.readText().trim().toIntOrNull(radix)
    } catch (_: Exception) { null }
}
