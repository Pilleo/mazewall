package io.mazewall.profiler.tierE.engine

import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections

internal object BoundaryLayout {
    const val MAGIC = 0x44425a4d
    const val HEADER_BYTES = 128
    const val RECORD_BYTES = 112
    const val CONTROL_BYTES = 128
    const val CAPACITY = 32768
    const val COUNTER_BYTES = 8
    const val COUNTER_COUNT = 8
    const val CONTROL_USED_BYTES = 64
    const val HEADER_USED_BYTES = 40
    const val IMAGE_PREFIX_BYTES = HEADER_BYTES + CONTROL_BYTES
    const val RECORD_PLANE = 4
    const val RECORD_KTIME = 8
    const val RECORD_PID_TGID = 16
    const val RECORD_RAW_ID = 24
    const val RECORD_INVOCATION = 32
    const val RECORD_SEQUENCE = 40
    const val RECORD_WORDS = 48
    const val WORD_COUNT = 6
    const val RECORD_STAGE = 96
    const val RECORD_GENERATION = 100
    const val RECORD_AUX = 104
    const val RECORD_VALID = 108
    const val WORD_1 = RECORD_WORDS + COUNTER_BYTES
    const val WORD_2 = WORD_1 + COUNTER_BYTES
    const val WORD_3 = WORD_2 + COUNTER_BYTES
    const val WORD_4 = WORD_3 + COUNTER_BYTES
    const val ATTEMPTED = 0
    const val RESERVED = 8
    const val ENTRIES = 16
    const val TERMINALS = 24
    const val DROPS = 32
    const val HELPER_FAILURES = 40
    const val MAIN_IN_FLIGHT = 48
    const val MARKER_IN_FLIGHT = 56
    const val TERMINAL_COMMIT = 3
    const val VALID_FIELD_MASK = 1023u
    const val INITIAL_STORAGE_BYTES = 6270528L
    const val MAP_PAYLOAD_BYTES = RECORD_BYTES.toLong() * CAPACITY + CONTROL_BYTES
    const val MAP_EXCESS_BYTES = 20928L
    const val STORAGE_LIMIT_BYTES = 6L * 1024 * 1024
    const val RECEIPT_LIMIT_BYTES = 1024
    const val FDINFO_LIMIT_BYTES = 4096
    const val ASCII_SPACE = 32
    const val ASCII_LAST_PRINTABLE = 126
    const val LOOKUP_ATTR_BYTES = 32
    const val LOOKUP_KEY_ADDRESS = 8L
    const val LOOKUP_VALUE_ADDRESS = 16L
    const val INVOCATION_BIT = 6
    const val SEQUENCE_BIT = 7
    const val GENERATION_BIT = 8
    const val AUX_BIT = 9
    const val WORD_4_BIT = 4
    const val WORD_3_BIT = 3
}

