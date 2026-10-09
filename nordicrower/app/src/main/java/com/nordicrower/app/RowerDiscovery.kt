package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import com.nettarion.hyperborea.hardware.fitpro.v1.*
import com.nettarion.hyperborea.hardware.fitpro.v2.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Query-only protocol path, deliberately independent of workout start/stop and command dispatch. */
class RowerDiscovery(
    private val transport: HidTransport,
    private val productId: Int,
    private val record: (String) -> Unit,
    private val step: (Int, String) -> Unit,
) {
    private var invalidV2Frames = 0
    suspend fun discover() {
        step(2, "Ouverture du contrôleur USB")
        transport.open()
        when (productId) {
            2 -> discoverV1()
            3, 4 -> discoverV2()
            else -> error("Unsupported USB product ID: $productId")
        }
    }

    private suspend fun queryV1(message: V1Message.Outgoing, fields: Set<V1DataField> = emptySet()): V1Message.Incoming? {
        // No workout, security-unlock, calibration, connect/disconnect or target writes are permitted.
        require(message is V1Message.Outgoing.DeviceInfo || message is V1Message.Outgoing.SupportedCommands ||
            message is V1Message.Outgoing.SystemInfo || message is V1Message.Outgoing.VersionInfo ||
            message is V1Message.Outgoing.ReadWriteData && message.writeFields.isEmpty())
        val description = if (message is V1Message.Outgoing.ReadWriteData) "Read ${fields.joinToString { it.name }}"
            else message.javaClass.simpleName
        val started = System.nanoTime()
        record("TX V1 $description")
        val outgoing = V1Codec.encode(message)
        val expectedCommand = outgoing.first()[2].toInt() and 0xff
        outgoing.forEach { transport.write(it) }
        val response = withTimeoutOrNull(2500) {
            val first = transport.readPacket() ?: return@withTimeoutOrNull null
            val raw = if (V1Codec.isMultiPacketHeader(first)) {
                val count = V1Codec.expectedPacketCount(first)
                check(count in 1..16) { "Invalid V1 fragment count=$count" }
                val packets = mutableListOf(first)
                repeat(count) { packets += transport.readPacket() ?: return@withTimeoutOrNull null }
                val bytes = packets.drop(1).flatMapIndexed { index, packet ->
                    val size = packet.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                    check(size in 1..18 && size <= packet.size - 2) { "Invalid V1 fragment size=$size" }
                    check(packet[0].toInt() and 0xff == if (index == count - 1) 255 else index) {
                        "Invalid V1 fragment sequence"
                    }
                    packet.copyOfRange(2, 2 + size).toList()
                }.toByteArray()
                check(first.size >= 4 && bytes.size == first[2].toInt() and 0xff) { "Incomplete V1 fragmented response" }
                bytes
            } else {
                val length = first.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                check(length in 5..first.size) { "Invalid V1 response length=$length bytes=${first.size}" }
                first.copyOf(length)
            }
            check(raw.size >= 5 && raw[1].toInt() and 0xff == raw.size) { "V1 response declared length mismatch" }
            check(V1Codec.verifyChecksum(raw)) { "V1 checksum mismatch" }
            if (raw[2].toInt() and 0xff != expectedCommand) {
                record("Unexpected V1 response opcode=${raw[2].toInt() and 0xff}; expected=$expectedCommand; ignored")
                return@withTimeoutOrNull null
            }
            if (expectedCommand != 2 && raw[3].toInt() != V1Message.STATUS_DONE) {
                record("V1 query rejected status=${raw[3].toInt() and 0xff}; capabilities UNKNOWN")
                return@withTimeoutOrNull null
            }
            if (expectedCommand == 2) record("RX V1 field response bytes=${raw.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}")
            if (expectedCommand == 0x82) check(raw.size >= 16) { "Incomplete V1 SystemInfo response" }
            if (expectedCommand == 0x84) check(raw.size >= 8) { "Incomplete V1 VersionInfo response" }
            V1Codec.decodeSingle(raw, fields.takeIf { it.isNotEmpty() })
        }
        record("RX V1 $description elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
            "type=${response?.javaClass?.simpleName ?: "TIMEOUT_OR_INVALID"}")
        delay(100)
        return response
    }

    private suspend fun discoverV1() {
        step(3, "Identité et fonctions déclarées (FitPro V1)")
        val info = queryV1(V1Message.Outgoing.DeviceInfo()) as? V1Message.Incoming.DeviceInfoResponse
            ?: error("No valid V1 DeviceInfo response; capabilities UNKNOWN")
        val maskCount = info.raw.getOrNull(12)?.toInt()?.and(0xff)
        check(info.raw.size >= 14 && info.raw[3].toInt() == V1Message.STATUS_DONE &&
            maskCount != null && 13 + maskCount <= info.raw.size - 1) {
            "Incomplete V1 DeviceInfo feature mask; capabilities UNKNOWN"
        }
        val fields = info.supportedBitFields
        record("protocol=V1 equipmentDeviceId=${info.deviceId} firmware=${info.softwareVersion} hardware=${info.hardwareVersion}")
        record("supportedBitFields=${fields.sorted().joinToString { "$it:${V1DataField.fromFieldIndex(it)?.name ?: "UNKNOWN"}" }}")
        record(capabilitySummary("V1", fields, complete = true))
        check(info.deviceId == 20) { "Equipment type is not ROWER (20); further reads skipped" }

        val commands = (queryV1(V1Message.Outgoing.SupportedCommands(info.deviceId))
            as? V1Message.Incoming.SupportedCommandsResponse)?.commandIds
        record("supportedCommands=${commands?.sorted()?.joinToString { "0x%02x".format(it) } ?: "UNKNOWN"}")
        if (commands?.contains(V1Message.CMD_SYSTEM_INFO) == true) {
            val system = queryV1(V1Message.Outgoing.SystemInfo()) as? V1Message.Incoming.SystemInfoResponse
            record("partNumber=${system?.partNumber ?: "UNKNOWN"} model=${system?.model ?: "UNKNOWN"}")
        }
        if (commands?.contains(V1Message.CMD_VERSION_INFO) == true) {
            val version = queryV1(V1Message.Outgoing.VersionInfo()) as? V1Message.Incoming.VersionInfoResponse
            record("masterLibraryVersion=${version?.masterLibraryVersion ?: "UNKNOWN"} build=${version?.masterLibraryBuild ?: "UNKNOWN"}")
        }
        step(4, "Lecture des limites, cibles et état ERG")
        if (commands?.contains(V1Message.CMD_READ_WRITE_DATA) != true) {
            record("ReadWriteData not declared or command list unavailable; all readbacks UNKNOWN; no blind reads")
            return
        }
        for (field in v1ReadFields.filter { it.fieldIndex in fields }) {
            val response = queryV1(V1Message.Outgoing.ReadWriteData(readFields = setOf(field)), setOf(field))
                as? V1Message.Incoming.DataResponse
            val value = response?.fields?.get(field)
            val valid = response?.status == V1Message.STATUS_DONE && !response.isTruncated && value?.isFinite() == true
            record("readback ${field.fieldIndex}:${field.name} status=${response?.status ?: "UNKNOWN"} " +
                "truncated=${response?.isTruncated} value=${if (valid) value else "UNKNOWN"}")
            if (response?.status == V1Message.STATUS_SECURITY_BLOCK) {
                record("SECURITY_BLOCK: read access needs authentication; no unlock attempted; remaining reads skipped")
                break
            }
        }
    }

    private suspend fun sendV2(message: V2Message.Outgoing) {
        require(message is V2Message.Outgoing.QueryFeatures || message is V2Message.Outgoing.QueryProductInfo ||
            message is V2Message.Outgoing.Subscribe || message is V2Message.Outgoing.Unsubscribe)
        record("TX V2 ${message.javaClass.simpleName}" + if (message is V2Message.Outgoing.Subscribe)
            " features=${message.features.joinToString { "${it.code}:${it.name}" }}" else "")
        transport.write(V2Codec.encode(message))
    }

    private suspend fun nextV2(): V2Message.Incoming? {
        val packet = transport.readPacket() ?: run { delay(10); return null }
        if (packet.size >= 3 && packet[1].toInt() and 15 == 1) {
            check((packet[2].toInt() and 0xff) % 2 == 0) { "Malformed V2 feature list; odd payload length" }
        }
        val message = V2Codec.decode(packet)
        when (message) {
            is V2Message.Incoming.Error -> { invalidV2Frames++; record("RX V2 ERROR ${message.describe()}") }
            is V2Message.Incoming.Unknown, null -> {
                invalidV2Frames++
                record("RX V2 unknown/invalid packet bytes=${packet.size}; payload omitted")
            }
            else -> Unit
        }
        return message
    }

    private suspend fun discoverV2() {
        step(3, "Identité et fonctions déclarées (FitPro V2)")
        sendV2(V2Message.Outgoing.QueryFeatures())
        val features = mutableSetOf<V2FeatureId>()
        val unknown = mutableSetOf<Int>()
        val complete = withTimeoutOrNull(5000) {
            while (true) {
                val response = nextV2()
                if (response is V2Message.Incoming.SupportedFeatures) {
                    features += response.features
                    unknown += response.unknownCodes
                    record("RX V2 featureChunk=${(response.features.map { it.code } + response.unknownCodes).joinToString()} end=${response.isEndOfList}")
                    if (response.isEndOfList) return@withTimeoutOrNull true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true && invalidV2Frames == 0
        record("protocol=V2 featureListComplete=$complete")
        record("supportedFeatures=${(features.map { it.code } + unknown).sorted().joinToString { "$it:${V2FeatureId.fromCode(it)?.name ?: "UNKNOWN"}" }}")
        record(capabilitySummary("V2", features.map { it.code }.toSet(), complete))
        check(complete) { "Incomplete V2 feature list; no blind subscriptions; missing features UNKNOWN" }

        sendV2(V2Message.Outgoing.QueryProductInfo())
        withTimeoutOrNull(2500) {
            while (true) {
                val field = nextV2() as? V2Message.Incoming.ProductInfoField ?: continue
                if (field.isEndOfList) return@withTimeoutOrNull true
                if (field.fieldType in setOf(1, 2, 3, 4, 9)) record("productInfo field=${field.fieldType} value=${field.text}")
            }
        } ?: record("Product info did not complete; identity partial/UNKNOWN")
        step(4, "Lecture des limites, cibles et état du rameur")
        check(V2FeatureId.DEVICE_TYPE in features) { "No DEVICE_TYPE declared; rower identity UNKNOWN; further reads skipped" }
        val subscribed = mutableListOf<V2FeatureId>()
        try {
            sendV2(V2Message.Outgoing.Subscribe(listOf(V2FeatureId.DEVICE_TYPE)))
            subscribed += V2FeatureId.DEVICE_TYPE
            val type = withTimeoutOrNull(3000) {
                while (true) {
                    val response = nextV2()
                    if (response is V2Message.Incoming.Event && response.feature == V2FeatureId.DEVICE_TYPE)
                        return@withTimeoutOrNull response.value
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            record("equipmentDeviceId=${type ?: "UNKNOWN"}; 20=ROWER")
            check(type == 20f) { "Controller did not confirm ROWER (20); further reads skipped" }
            val reads = v2ReadFields.filter { it in features }
            if (reads.isNotEmpty()) {
                sendV2(V2Message.Outgoing.Subscribe(reads))
                subscribed += reads
                val seen = mutableSetOf<V2FeatureId>()
                withTimeoutOrNull(4000) {
                    while (seen.size < reads.size) {
                        val response = nextV2()
                        if (response is V2Message.Incoming.Event && response.feature in reads) {
                            if (response.value.isFinite()) {
                                seen += response.feature
                                record("readback ${response.feature.code}:${response.feature.name} value=${response.value}")
                            } else record("readback ${response.feature.name} NON_FINITE; ignored")
                        }
                    }
                }
                reads.filter { it !in seen }.forEach { record("readback ${it.code}:${it.name}=UNKNOWN (no event within deadline)") }
            }
        } finally {
            // Unsubscribe only our own bounded probe subscriptions, never a workout teardown write.
            if (subscribed.isNotEmpty()) sendV2(V2Message.Outgoing.Unsubscribe(subscribed))
        }
    }

    companion object {
        val v1ReadFields = listOf(V1DataField.MAX_RESISTANCE_LEVEL, V1DataField.RESISTANCE,
            V1DataField.WATT_GOAL, V1DataField.IS_CONSTANT_WATTS_MODE, V1DataField.WORKOUT_MODE,
            V1DataField.WATTS, V1DataField.STROKES, V1DataField.STROKES_PER_MINUTE)
        val v2ReadFields = listOf(V2FeatureId.MAX_RESISTANCE, V2FeatureId.TARGET_RESISTANCE,
            V2FeatureId.GOAL_WATTS, V2FeatureId.MAX_WATTS, V2FeatureId.WORKOUT_STATE,
            V2FeatureId.WATTS, V2FeatureId.ROWER_TOTAL_STROKES, V2FeatureId.ROWER_STROKES_PER_MINUTE)

        fun capabilitySummary(protocol: String, ids: Set<Int>, complete: Boolean): String {
            fun declared(vararg codes: Int) = when {
                codes.all { it in ids } -> "DECLARED_NOT_WRITE_TESTED"
                complete -> "NOT_DECLARED"
                else -> "UNKNOWN_INCOMPLETE_LIST"
            }
            val resistance = if (protocol == "V1") declared(2) else declared(503)
            val erg = if (protocol == "V1") declared(61, 119) else declared(523)
            return "resistanceControl=$resistance\nnativeErg=$erg\n" +
                "Déclaration de fonctions uniquement : aucun changement de résistance ou de watts testé.\n" +
                "Une cible lisible ne prouve pas qu'une commande sera acceptée ni qu'un mode ERG fonctionne."
        }
    }
}
