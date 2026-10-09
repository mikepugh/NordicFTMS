package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Message
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId

object RowerProtocolGuard {
    fun requireV1(deviceId: Int, fields: Set<Int>) {
        check(deviceId == V1Message.DEVICE_ROWER) {
            "Controller identifies as equipment $deviceId, not a rower; no workout writes sent"
        }
        val required = setOf(V1DataField.WATTS, V1DataField.STROKES, V1DataField.STROKES_PER_MINUTE)
        check(required.all { it.fieldIndex in fields }) {
            "Rower telemetry missing=${required.filter { it.fieldIndex !in fields }.joinToString { "${it.fieldIndex}:${it.name}" }}; " +
                "declaredFields=${fields.sorted().joinToString()}; no workout writes sent"
        }
    }

    fun requireV2(features: Set<V2FeatureId>, reportedType: Float?) {
        check(setOf(V2FeatureId.WATTS, V2FeatureId.ROWER_TOTAL_STROKES,
            V2FeatureId.ROWER_STROKES_PER_MINUTE).all { it in features }) {
            "Rower telemetry missing=${setOf(V2FeatureId.WATTS, V2FeatureId.ROWER_TOTAL_STROKES,
                V2FeatureId.ROWER_STROKES_PER_MINUTE).filter { it !in features }.joinToString { "${it.code}:${it.name}" }}; " +
                "declaredFeatures=${features.sortedBy { it.code }.joinToString { "${it.code}:${it.name}" }}; no workout writes sent"
        }
        if (V2FeatureId.DEVICE_TYPE in features) {
            check(reportedType == 20f) {
                "Controller did not confirm rower equipment type 20 (reported $reportedType); no workout writes sent"
            }
        }
    }
}
