package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId as F
import org.junit.Assert.*
import org.junit.Test

class RowerProtocolGuardTest {
    private val rower = setOf(F.WATTS, F.ROWER_TOTAL_STROKES, F.ROWER_STROKES_PER_MINUTE)
    @Test fun declaredV1RowerAccepted() { RowerProtocolGuard.requireV1(20, setOf(3, 109, 110)) }
    @Test(expected = IllegalStateException::class) fun treadmillRejected() {
        RowerProtocolGuard.requireV1(4, setOf(3, 109, 110))
    }
    @Test(expected = IllegalStateException::class) fun emptyV1CapabilitiesRejected() {
        RowerProtocolGuard.requireV1(20, emptySet())
    }
    @Test fun declaredV2RowerAccepted() { RowerProtocolGuard.requireV2(rower + F.DEVICE_TYPE, 20f) }
    @Test(expected = IllegalStateException::class) fun conflictingV2TypeRejected() {
        RowerProtocolGuard.requireV2(rower + F.DEVICE_TYPE, 4f)
    }
    @Test(expected = IllegalStateException::class) fun missingV2TypeEventRejected() {
        RowerProtocolGuard.requireV2(rower + F.DEVICE_TYPE, null)
    }
    @Test(expected = IllegalStateException::class) fun bikeRpmIsNotRowingData() {
        RowerProtocolGuard.requireV2(setOf(F.WATTS, F.RPM), 20f)
    }
    @Test fun rowerOnlyFeatureSetCanIdentifyOlderController() {
        RowerProtocolGuard.requireV2(rower, null)
    }
}
