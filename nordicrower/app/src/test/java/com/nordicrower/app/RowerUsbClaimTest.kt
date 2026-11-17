package com.nordicrower.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RowerUsbClaimTest {
    @Test fun normalClaimDoesNotInspectOrDetachDrivers() {
        assertEquals(RowerUsbClaim.Mode.NORMAL, RowerUsbClaim.tryClaim({ error("No driver probe expected") }) { force ->
            assertFalse(force); true
        })
    }

    @Test fun identifiedKernelHidCanBeDetachedAfterNormalFailure() {
        val attempts = mutableListOf<Boolean>()
        val result = RowerUsbClaim.tryClaim({ "usbhid" }) { force -> attempts += force; force }
        assertEquals(RowerUsbClaim.Mode.KERNEL_HID_DETACHED, result)
        assertEquals(listOf(false, true), attempts)
    }

    @Test fun userspaceUnknownAndOtherDriversNeverAuthorizeDetachment() {
        listOf("usbfs", null, "ftdi_sio").forEach { driver ->
            val attempts = mutableListOf<Boolean>()
            assertNull(RowerUsbClaim.tryClaim({ driver }) { force -> attempts += force; false })
            assertEquals(listOf(false), attempts)
        }
    }

    @Test fun failedKernelDetachRemainsAClaimFailure() {
        assertNull(RowerUsbClaim.tryClaim({ "usbhid" }) { false })
    }

    @Test fun sysfsProbeMatchesBusDeviceAndInterfaceInsteadOfUnrelatedHid() {
        val root = Files.createTempDirectory("rower-sysfs").toFile()
        try {
            fun controller(name: String, bus: Int, device: Int, iface: Int, driver: String) {
                val controller = root.resolve(name).apply { mkdirs() }
                controller.resolve("busnum").writeText("$bus\n")
                controller.resolve("devnum").writeText("$device\n")
                val usbInterface = root.resolve("$name:1.$iface").apply { mkdirs() }
                usbInterface.resolve("bInterfaceNumber").writeText("%02x\n".format(iface))
                val target = root.resolve("drivers/$driver").apply { mkdirs() }
                Files.createSymbolicLink(usbInterface.resolve("driver").toPath(), target.toPath())
            }
            controller("1-1", 1, 4, 0, "usbhid")
            controller("2-1", 2, 4, 0, "usbfs")
            assertEquals("usbfs", RowerUsbClaim.driverName("/dev/bus/usb/002/004", 0, root))
            assertNull(RowerUsbClaim.driverName("/dev/bus/usb/002/004", 1, root))
            assertNull(RowerUsbClaim.driverName("/dev/bus/usb/002/005", 0, root))
            assertNull(RowerUsbClaim.driverName("invalid", 0, root))
        } finally { root.deleteRecursively() }
    }
}
