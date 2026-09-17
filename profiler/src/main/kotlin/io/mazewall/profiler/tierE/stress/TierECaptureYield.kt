package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.internal.attribution.InvocationDefinition
import io.mazewall.profiler.internal.attribution.InvocationId
import java.nio.file.Files
import java.nio.file.Path

/** Bounded, benchmark-only decoder for the native agent's terminal MZCY sidecar. */
internal object TierECaptureYield {
    private const val HEADER_SIZE = 24
    private const val RECORD_SIZE = 16
    private const val VERSION = 1
    private const val MAX_RECORDS = 65_536
    private const val MAX_BYTES = HEADER_SIZE + MAX_RECORDS * RECORD_SIZE

    fun read(path: Path): Report {
        val size = Files.size(path)
        require(size in HEADER_SIZE.toLong()..MAX_BYTES.toLong()) { "invalid MZCY size: $size" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size == size.toInt()) { "MZCY changed while reading" }
        require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'C'.code.toByte(), 'Y'.code.toByte()))) {
            "invalid MZCY magic"
        }
        require(u16(bytes, 4) == VERSION) { "unsupported MZCY version: ${u16(bytes, 4)}" }
        require(u16(bytes, 6) == RECORD_SIZE) { "invalid MZCY record size: ${u16(bytes, 6)}" }
        val sessionTag = u32(bytes, 8)
        require(u32(bytes, 12) == 0L) { "unsupported MZCY flags" }
        val count = u64(bytes, 16)
        require(count <= MAX_RECORDS.toLong()) { "MZCY record count exceeds bound: $count" }
        require(HEADER_SIZE.toLong() + count * RECORD_SIZE == bytes.size.toLong()) { "MZCY length does not match record count" }
        val records = ArrayList<Record>(count.toInt())
        repeat(count.toInt()) { index ->
            val offset = HEADER_SIZE + index * RECORD_SIZE
            val outcome = Outcome.fromCode(u16(bytes, offset + 10))
            require(u32(bytes, offset + 12) == 0L) { "unsupported MZCY record flags" }
            records += Record(u64(bytes, offset), u16(bytes, offset + 8), outcome)
        }
        return Report(sessionTag, records)
    }

    internal fun encodeForTest(
        sessionTag: Long,
        records: List<Record>,
    ): ByteArray {
        require(records.size <= MAX_RECORDS)
        val bytes = ByteArray(HEADER_SIZE + records.size * RECORD_SIZE)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[3] = 'Y'.code.toByte()
        putU16(bytes, 4, VERSION)
        putU16(bytes, 6, RECORD_SIZE)
        putU32(bytes, 8, sessionTag)
        putU64(bytes, 16, records.size.toLong())
        records.forEachIndexed { index, record ->
            val offset = HEADER_SIZE + index * RECORD_SIZE
            putU64(bytes, offset, record.invocationId)
            putU16(bytes, offset + 8, record.bindingCode)
            putU16(bytes, offset + 10, record.outcome.code)
        }
        return bytes
    }

    data class Record(
        val invocationId: Long,
        val bindingCode: Int,
        val outcome: Outcome,
    )

    data class Report(
        val sessionTag: Long,
        val records: List<Record>,
    ) {
        val captured: Int get() = records.count { it.outcome == Outcome.CAPTURED }
        val captureFailed: Int get() = records.count { it.outcome == Outcome.CAPTURE_FAILED }
        val admissionFailed: Int get() = records.count { it.outcome == Outcome.ADMISSION_FAILED }
        val excluded: Int get() = records.count { it.outcome == Outcome.EXCLUDED }
    }

    /** Exact benchmark-only join; it never changes attribution or native-call behavior. */
    fun join(
        report: Report,
        definitions: Collection<InvocationDefinition>,
    ): Join {
        val byId = definitions.associateBy { it.invocationId }
        val yields = report.records.groupBy { it.bindingCode }.mapValues { (_, records) ->
            val joined = records.map { it to byId[InvocationId(it.invocationId)] }
            BindingYield(
                records.size,
                joined.count { it.second != null },
                joined.count { it.second == null },
                joined.count { (record, definition) -> definition != null && !matches(record.outcome, definition) },
            )
        }
        val seen = report.records.mapTo(HashSet()) { InvocationId(it.invocationId) }
        return Join(
            yields,
            yields.values.sumOf { it.exactJoined },
            yields.values.sumOf { it.missingDefinition },
            yields.values.sumOf { it.outcomeMismatch },
            byId.keys.count { it !in seen },
        )
    }

    private fun matches(
        outcome: Outcome,
        definition: InvocationDefinition,
    ): Boolean =
        when (outcome) {
        Outcome.CAPTURED -> definition.captureFailure == null
        Outcome.CAPTURE_FAILED, Outcome.ADMISSION_FAILED, Outcome.EXCLUDED -> definition.captureFailure != null
    }

    data class BindingYield(
        val records: Int,
        val exactJoined: Int,
        val missingDefinition: Int,
        val outcomeMismatch: Int,
    )

    data class Join(
        val bindings: Map<Int, BindingYield>,
        val exactJoined: Int,
        val missingDefinition: Int,
        val outcomeMismatch: Int,
        val unrecordedDefinitions: Int,
    )

    enum class Outcome(
        val code: Int,
    ) {
        CAPTURED(1),
        CAPTURE_FAILED(2),
        ADMISSION_FAILED(3),
        EXCLUDED(4),
        ;

        companion object {
            fun fromCode(code: Int): Outcome = entries.singleOrNull { it.code == code } ?: error("unknown MZCY outcome: $code")
        }
    }

    private fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Long = (0 until 4).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }

    private fun u64(
        bytes: ByteArray,
        offset: Int,
    ): Long = (0 until 8).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }

    private fun putU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putU64(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        repeat(8) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
