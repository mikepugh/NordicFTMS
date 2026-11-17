package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField as F
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Message
import org.junit.Assert.*
import org.junit.Test

class RowerV1ReadPlanTest {
    companion object {
        // Controller declarations from the October 27 QZ trace; no identifiers or private data.
        val customerFields = setOf(2, 3, 4, 7, 9, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20, 21,
            22, 23, 24, 25, 30, 31, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
            49, 51, 54, 55, 58, 60, 64, 65, 66, 69, 70, 71, 72, 73, 74, 87, 94, 95, 96,
            100, 102, 103, 108, 109, 110, 111, 112, 115, 116)
        val customerCommands = setOf(0x09, 0x71, 0x82, 0x83, 0x84, 0x89, 0x90, 0x95)
    }

    @Test fun observedControllerUsesSmallCompleteRowingReply() {
        val old = F.periodicReadFields.filter { it.fieldIndex in customerFields }
        assertEquals(64, old.sumOf { it.sizeBytes })
        val fields = RowerV1ReadPlan.periodicFields(customerFields)
        assertEquals(13, fields.sumOf { it.sizeBytes })
        assertTrue(fields.containsAll(setOf(F.WATTS, F.STROKES, F.STROKES_PER_MINUTE, F.START_REQUESTED, F.WORKOUT_MODE)))
        assertFalse(F.KEY_OBJECT in fields)
        assertFalse(F.RPM in fields)
        assertFalse(F.FIVE_HUNDRED_SPLIT in fields)
    }

    @Test fun optionalFieldsAreNeverReadUnlessDeclared() {
        assertEquals(setOf(F.WATTS, F.STROKES, F.STROKES_PER_MINUTE),
            RowerV1ReadPlan.periodicFields(setOf(3, 109, 110)))
    }

    @Test(expected = IllegalStateException::class) fun missingRealRowingFieldsCannotUseRpm() {
        RowerV1ReadPlan.periodicFields(setOf(3, 5))
    }

    @Test fun observedFirmwareAllowsCoreReadDespiteOptionalCommandListOmission() {
        assertFalse(V1Message.CMD_READ_WRITE_DATA in customerCommands)
        assertTrue(RowerV1ReadPlan.allowCoreReadback(20, 83, 1, customerFields, customerCommands))
    }

    @Test fun firmwareExceptionDoesNotPermitUnknownOrNonRowerReads() {
        assertFalse(RowerV1ReadPlan.allowCoreReadback(4, 83, 1, customerFields, customerCommands))
        assertFalse(RowerV1ReadPlan.allowCoreReadback(20, 84, 1, customerFields, customerCommands))
        assertFalse(RowerV1ReadPlan.allowCoreReadback(20, 83, 2, customerFields, customerCommands))
        assertFalse(RowerV1ReadPlan.allowCoreReadback(20, 83, 1, customerFields, null))
        assertFalse(RowerV1ReadPlan.allowCoreReadback(20, 83, 1, setOf(3), customerCommands))
    }
}
