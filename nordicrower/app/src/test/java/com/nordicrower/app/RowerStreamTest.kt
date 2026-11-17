package com.nordicrower.app

import org.junit.Assert.*
import org.junit.Test

class RowerStreamTest {
    private val reading = RowerReading(188, 27, 9, 10_000)

    @Test fun realMeasurementsDoNotImplyBluetoothReception() {
        assertTrue(RowerStream.status(reading, 10_100, 0, 0).contains("en attente d'une application"))
        assertTrue(RowerStream.status(reading, 10_100, 1, 0).contains("en attente de sa demande"))
        assertTrue(RowerStream.status(reading, 10_100, 1, 1).contains("partage Bluetooth actif"))
        // Reconnecting clients must not inherit the old client's subscription.
        assertTrue(RowerStream.status(reading, 10_200, 1, 0).contains("en attente de sa demande"))
    }

    @Test fun stoppedStreamHidesValuesEvenWithSubscribers() {
        assertTrue(RowerStream.metrics(reading, 15_000).contains("188 W"))
        assertFalse(RowerStream.metrics(reading, 15_001).contains("188"))
        assertTrue(RowerStream.status(reading, 15_001, 1, 1).contains("interrompues"))
        assertFalse(RowerStream.fresh(reading, 9_999))
    }

    @Test fun missingDataIsNotReportedAsZero() {
        assertFalse(RowerStream.fresh(null, 100))
        assertTrue(RowerStream.metrics(null, 100).contains("— W"))
        assertTrue(RowerStream.metrics(RowerReading(0, 0, 0, 100), 100).contains("0 W"))
    }
}
