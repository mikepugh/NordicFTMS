package com.nordicrower.app

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import com.nettarion.hyperborea.hardware.fitpro.v1.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class RowerV1SessionTest {
    private class Controller : HidTransport {
        override var isOpen = false
        private val replies = ArrayDeque<ByteArray>()
        private val values = mutableMapOf(3 to 155, 4 to 270, 12 to WorkoutMode.IDLE.raw.toInt(),
            42 to 26, 69 to 1000, 70 to 100, 109 to 35, 110 to 33, 116 to 1)
        val polls = mutableListOf<Set<V1DataField>>()
        override suspend fun open() { isOpen = true }
        override suspend fun close() { isOpen = false }
        override suspend fun clearBuffer() { replies.clear() }
        override fun incoming() = emptyFlow<ByteArray>()
        override suspend fun readPacket(): ByteArray? {
            if (replies.isEmpty()) delay(100)
            return replies.pollFirst()
        }
        private fun response(command: Int, payload: ByteArray, device: Int = 2): ByteArray {
            val raw = byteArrayOf(device.toByte(), (payload.size + 5).toByte(), command.toByte(), V1Message.STATUS_DONE.toByte()) + payload
            return raw + V1Codec.checksum(raw)
        }
        private fun mask(data: ByteArray, start: Int): List<V1DataField> {
            val count = data[start].toInt() and 0xff
            return (0 until count * 8).filter { bit ->
                data[start + 1 + bit / 8].toInt() and (1 shl (bit % 8)) != 0
            }.map { V1DataField.fromFieldIndex(it) ?: error("Unknown requested field $it") }
        }
        override suspend fun write(data: ByteArray) {
            check(V1Codec.verifyChecksum(data))
            val command = data[2].toInt() and 0xff
            val reply = when (command) {
                0x81 -> {
                    val declared = RowerV1ReadPlanTest.customerFields
                    val payload = ByteArray(24).apply { this[0] = 83; this[1] = 1; this[8] = 15 }
                    declared.forEach { id -> payload[9 + id / 8] = (payload[9 + id / 8].toInt() or (1 shl (id % 8))).toByte() }
                    response(command, payload, 20)
                }
                0x88 -> response(command, RowerV1ReadPlanTest.customerCommands.sorted().map { it.toByte() }.toByteArray())
                0x82 -> response(command, ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(3, 15121).putInt(7, 427244).array())
                0x84 -> response(command, byteArrayOf(83, 0xd6.toByte(), 1))
                0x90 -> response(command, byteArrayOf(8))
                V1Message.CMD_READ_WRITE_DATA -> {
                    val written = mask(data, 3)
                    var offset = 4 + (data[3].toInt() and 0xff)
                    written.forEach { field ->
                        values[field.fieldIndex] = (0 until field.sizeBytes).fold(0) { value, index ->
                            value or ((data[offset + index].toInt() and 0xff) shl (8 * index))
                        }
                        offset += field.sizeBytes
                    }
                    val read = mask(data, offset)
                    if (setOf(3, 109, 110).all { id -> read.any { it.fieldIndex == id } }) polls += read.toSet()
                    val payload = read.flatMap { field -> (0 until field.sizeBytes).map { index ->
                        ((values[field.fieldIndex] ?: 0) ushr (8 * index)).toByte()
                    } }.toByteArray()
                    // Model a controller limited to one 64-byte USB reply: the old 69-byte shape fails.
                    response(command, payload).take(64).toByteArray()
                }
                V1Message.CMD_DISCONNECT -> response(command, byteArrayOf())
                else -> error("Unexpected command $command")
            }
            replies += reply.copyOf(64)
        }
    }

    @Test fun observedFirmwareCompletesHandshakeAndPublishesCompleteRowerFrame() = runTest {
        val controller = Controller()
        val logger = object : AppLogger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) {}
            override fun w(tag: String, message: String) {}
            override fun e(tag: String, message: String, throwable: Throwable?) {}
        }
        val session = V1Session(controller, logger, backgroundScope,
            DeviceInfo.DEFAULT_INDOOR_BIKE.copy(type = DeviceType.ROWER))
        try {
            session.start()
            assertEquals(SessionState.Streaming, session.sessionState.value)
            val data = withTimeout(5000) { session.exerciseData.filterNotNull().first() }
            assertEquals(155, data.power)
            assertEquals(35, data.strokeCount)
            assertEquals(33, data.strokeRate)
            assertEquals(0.270f, data.distance)
            assertArrayEquals(byteArrayOf(0x20, 0, 66, 35, 0, 0x9b.toByte(), 0), RowerFrames.encode(data))
            assertTrue(controller.polls.isNotEmpty())
            assertTrue(controller.polls.all { fields -> fields.sumOf { it.sizeBytes } <= 13 })
        } finally { session.stop() }
        assertFalse(controller.isOpen)
    }
}
