package com.nordicrower.app

import com.nettarion.hyperborea.core.model.ExerciseData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RowerFrames {
    fun rejectionReason(data: ExerciseData): String? = when {
        data.power == null -> "Missing watts"
        data.strokeCount == null -> "Missing real cumulative strokes"
        data.strokeRate == null -> "Missing real stroke rate"
        data.power !in 0..32767 -> "Watts out of FTMS range"
        data.strokeCount < 0 -> "Negative cumulative strokes"
        data.strokeRate !in 0..127 -> "Stroke rate out of FTMS range"
        else -> null
    }
    // A complete record always contains genuine stroke rate and cumulative stroke count.
    fun encode(data: ExerciseData): ByteArray? {
        if (rejectionReason(data) != null) return null
        val watts = data.power ?: return null
        val strokes = data.strokeCount ?: return null
        val rate = data.strokeRate ?: return null
        if (watts !in 0..32767 || strokes < 0 || rate !in 0..127) return null
        return ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x20).put((rate * 2).toByte())
            .putShort((strokes and 0xffff).toShort()).putShort(watts.toShort()).array()
    }
}
