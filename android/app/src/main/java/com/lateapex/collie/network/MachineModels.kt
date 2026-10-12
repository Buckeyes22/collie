package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Native wire models for the lead-owned machine census and minute history. */
@Serializable
data class MachineSample(
    val cpu: Double,
    val cores: Int,
    val memUsed: Long,
    val memTotal: Long,
    val load1: Double? = null,
    val rxBps: Double? = null,
    val txBps: Double? = null,
    val disks: List<MachineDisk>? = null,
)

@Serializable
data class MachineDisk(val mount: String, val used: Long, val total: Long)

@Serializable
data class MachineSpark(
    val stepMs: Long,
    val cpu: List<Double?>,
    val mem: List<Double?>,
)

@Serializable
data class MachineRow(
    val id: String,
    val name: String,
    val isLead: Boolean,
    val health: String,
    val sample: MachineSample? = null,
    val sampledAt: Long? = null,
    val alerts: MachineAlerts = MachineAlerts(),
    val firing: List<String> = emptyList(),
    val spark: MachineSpark? = null,
)

@Serializable
data class MachineAlerts(
    val cpu: MachineAlertRule? = null,
    val mem: MachineAlertRule? = null,
    val disk: MachineAlertRule? = null,
)

@Serializable
data class MachineAlertRule(val above: Double, val forMin: Int)

@Serializable
data class MachinesResponse(val ts: Long, val machines: List<MachineRow>)

@Serializable
data class MachineHistoryResponse(
    val ts: Long,
    val stepMs: Long,
    val points: List<MachineHistoryPoint>,
)

/** The wire tuple: minute start, cpu average/max, memory fraction, rx/tx B/s, disk fraction. */
@Serializable(with = MachineHistoryPointSerializer::class)
data class MachineHistoryPoint(
    val t: Long,
    val cpuAvg: Double,
    val cpuMax: Double,
    val mem: Double,
    val rxBps: Double? = null,
    val txBps: Double? = null,
    val disk: Double? = null,
)

/** History points are a fixed seven-field JSON tuple on the established bridge API. */
private object MachineHistoryPointSerializer : KSerializer<MachineHistoryPoint> {
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("MachineHistoryPoint", StructureKind.LIST) {
        element("t", Long.serializer().descriptor)
        element("cpuAvg", Double.serializer().descriptor)
        element("cpuMax", Double.serializer().descriptor)
        element("mem", Double.serializer().descriptor)
        element("rxBps", Double.serializer().nullable.descriptor)
        element("txBps", Double.serializer().nullable.descriptor)
        element("disk", Double.serializer().nullable.descriptor)
    }

    override fun deserialize(decoder: Decoder): MachineHistoryPoint {
        require(decoder is JsonDecoder) { "Machine history requires JSON" }
        val values = decoder.decodeJsonElement() as? JsonArray ?: throw SerializationException("History point must be an array")
        require(values.size == 6 || values.size == 7) { "History point must have six or seven values" }
        fun number(index: Int): Double {
            val primitive = values[index].jsonPrimitive
            require(!primitive.isString) { "History values must be JSON numbers" }
            return primitive.doubleOrNull ?: throw SerializationException("Invalid history number")
        }
        fun optional(index: Int): Double? = if (index >= values.size || values[index] == JsonNull) null else number(index)
        val timeValue = values[0].jsonPrimitive
        require(!timeValue.isString) { "History time must be a JSON number" }
        val time = timeValue.longOrNull ?: throw SerializationException("Invalid history time")
        return MachineHistoryPoint(time, number(1), number(2), number(3), optional(4), optional(5), optional(6))
    }

    override fun serialize(encoder: Encoder, value: MachineHistoryPoint) {
        require(encoder is JsonEncoder) { "Machine history requires JSON" }
        fun nullable(number: Double?) = number?.let(::JsonPrimitive) ?: JsonNull
        encoder.encodeJsonElement(JsonArray(listOf(
            JsonPrimitive(value.t), JsonPrimitive(value.cpuAvg), JsonPrimitive(value.cpuMax), JsonPrimitive(value.mem),
            nullable(value.rxBps), nullable(value.txBps), nullable(value.disk),
        )))
    }
}

/** Strictly validates the established API contract before the UI treats a response as telemetry. */
internal fun MachinesResponse.isValid(): Boolean {
    if (ts < 0) return false
    if (machines.map { it.id }.toSet().size != machines.size) return false
    return machines.all { row ->
        isValidMachineId(row.id) && row.name.length <= 256 &&
            row.health in MACHINE_HEALTH && (row.sampledAt == null || row.sampledAt in 0..ts) &&
            (row.sample == null) == (row.sampledAt == null) &&
            (row.sample == null || row.sample.isValid()) &&
            (row.spark == null || row.spark.isValid()) &&
            row.firing.all { it in MACHINE_METRICS } && row.firing.distinct().size == row.firing.size && row.alerts.isValid()
    }
}

/** Collie's crew member ID wire grammar; solo machines use the valid literal "local". */
internal fun isValidMachineId(value: String): Boolean = MACHINE_ID.matches(value)

internal fun MachineHistoryResponse.isValid(requestedSince: Long? = null): Boolean {
    if (ts < 0 || stepMs != MACHINE_MINUTE_MS || points.size > MAX_MACHINE_HISTORY_POINTS) return false
    var previous = -1L
    return points.all { point ->
        val valid = point.t >= 0 && (requestedSince == null || point.t >= requestedSince) && point.t % stepMs == 0L &&
            point.t > previous && point.t <= ts && ts - point.t < 86_400_000L &&
            point.cpuAvg.fraction() && point.cpuMax.fraction() && point.cpuAvg <= point.cpuMax &&
            point.mem.fraction() && point.rxBps.nonNegativeOrNull() && point.txBps.nonNegativeOrNull() &&
            point.disk.fractionOrNull()
        if (valid) previous = point.t
        valid
    }
}

private fun MachineSample.isValid(): Boolean = cpu.fraction() && cores > 0 && memUsed >= 0 && memTotal > 0 &&
    memUsed <= memTotal &&
    load1.nonNegativeOrNull() && rxBps.nonNegativeOrNull() && txBps.nonNegativeOrNull() &&
    (disks == null || disks.size <= MAX_MACHINE_DISKS && disks.all {
        it.mount.isNotBlank() && it.mount.length <= 256 && it.used >= 0 && it.total > 0 && it.used <= it.total
    })

private fun MachineSpark.isValid(): Boolean = stepMs == MACHINE_MINUTE_MS && cpu.size in 1..MAX_MACHINE_SPARK_MINUTES &&
    mem.size == cpu.size && cpu.all { it.fractionOrNull() } && mem.all { it.fractionOrNull() }

private fun MachineAlerts.isValid(): Boolean = listOfNotNull(cpu, mem, disk).all {
    it.above.isFinite() && it.above in .5..0.99 && it.forMin in 5..120
}

private fun Double.fraction() = isFinite() && this in 0.0..1.0
private fun Double?.fractionOrNull() = this == null || this.fraction()
private fun Double?.nonNegativeOrNull() = this == null || (isFinite() && this >= 0.0)

const val MACHINE_MINUTE_MS = 60_000L
const val MAX_MACHINE_SPARK_MINUTES = 60
const val MAX_MACHINE_HISTORY_POINTS = 1440
const val MAX_MACHINE_DISKS = 4

private val MACHINE_HEALTH = setOf("reachable", "unreachable", "incompatible", "conflicted")
private val MACHINE_METRICS = setOf("cpu", "mem", "disk")
private val MACHINE_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
