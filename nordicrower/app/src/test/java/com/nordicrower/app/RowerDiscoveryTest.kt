package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import com.nettarion.hyperborea.hardware.fitpro.v1.*
import com.nettarion.hyperborea.hardware.fitpro.v2.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class RowerDiscoveryTest {
    private class FakeTransport(val respond: (ByteArray) -> List<ByteArray>) : HidTransport {
        val writes = mutableListOf<ByteArray>()
        private val incoming = ArrayDeque<ByteArray>()
        override var isOpen = false
        override suspend fun open() { isOpen = true }
        override suspend fun close() { isOpen = false }
        override suspend fun clearBuffer() { error("Probe must not flush/write maintenance packets") }
        override suspend fun write(data: ByteArray) { writes += data; incoming.addAll(respond(data)) }
        override suspend fun readPacket(): ByteArray? {
            if (incoming.isEmpty()) delay(100)
            return incoming.pollFirst()
        }
        override fun incoming() = emptyFlow<ByteArray>()
    }
    private fun responseV1(command: Int, payload: ByteArray, status: Int = V1Message.STATUS_DONE): ByteArray {
        val data = byteArrayOf(20, (5 + payload.size).toByte(), command.toByte(), status.toByte()) + payload
        return data + V1Codec.checksum(data)
    }
    private fun infoV1(fields: Set<Int>, type: Int = 20): ByteArray {
        val count = (fields.maxOrNull() ?: 0) / 8 + 1
        val payload = ByteArray(9 + count)
        payload[0] = 90; payload[1] = 3; payload[8] = count.toByte()
        fields.forEach { payload[9 + it / 8] = (payload[9 + it / 8].toInt() or (1 shl (it % 8))).toByte() }
        val result = responseV1(0x81, payload)
        result[0] = type.toByte()
        result[result.lastIndex] = V1Codec.checksum(result.copyOf(result.size - 1))
        return result
    }
    private fun fieldV1(request: ByteArray): V1DataField {
        assertEquals("V1 query cannot write any field", 0, request[3].toInt())
        val sections = request[4].toInt() and 0xff
        val ids = (0 until sections * 8).filter { id ->
            request[5 + id / 8].toInt() and (1 shl (id % 8)) != 0
        }
        return V1DataField.fromFieldIndex(ids.single())!!
    }
    private fun eventV2(id: Int, value: Float) = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        .put(2).put(5).put(6).putShort(id.toShort()).putFloat(value).array()
    private fun featuresV2(ids: List<Int>) = byteArrayOf(2, 1, (ids.size * 2).toByte()) +
        ids.flatMap { listOf((it and 0xff).toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test fun v1ReadsCapabilitiesWithoutAnyTargetOrSecurityWrites() = runTest {
        val records = mutableListOf<String>()
        val fields = RowerDiscovery.v1ReadFields.map { it.fieldIndex }.toSet()
        val transport = FakeTransport { request ->
            listOf(when (request[2].toInt() and 0xff) {
                0x81 -> infoV1(fields)
                0x88 -> responseV1(0x88, byteArrayOf(2, 0x90.toByte()))
                2 -> responseV1(2, ByteArray(fieldV1(request).sizeBytes).apply { this[0] = 1 })
                else -> error("Unexpected non-query V1 command")
            })
        }
        RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover()
        assertEquals(10, transport.writes.size)
        assertTrue(records.any { it.contains("nativeErg=DECLARED_NOT_WRITE_TESTED") })
        assertTrue(records.any { it.contains("MAX_RESISTANCE_LEVEL") && it.contains("value=1.0") })
        assertFalse(transport.writes.any { it[2].toInt() and 0xff == 0x90 })
    }
    @Test fun v1SecurityBlockIsPreservedAndNoUnlockIsAttempted() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport {
            listOf(when (it[2].toInt() and 0xff) {
                0x81 -> infoV1(setOf(42, 2, 61, 119))
                0x88 -> responseV1(0x88, byteArrayOf(2))
                2 -> responseV1(2, byteArrayOf(), V1Message.STATUS_SECURITY_BLOCK)
                else -> error("Unexpected command")
            })
        }
        RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover()
        assertEquals(3, transport.writes.size)
        assertTrue(records.any { it.contains("SECURITY_BLOCK") })
    }
    @Test fun v1MissingCommandListDoesNotTriggerBlindReads() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport { if (it[2].toInt() and 0xff == 0x81) listOf(infoV1(setOf(2))) else emptyList() }
        RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover()
        assertEquals(2, transport.writes.size)
        assertTrue(records.any { it.contains("no blind reads") })
    }
    @Test fun v1MalformedMaskIsUnknownNotUnsupported() = runTest {
        val bad = infoV1(setOf(2)).apply { this[12] = 50; this[lastIndex] = V1Codec.checksum(copyOf(size - 1)) }
        val records = mutableListOf<String>()
        val transport = FakeTransport { listOf(bad) }
        try { RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("UNKNOWN")) }
        assertFalse(records.any { it.contains("NOT_DECLARED") })
    }
    @Test fun v1InvalidChecksumStopsDiscovery() = runTest {
        val transport = FakeTransport { listOf(infoV1(setOf(2)).apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }) }
        try { RowerDiscovery(transport, 2, {}) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("checksum")) }
        assertEquals(1, transport.writes.size)
    }
    @Test fun v1NonRowerStopsBeforeFurtherQueries() = runTest {
        val transport = FakeTransport { listOf(infoV1(setOf(2), type = 7)) }
        try { RowerDiscovery(transport, 2, {}) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not ROWER")) }
        assertEquals(1, transport.writes.size)
    }
    @Test fun v1FragmentedIdentityIsReassembledAndValidated() = runTest {
        val records = mutableListOf<String>()
        val info = infoV1(setOf(2, 61, 119))
        val chunks = info.toList().chunked(18)
        val transport = FakeTransport {
            if (it[2].toInt() and 0xff == 0x81) {
                listOf(byteArrayOf(0xfe.toByte(), 2, info.size.toByte(), chunks.size.toByte())) +
                    chunks.mapIndexed { index, chunk ->
                        byteArrayOf(if (index == chunks.lastIndex) 0xff.toByte() else index.toByte(), chunk.size.toByte()) + chunk.toByteArray()
                    }
            } else emptyList()
        }
        RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover()
        assertTrue(records.any { it.contains("nativeErg=DECLARED_NOT_WRITE_TESTED") })
    }
    @Test fun v1StaleUnexpectedResponseIsNotDecodedAsCurrentField() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport {
            when (it[2].toInt() and 0xff) {
                0x81 -> listOf(infoV1(setOf(42)))
                0x88 -> listOf(responseV1(0x88, byteArrayOf(2)))
                else -> listOf(responseV1(0x88, byteArrayOf(2)))
            }
        }
        RowerDiscovery(transport, 2, records::add) { _, _ -> }.discover()
        assertTrue(records.any { it.contains("Unexpected V1 response opcode") })
        assertTrue(records.any { it.contains("MAX_RESISTANCE_LEVEL") && it.contains("value=UNKNOWN") })
    }
    @Test fun v2FeatureListAndReadbacksUseOnlyQueriesAndSubscriptions() = runTest {
        val records = mutableListOf<String>()
        val ids = listOf(10, 503, 504, 522, 523, 528, 343, 344, 602, 777)
        val transport = FakeTransport { request ->
            when (request[1].toInt() and 15) {
                6 -> listOf(featuresV2(ids), featuresV2(emptyList()))
                14 -> listOf(byteArrayOf(2, 14, 2, 2, 0))
                1 -> (3 until request.size step 2).map { index ->
                    val id = (request[index].toInt() and 0xff) or ((request[index + 1].toInt() and 0xff) shl 8)
                    eventV2(id, if (id == 10) 20f else 24f)
                }
                7 -> emptyList()
                else -> error("V2 probe must never WriteFeature")
            }
        }
        RowerDiscovery(transport, 3, records::add) { _, _ -> }.discover()
        assertTrue(records.any { it.contains("777:UNKNOWN") })
        assertTrue(records.any { it.contains("nativeErg=DECLARED_NOT_WRITE_TESTED") })
        assertTrue(records.any { it.contains("504:MAX_RESISTANCE value=24.0") })
        assertEquals(listOf(6, 14, 1, 1, 7), transport.writes.map { it[1].toInt() and 15 })
    }
    @Test fun v2IncompleteListDoesNotClaimMissingFeaturesAreUnsupported() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport { listOf(featuresV2(listOf(503))) }
        try { RowerDiscovery(transport, 4, records::add) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Incomplete")) }
        assertTrue(records.any { it.contains("nativeErg=UNKNOWN_INCOMPLETE_LIST") })
        assertEquals(1, transport.writes.size)
    }
    @Test fun v2MissingRowerTypeDoesNotSubscribeBlindly() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport {
            if (it[1].toInt() and 15 == 6) listOf(featuresV2(listOf(503)), featuresV2(emptyList()))
            else listOf(byteArrayOf(2, 14, 2, 2, 0))
        }
        try { RowerDiscovery(transport, 3, records::add) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("DEVICE_TYPE")) }
        assertEquals(2, transport.writes.size)
    }
    @Test fun v2MissingReadbackRemainsUnknownAndUnsubscribes() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport {
            when (it[1].toInt() and 15) {
                6 -> listOf(featuresV2(listOf(10, 503, 504)), featuresV2(emptyList()))
                14 -> listOf(byteArrayOf(2, 14, 2, 2, 0))
                1 -> if (it.size == 5) listOf(eventV2(10, 20f)) else emptyList()
                7 -> emptyList()
                else -> error("Non-query command")
            }
        }
        RowerDiscovery(transport, 3, records::add) { _, _ -> }.discover()
        assertTrue(records.any { it.contains("504:MAX_RESISTANCE=UNKNOWN") })
        assertEquals(7, transport.writes.last()[1].toInt() and 15)
    }
    @Test fun v2CorruptFramePreventsFalseUnsupportedClaims() = runTest {
        val records = mutableListOf<String>()
        val transport = FakeTransport { listOf(featuresV2(listOf(503)), byteArrayOf(2, 1, 4, 10, 0), featuresV2(emptyList())) }
        try { RowerDiscovery(transport, 3, records::add) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Incomplete")) }
        assertTrue(records.any { it.contains("nativeErg=UNKNOWN_INCOMPLETE_LIST") })
        assertEquals(1, transport.writes.size)
    }
    @Test fun v2NonRowerTypeUnsubscribesWithoutWorkoutCommands() = runTest {
        val transport = FakeTransport {
            when (it[1].toInt() and 15) {
                6 -> listOf(featuresV2(listOf(10, 503)), featuresV2(emptyList()))
                14 -> listOf(byteArrayOf(2, 14, 2, 2, 0))
                1 -> listOf(eventV2(10, 7f))
                7 -> emptyList()
                else -> error("Non-query command")
            }
        }
        try { RowerDiscovery(transport, 3, {}) { _, _ -> }.discover(); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("ROWER")) }
        assertEquals(listOf(6, 14, 1, 7), transport.writes.map { it[1].toInt() and 15 })
    }
    @Test fun v1ErgRequiresBothGoalAndModeDeclarations() {
        assertTrue(RowerDiscovery.capabilitySummary("V1", setOf(61), true).contains("nativeErg=NOT_DECLARED"))
        assertTrue(RowerDiscovery.capabilitySummary("V1", setOf(61), false).contains("nativeErg=UNKNOWN"))
    }
}
