package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UsbClaimRecoveryTest {
    private class OpenTransport(private val failure: Exception? = null) : HidTransport {
        override var isOpen = false
        override suspend fun open() { failure?.let { throw it }; isOpen = true }
        override suspend fun close() { isOpen = false }
        override suspend fun write(data: ByteArray) { error("No writes expected") }
        override suspend fun readPacket(): ByteArray? = null
        override suspend fun clearBuffer() { error("No buffer clear expected") }
        override fun incoming() = emptyFlow<ByteArray>()
    }

    @Test fun exhaustedUsbClaimIsTypedAndKeepsOriginalEvidence() = runTest {
        val original = IllegalStateException("Failed to claim USB interface after 20 attempts")
        try { RowerTransport(OpenTransport(original), 2).open(); fail("Expected claim failure") }
        catch (e: UsbClaimException) {
            assertSame(original, e.cause)
            assertEquals("connect", UsbClaimRecovery.fromFailure(e, "connect")?.action)
        }
    }

    @Test fun unrelatedOpenFailureDoesNotOfferWolfRecovery() = runTest {
        val original = IllegalStateException("Transport already closed")
        try { RowerTransport(OpenTransport(original), 2).open(); fail("Expected open failure") }
        catch (e: IllegalStateException) {
            assertSame(original, e)
            assertNull(UsbClaimRecovery.fromFailure(e, "connect"))
        }
    }

    @Test fun permissionFailureDoesNotOfferWolfRecovery() = runTest {
        val original = SecurityException("USB access was not granted")
        try { RowerTransport(OpenTransport(original), 3).open(); fail("Expected permission failure") }
        catch (e: SecurityException) {
            assertSame(original, e)
            assertNull(UsbClaimRecovery.fromFailure(e, "discover"))
        }
    }

    @Test fun cancellationIsPreservedWithoutRecovery() = runTest {
        val original = CancellationException("Cancelled")
        try { RowerTransport(OpenTransport(original), 4).open(); fail("Expected cancellation") }
        catch (e: CancellationException) { assertSame(original, e) }
    }

    @Test fun successfulOpenDoesNotBlockAccess() = runTest {
        val transport = RowerTransport(OpenTransport(), 2)
        transport.open()
        assertTrue(transport.isOpen)
    }

    @Test fun sessionWrappedClaimFailurePreservesDiscoveryRetry() {
        val failure = IllegalStateException("Controller handshake failed", UsbClaimException(IllegalStateException("Claim failed")))
        val recovery = UsbClaimRecovery.fromFailure(failure, "discover")!!
        assertEquals("discover", recovery.action)
        assertTrue(recovery.instructions.contains("la recherche des capacités"))
    }

    @Test fun handshakeAndBleErrorsDoNotOfferWolfRecovery() {
        assertNull(UsbClaimRecovery.fromFailure(IllegalStateException("No valid V1 DeviceInfo response"), "connect"))
        assertNull(UsbClaimRecovery.fromFailure(IllegalStateException("Bluetooth advertising failed"), "connect"))
    }

    @Test fun savedRecoveryAllowsOnlyKnownLocalActions() {
        assertEquals("connect", UsbClaimRecovery.fromAction("connect")?.action)
        assertEquals("discover", UsbClaimRecovery.fromAction("discover")?.action)
        listOf(null, "NONE", "stop", "other").forEach { assertNull(UsbClaimRecovery.fromAction(it)) }
    }
}
