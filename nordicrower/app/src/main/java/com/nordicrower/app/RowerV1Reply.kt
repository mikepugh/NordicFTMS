package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.v1.V1Codec
import com.nettarion.hyperborea.hardware.fitpro.v1.V1DataField
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Message

/** Validate framing before the upstream positional decoder sees any controller data. */
object RowerV1Reply {
    fun decode(packets: List<ByteArray>, fields: Set<V1DataField>? = null): V1Message.Incoming? {
        check(packets.isNotEmpty()) { "Missing V1 reply" }
        val first = packets.first()
        val raw = if (V1Codec.isMultiPacketHeader(first)) {
            check(first.size >= 4) { "Incomplete V1 fragment header" }
            val count = V1Codec.expectedPacketCount(first)
            check(count in 1..16 && packets.size == count + 1) { "Incomplete V1 reply fragments" }
            val bytes = packets.drop(1).flatMapIndexed { index, packet ->
                val size = packet.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                check(size in 1..18 && size <= packet.size - 2) { "Invalid V1 fragment size" }
                val sequence = packet[0].toInt() and 0xff
                check(sequence == if (index == count - 1) 255 else index) { "Invalid V1 fragment sequence" }
                packet.copyOfRange(2, 2 + size).toList()
            }.toByteArray()
            check(bytes.size == first[2].toInt() and 0xff) { "V1 fragmented length mismatch" }
            bytes
        } else {
            check(packets.size == 1) { "Unexpected extra V1 packets" }
            val length = first.getOrNull(1)?.toInt()?.and(0xff) ?: 0
            check(length in 5..first.size) { "Invalid V1 reply length" }
            first.copyOf(length)
        }
        check(raw.size >= 5 && raw[1].toInt() and 0xff == raw.size) { "V1 declared length mismatch" }
        check(V1Codec.verifyChecksum(raw)) { "V1 checksum mismatch" }
        if (fields != null) check(raw[2].toInt() and 0xff == V1Message.CMD_READ_WRITE_DATA) {
            "Unexpected V1 response while reading fields"
        }
        return V1Codec.decodeSingle(raw, fields)
    }
}
