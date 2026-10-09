package com.nordicrower.app

import com.nettarion.hyperborea.core.model.ExerciseData
import org.junit.Assert.*
import org.junit.Test

class RowerFramesTest {
    private fun sample(power: Int? = 123, count: Int? = 42, rate: Int? = 24) =
        ExerciseData(power, 90, null, null, null, null, null, null, 0, strokeCount = count, strokeRate = rate)

    @Test fun completeRecordUsesPowerAndRealStrokeFields() {
        assertArrayEquals(byteArrayOf(0x20, 0, 48, 42, 0, 123, 0), RowerFrames.encode(sample()))
    }
    @Test fun missingStrokeRateDoesNotFallBackToRpm() { assertNull(RowerFrames.encode(sample(rate = null))) }
    @Test fun missingCountIsNotFabricated() { assertNull(RowerFrames.encode(sample(count = null))) }
    @Test fun missingPowerIsNotZero() { assertNull(RowerFrames.encode(sample(power = null))) }
    @Test fun genuineZeroIsValid() { assertNotNull(RowerFrames.encode(sample(0, 0, 0))) }
    @Test fun cumulativeCountWrapsUint16() {
        assertArrayEquals(byteArrayOf(0x20, 0, 48, 1, 0, 123, 0), RowerFrames.encode(sample(count = 65537)))
    }
    @Test fun impossibleReadingsAreRejected() {
        assertNull(RowerFrames.encode(sample(power = -1)))
        assertNull(RowerFrames.encode(sample(rate = 128)))
    }
}
