package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v2.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FitProRowerCodecTest {
    private fun event(id: Int, value: Float): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).put(5).put(6).putShort(id.toShort()).putFloat(value).array()
    @Test fun strokeCount343IsDecodedAsFloat() {
        assertEquals(V2Message.Incoming.Event(V2FeatureId.ROWER_TOTAL_STROKES, 42f), V2Codec.decode(event(343, 42f)))
    }
    @Test fun strokeRate344IsDecodedAsFloat() {
        assertEquals(V2Message.Incoming.Event(V2FeatureId.ROWER_STROKES_PER_MINUTE, 24f), V2Codec.decode(event(344, 24f)))
    }
    @Test fun truncatedPacketsAreRejected() { assertNull(V2Codec.decode(event(343, 42f).copyOf(8))) }
}
