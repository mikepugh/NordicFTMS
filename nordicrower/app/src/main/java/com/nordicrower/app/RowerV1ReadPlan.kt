package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField

/** Keep rower replies small and avoid unrelated/composite console fields. */
object RowerV1ReadPlan {
    private val required = setOf(V1DataField.WATTS, V1DataField.STROKES, V1DataField.STROKES_PER_MINUTE)
    private val optional = setOf(V1DataField.RESISTANCE, V1DataField.CURRENT_DISTANCE,
        V1DataField.WORKOUT_MODE, V1DataField.START_REQUESTED)

    fun periodicFields(declared: Set<Int>): Set<V1DataField> {
        check(required.all { it.fieldIndex in declared }) { "Required rower telemetry was not declared" }
        return required + optional.filter { it.fieldIndex in declared }
    }

    fun allowCoreReadback(deviceId: Int, software: Int, hardware: Int,
                          declared: Set<Int>, commands: Set<Int>?): Boolean {
        if (deviceId != 20 || commands == null) return false
        if (2 in commands) return true
        // The October 27 controller uses opcode 02 successfully but omits it from SupportedCommands.
        return software == 83 && hardware == 1 && required.all { it.fieldIndex in declared }
    }
}