internal fun publishBoundaryControlReceipt(
    line: String,
    output: PrintStream = System.out,
) {
    require(
        line.length in 1..BoundaryLayout.RECEIPT_LIMIT_BYTES &&
        line.last() == '\n' &&
        line.dropLast(1).all {
        it.code in BoundaryLayout.ASCII_SPACE..BoundaryLayout.ASCII_LAST_PRINTABLE
    },
    ) { "invalid boundary control receipt" }
    synchronized(output) {
        output.write(line.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        if (output.checkError()) throw IOException("boundary control receipt publication failed")
    }
}

internal fun boundaryControlReceipt(
    installation: Long,
    ordinal: Long,
    target: Int,
    epoch: Int,
    allocatedBytes: Long,
    control: BoundaryDiagnosticControl,
): String {
    require(installation > 0 && ordinal > 0 && target > 0 && epoch > 0)
    val combined = BoundaryLayout.INITIAL_STORAGE_BYTES + allocatedBytes - BoundaryLayout.MAP_PAYLOAD_BYTES
    require(allocatedBytes in BoundaryLayout.MAP_PAYLOAD_BYTES..(BoundaryLayout.MAP_PAYLOAD_BYTES + BoundaryLayout.MAP_EXCESS_BYTES) && combined <= BoundaryLayout.STORAGE_LIMIT_BYTES)
    val line = "tier-e-boundary-control: version=1 status=ok installation=$installation ordinal=$ordinal initialized=1 " +
        "target=$target epoch=$epoch capacity=32768 record_bytes=112 control_bytes=128 allocated_bytes=$allocatedBytes " +
        "combined_storage_bound=$combined bookkeeping_bound=65536 attempted=${control.attempted} reserved=${control.reserved} " +
        "entries=${control.entries} terminals=${control.terminals} drops=${control.drops} helper_failures=${control.helperFailures} " +
        "main_in_flight=${control.mainInFlight} marker_in_flight=${control.markerInFlight}\n"
    check(line.length <= BoundaryLayout.RECEIPT_LIMIT_BYTES)
    return line
}

internal fun streamBoundaryDiagnostics(
    output: Path,
    session: Long,
    targetTgid: Long,
    producerClosed: Boolean,
    quiescent: Boolean,
    read: (Boolean, Int, ByteBuffer) -> Unit,
    publish: (Path, Path) -> Unit = { temporary, destination -> Files.createLink(destination, temporary) },
) {
    require(output.isAbsolute && output == output.normalize()) { "diagnostic output must be an absolute normalized path" }
    require(session in 1..Int.MAX_VALUE.toLong() && targetTgid in 1..Int.MAX_VALUE.toLong())
    val temporary = output.resolveSibling("${output.fileName}.tmp")
    val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    FileChannel.open(temporary, options, mode).use { channel ->
        val scratch = ByteBuffer.allocate(BoundaryLayout.CONTROL_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        read(true, 0, scratch)
        val reserved = scratch.getLong(BoundaryLayout.RESERVED)
        val mainInFlight = scratch.getLong(BoundaryLayout.MAIN_IN_FLIGHT)
        val markerInFlight = scratch.getLong(BoundaryLayout.MARKER_IN_FLIGHT)
        require(reserved in 0..BoundaryLayout.CAPACITY.toLong()) { "diagnostic reserved count exceeds capacity" }
        val header = ByteBuffer.allocate(BoundaryLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header
            .putInt(BoundaryLayout.MAGIC)
            .putShort(1)
            .putShort(BoundaryLayout.HEADER_BYTES.toShort())
            .putInt(BoundaryLayout.RECORD_BYTES)
            .putInt(BoundaryLayout.CAPACITY)
        header.putLong(session).putLong(targetTgid).putInt(if (producerClosed) 1 else 0)
        header.putInt(if (quiescent && mainInFlight == 0L && markerInFlight == 0L) 1 else 0)
        header.clear()
        while (header.hasRemaining()) channel.write(header)
        scratch.clear()
        while (scratch.hasRemaining()) channel.write(scratch)
        repeat(reserved.toInt()) { index ->
            scratch.clear()
            repeat(BoundaryLayout.CONTROL_BYTES / Long.SIZE_BYTES) { scratch.putLong(it * Long.SIZE_BYTES, 0) }
            read(false, index, scratch)
            scratch.position(0).limit(BoundaryLayout.RECORD_BYTES)
            while (scratch.hasRemaining()) channel.write(scratch)
        }
        channel.force(true)
    }
    publish(temporary, output)
    Files.delete(temporary)
}

internal enum class BoundaryStage(
    val encoding: Int,
) {
    ENTRY(0),
    MARKER_CONFIG_MISSING(1),
    MARKER_WRONG_TARGET(2),
    MARKER_SESSION_MISSING(3),
    MARKER_WRONG_SESSION(4),
    MARKER_COUNTER_MISSING(5),
    MARKER_PID_SLOT_MISSING(6),
    MARKER_NO_TASK(7),
    MARKER_STORAGE_FAILED(8),
    MARKER_SET(9),
    MARKER_CLEAR(10),
    MAIN_NO_SESSION(20),
    MAIN_WRONG_TARGET(21),
    MAIN_NO_INFLIGHT(22),
    MAIN_CAPTURE_DISABLED(23),
    MAIN_DETACHED(24),
    MAIN_NO_TASK(25),
    MAIN_NO_STORAGE(26),
    MAIN_INACTIVE(27),
    MAIN_STALE(28),
    MAIN_ACTIVE_COUNTER_REACHED(29),
    MAIN_MARKER_STATE_UNAVAILABLE(30),
}

internal data class BoundaryDiagnosticConfig(
    val output: Path,
) {
    init {
        require(output == Path.of("/fixture/evidence/boundary.bpfdiag")) { "boundary diagnostics require the fixed fixture output" }
    }
}

internal fun boundaryAllocatedBytes(
    recordInfo: String,
    controlInfo: String,
): Long {
    fun allocation(
        info: String,
        size: Long,
        capacity: Long,
    ): Long {
        require(info.length <= BoundaryLayout.FDINFO_LIMIT_BYTES) { "diagnostic fdinfo exceeds bound" }

        fun number(name: String): Long {
            val values = info.lineSequence().filter { it.startsWith("$name:") }.toList()
            require(values.size == 1) { "missing or duplicate diagnostic map $name" }
            return requireNotNull(
                values
                .single()
                .substringAfter(':')
                .trim()
                .toLongOrNull(),
            ) { "invalid diagnostic map $name" }
        }
        require(number("map_type") == 2L && number("value_size") == size && number("max_entries") == capacity) { "diagnostic map layout mismatch" }
        return number("memlock").also { require(it >= size * capacity) { "unknown diagnostic map allocation" } }
    }
    val records = allocation(recordInfo, BoundaryLayout.RECORD_BYTES.toLong(), BoundaryLayout.CAPACITY.toLong())
    val control = allocation(controlInfo, BoundaryLayout.CONTROL_BYTES.toLong(), 1)
    val maximum = BoundaryLayout.MAP_PAYLOAD_BYTES + BoundaryLayout.MAP_EXCESS_BYTES
    require(control <= maximum && records <= maximum - control) { "diagnostic kernel allocation exceeds the 6 MiB combined budget" }
    return records + control
}

internal data class BoundaryDiagnosticControl(
    val attempted: Long,
    val reserved: Long,
    val entries: Long,
    val terminals: Long,
    val drops: Long,
    val helperFailures: Long,
    val mainInFlight: Long,
    val markerInFlight: Long,
)

/** Live map copies may interleave separately updated counters; only individual bounds are meaningful. */
internal fun decodeLiveBoundaryControl(bytes: ByteArray): BoundaryDiagnosticControl {
    require(bytes.size == BoundaryLayout.CONTROL_BYTES) { "invalid live boundary control size" }
    require((BoundaryLayout.CONTROL_USED_BYTES until BoundaryLayout.CONTROL_BYTES).all { bytes[it] == 0.toByte() }) { "nonzero live boundary control reserved bytes" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val control = readBoundaryCounters(buffer)
    require(control.attempted < BoundaryLayout.CAPACITY && control.reserved < BoundaryLayout.CAPACITY && control.entries <= BoundaryLayout.CAPACITY && control.terminals <= BoundaryLayout.CAPACITY) {
        "boundary diagnostic capacity unavailable"
    }
    require(control.drops == 0L && control.helperFailures == 0L) { "boundary diagnostic loss observed" }
    return control
}

private fun readBoundaryCounters(buffer: ByteBuffer): BoundaryDiagnosticControl {
    fun count(): Long = buffer.long.also { value -> require(value >= 0) { "unrepresentable boundary counter" } }
    return BoundaryDiagnosticControl(count(), count(), count(), count(), count(), count(), count(), count())
}

internal data class BoundaryDiagnosticRecord(
    val commit: Int,
    val plane: Int,
    val ktime: ULong,
    val pidTgid: ULong,
    val rawId: ULong,
    val invocation: ULong,
    val sequence: ULong,
    val words: List<ULong>,
    val stage: BoundaryStage,
    val generation: UInt,
    val aux: UInt,
    val validFields: UInt,
)

internal data class BoundaryDiagnosticSnapshot(
    val session: Long,
    val targetTgid: Long,
    val producerClosed: Boolean,
    val quiescent: Boolean,
    val control: BoundaryDiagnosticControl,
    val records: List<BoundaryDiagnosticRecord>,
) {
    val complete: Boolean get() = producerClosed &&
        quiescent &&
        control.attempted == control.reserved &&
        control.drops == 0L &&
        control.helperFailures == 0L &&
        control.mainInFlight == 0L &&
        control.markerInFlight == 0L &&
        control.entries == control.reserved &&
        control.terminals == control.reserved &&
        records.all { it.commit == BoundaryLayout.TERMINAL_COMMIT }
}

internal fun decodeBoundaryDiagnostics(bytes: ByteArray): BoundaryDiagnosticSnapshot {
    require(bytes.size in BoundaryLayout.IMAGE_PREFIX_BYTES..(BoundaryLayout.IMAGE_PREFIX_BYTES + BoundaryLayout.CAPACITY * BoundaryLayout.RECORD_BYTES)) { "invalid MZBD size" }
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(b.int == BoundaryLayout.MAGIC && b.short.toInt() == 1 && b.short.toInt() == BoundaryLayout.HEADER_BYTES) { "invalid MZBD header" }
    require(b.int == BoundaryLayout.RECORD_BYTES && b.int == BoundaryLayout.CAPACITY) { "invalid MZBD bounds" }
    val session = b.long
    val target = b.long
    require(session in 1..Int.MAX_VALUE.toLong() && target in 1..Int.MAX_VALUE.toLong()) { "invalid MZBD identity" }

    fun flag(): Boolean =
        when (b.int) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("invalid MZBD closure")
    }
    val closed = flag()
    val quiescent = flag()
    require((BoundaryLayout.HEADER_USED_BYTES until BoundaryLayout.HEADER_BYTES).all { bytes[it] == 0.toByte() }) { "nonzero MZBD header reserved bytes" }
    b.position(BoundaryLayout.HEADER_BYTES)
    val control = readBoundaryCounters(b)
    require(
        control.reserved <= BoundaryLayout.CAPACITY && control.reserved <= control.attempted && control.terminals <= control.entries && control.entries <= control.reserved,
    ) { "invalid MZBD counters" }
    require(control.drops <= control.attempted - control.reserved) { "invalid MZBD drops" }
    require((BoundaryLayout.HEADER_BYTES + BoundaryLayout.CONTROL_USED_BYTES until BoundaryLayout.IMAGE_PREFIX_BYTES).all { bytes[it] == 0.toByte() }) { "nonzero MZBD control reserved bytes" }
    require(bytes.size.toLong() == BoundaryLayout.IMAGE_PREFIX_BYTES + control.reserved * BoundaryLayout.RECORD_BYTES) { "incomplete MZBD image" }
    b.position(BoundaryLayout.IMAGE_PREFIX_BYTES)
    val records = List(control.reserved.toInt()) { decodeBoundaryRecord(b) }
    return BoundaryDiagnosticSnapshot(session, target, closed, quiescent, control, Collections.unmodifiableList(records))
}

private fun decodeBoundaryRecord(b: ByteBuffer): BoundaryDiagnosticRecord {
        val commit = b.int.also { value -> require(value in listOf(0, 1, BoundaryLayout.TERMINAL_COMMIT)) { "invalid MZBD commit" } }
        val plane = b.int.also { value -> require(value in 1..2 || commit == 0 && value == 0) { "invalid MZBD plane" } }
        val ktime = b.long.toULong()
        val pidTgid = b.long.toULong()
        val raw = b.long.toULong()
        val invocation = b.long.toULong()
        val sequence = b.long.toULong()
        val words = Collections.unmodifiableList(List(BoundaryLayout.WORD_COUNT) { b.long.toULong() })
        val stageNumber = b.int
        val stage = BoundaryStage.entries.singleOrNull { candidate -> candidate.encoding == stageNumber }
            ?: throw IllegalArgumentException("invalid MZBD stage")
        require(
            commit != BoundaryLayout.TERMINAL_COMMIT ||
            when (plane) {
            1 -> stage in BoundaryStage.MARKER_CONFIG_MISSING..BoundaryStage.MARKER_CLEAR
            2 -> stage in BoundaryStage.MAIN_NO_SESSION..BoundaryStage.MAIN_MARKER_STATE_UNAVAILABLE
            else -> false
        },
        ) { "invalid MZBD terminal stage for plane" }
        val generation = b.int.toUInt()
        val aux = b.int.toUInt()
        val valid = b.int.toUInt().also { value -> require(value <= BoundaryLayout.VALID_FIELD_MASK) { "invalid MZBD valid fields" } }
        return BoundaryDiagnosticRecord(commit, plane, ktime, pidTgid, raw, invocation, sequence, words, stage, generation, aux, valid)
}
