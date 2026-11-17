package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v1.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RowerV1ReplyTest {
    private val fields = setOf(V1DataField.WATTS, V1DataField.STROKES, V1DataField.STROKES_PER_MINUTE)
    private fun reply(payload: ByteArray): ByteArray {
        val raw = byteArrayOf(2, (payload.size + 5).toByte(), 2, V1Message.STATUS_DONE.toByte()) + payload
        return raw + V1Codec.checksum(raw)
    }
    private val payload get() = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(155).putShort(35).put(33).array()

    @Test fun compactRowingResponseProducesGenuineMetrics() {
        val decoded = RowerV1Reply.decode(listOf(reply(payload).copyOf(64)), fields) as V1Message.Incoming.DataResponse
        assertFalse(decoded.isTruncated)
        assertEquals(155f, decoded.fields[V1DataField.WATTS])
        assertEquals(35f, decoded.fields[V1DataField.STROKES])
        assertEquals(33f, decoded.fields[V1DataField.STROKES_PER_MINUTE])
    }

    @Test fun shortPayloadStaysTruncatedInsteadOfInventingStrokeRate() {
        val decoded = RowerV1Reply.decode(listOf(reply(payload.copyOf(4))), fields) as V1Message.Incoming.DataResponse
        assertTrue(decoded.isTruncated)
        assertFalse(decoded.fields.containsKey(V1DataField.STROKES_PER_MINUTE))
    }

    @Test(expected = IllegalStateException::class) fun corruptChecksumIsRejected() {
        val packet = reply(payload).apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
        RowerV1Reply.decode(listOf(packet), fields)
    }

    @Test(expected = IllegalStateException::class) fun truncatedWirePacketIsRejected() {
        RowerV1Reply.decode(listOf(reply(payload).dropLast(1).toByteArray()), fields)
    }

    @Test(expected = IllegalStateException::class) fun unexpectedOpcodeIsNotTelemetry() {
        val packet = reply(payload).apply { this[2] = 0x81.toByte(); this[lastIndex] = V1Codec.checksum(copyOf(size - 1)) }
        RowerV1Reply.decode(listOf(packet), fields)
    }

    @Test fun validFragmentedReplyIsReassembled() {
        val raw = reply(payload)
        val packets = listOf(byteArrayOf(0xfe.toByte(), 2, raw.size.toByte(), 2),
            byteArrayOf(0, 5) + raw.copyOfRange(0, 5), byteArrayOf(0xff.toByte(), 5) + raw.copyOfRange(5, 10))
        assertFalse((RowerV1Reply.decode(packets, fields) as V1Message.Incoming.DataResponse).isTruncated)
    }

    @Test(expected = IllegalStateException::class) fun wrongFragmentSequenceIsRejected() {
        val raw = reply(payload)
        RowerV1Reply.decode(listOf(byteArrayOf(0xfe.toByte(), 2, raw.size.toByte(), 1), byteArrayOf(0, raw.size.toByte()) + raw), fields)
    }
}
