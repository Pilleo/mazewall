package io.mazewall.profiler.tierE.daemon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A validated x86_64 SystemTap SDT note for the Tier E native marker. */
internal data class UsdtProbeNote(
    val provider: String,
    val probe: String,
    val arguments: String,
    val virtualAddress: ULong,
    val fileOffset: Long,
    val semaphoreVirtualAddress: ULong,
)

/** Binds the control-frame offset to the one validated USDT probe location. */
internal fun UsdtProbeNote.requireExpectedOffset(expectedOffset: Long) {
    require(expectedOffset >= 0 && expectedOffset == fileOffset) {
        "USDT marker offset mismatch: expected=$expectedOffset note=$fileOffset"
    }
}

/**
 * Bounded, dependency-free reader for `.note.stapsdt` records.
 *
 * The native agent stays the only producer of marker state. This parser merely
 * proves that an explicitly requested USDT attachment has the ABI consumed by
 * the existing marker BPF program before a perf event can be enabled.
 */
internal object UsdtProbeNotes {
    private const val ELF_HEADER_SIZE = 64
    private const val PROGRAM_HEADER_SIZE = 56
    private const val SECTION_HEADER_SIZE = 64
    private const val ELF_MACHINE_X86_64 = 62
    private const val PT_LOAD = 1
    private const val PT_NOTE = 4
    private const val SHT_NOTE = 7
    private const val NT_STAPSDT = 3
    private const val MAX_LIBRARY_BYTES = 64 * 1024 * 1024L
    private const val EXPECTED_ARGUMENTS_V1 = "8@%rdi 4@%esi"
    private const val EXPECTED_ARGUMENTS_V2 = "8@%rdi 8@%rsi 4@%edx"
    private const val STACK_MARKER_PROVIDER = "mazewall"
    private const val STACK_MARKER_V1 = "stack_marker_v1"
    private const val STACK_MARKER_V2 = "stack_marker_v2"

    /** Finds the one versioned native marker ABI consumed by the Tier E BPF program. */
    fun findStackMarkerV1(library: Path): UsdtProbeNote = find(library, STACK_MARKER_PROVIDER, STACK_MARKER_V1)

    /** Finds the v2 marker carrying `(invocationId, stackTraceId, flags)`. */
    fun findStackMarkerV2(library: Path): UsdtProbeNote = find(library, STACK_MARKER_PROVIDER, STACK_MARKER_V2)

    fun find(
        library: Path,
        provider: String,
        probe: String,
    ): UsdtProbeNote {
        require(provider.isNotEmpty()) { "USDT provider must not be empty" }
        require(probe.isNotEmpty()) { "USDT probe must not be empty" }
        val image = readBounded(library)
        return parse(image, provider, probe)
    }

    private fun readBounded(library: Path): ByteBuffer =
        FileChannel.open(library, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            require(size in ELF_HEADER_SIZE.toLong()..MAX_LIBRARY_BYTES) {
                "USDT library size is outside the supported bounded range: $size"
            }
            val bytes = ByteBuffer.allocate(size.toInt())
            while (bytes.hasRemaining()) {
                check(channel.read(bytes) >= 0) { "USDT library changed while being read" }
            }
            bytes.flip()
            bytes.order(ByteOrder.LITTLE_ENDIAN)
        }

    private fun parse(
        image: ByteBuffer,
        provider: String,
        probe: String,
    ): UsdtProbeNote {
        require(image.limit() >= ELF_HEADER_SIZE) { "truncated ELF header" }
        require(
            image.get(0).toInt() and 0xff == 0x7f &&
            image.get(1).toInt().toChar() == 'E' &&
            image.get(2).toInt().toChar() == 'L' &&
            image.get(3).toInt().toChar() == 'F',
        ) { "not an ELF image" }
        require(image.get(4).toInt() == 2) { "USDT requires ELF64" }
        require(image.get(5).toInt() == 1) { "USDT requires little-endian ELF" }
        require(u16(image, 18) == ELF_MACHINE_X86_64) { "USDT marker ABI is currently x86_64 only" }
        val programOffset = u64(image, 32)
        val programEntrySize = u16(image, 54)
        val programCount = u16(image, 56)
        require(programEntrySize >= PROGRAM_HEADER_SIZE) { "unsupported ELF program-header size: $programEntrySize" }
        val programBytes = checkedMultiply(programEntrySize.toLong(), programCount.toLong(), "program-header bytes")
        requireRange(image, programOffset, programBytes, "program-header table")

        val loads = ArrayList<LoadSegment>()
        val noteSegments = LinkedHashSet<FileRange>()
        repeat(programCount) { index ->
            val offset = checkedAdd(programOffset, checkedMultiply(index.toLong(), programEntrySize.toLong(), "program-header offset"), "program-header offset")
            val type = u32(image, offset)
            val fileOffset = u64(image, checkedAdd(offset, 8, "program file offset"))
            val virtualAddress = u64(image, checkedAdd(offset, 16, "program virtual address"))
            val fileSize = u64(image, checkedAdd(offset, 32, "program file size"))
            when (type) {
                PT_LOAD.toLong() -> loads += LoadSegment(fileOffset, virtualAddress, fileSize)
                PT_NOTE.toLong() -> {
                    requireRange(image, fileOffset, fileSize, "note segment")
                    noteSegments += FileRange(fileOffset, fileSize)
                }
            }
        }

        val sectionOffset = u64(image, 40)
        val sectionEntrySize = u16(image, 58)
        val sectionCount = u16(image, 60)
        if (sectionOffset != 0L || sectionCount != 0) {
            require(sectionOffset != 0L && sectionCount != 0) { "incomplete ELF section-header table" }
            require(sectionEntrySize >= SECTION_HEADER_SIZE) { "unsupported ELF section-header size: $sectionEntrySize" }
            val sectionBytes = checkedMultiply(sectionEntrySize.toLong(), sectionCount.toLong(), "section-header bytes")
            requireRange(image, sectionOffset, sectionBytes, "section-header table")
            repeat(sectionCount) { index ->
                val offset = checkedAdd(sectionOffset, checkedMultiply(index.toLong(), sectionEntrySize.toLong(), "section-header offset"), "section-header offset")
                if (u32(image, checkedAdd(offset, 4, "section type")) == SHT_NOTE.toLong()) {
                    val fileOffset = u64(image, checkedAdd(offset, 24, "section file offset"))
                    val fileSize = u64(image, checkedAdd(offset, 32, "section file size"))
                    requireRange(image, fileOffset, fileSize, "note section")
                    noteSegments += FileRange(fileOffset, fileSize)
                }
            }
        }

        var result: UsdtProbeNote? = null
        noteSegments.forEach { segment ->
            var cursor = segment.offset
            val end = checkedAdd(segment.offset, segment.size, "note segment end")
            while (cursor < end) {
                requireRange(image, cursor, 12, "note header")
                val nameSize = u32(image, cursor)
                val descriptorSize = u32(image, checkedAdd(cursor, 4, "note descriptor size"))
                val type = u32(image, checkedAdd(cursor, 8, "note type"))
                val nameOffset = checkedAdd(cursor, 12, "note name offset")
                val paddedNameSize = align4(nameSize)
                val descriptorOffset = checkedAdd(nameOffset, paddedNameSize, "note descriptor offset")
                val paddedDescriptorSize = align4(descriptorSize)
                val next = checkedAdd(descriptorOffset, paddedDescriptorSize, "note end")
                require(next <= end) { "truncated ELF note" }
                if (type == NT_STAPSDT.toLong() && bytesEqual(image, nameOffset, nameSize, "stapsdt\u0000")) {
                    parseStapsdt(image, descriptorOffset, descriptorSize, loads, provider, probe)?.let { candidate ->
                        require(result == null) { "duplicate USDT marker note: $provider:$probe" }
                        result = candidate
                    }
                }
                cursor = next
            }
        }
        return requireNotNull(result) { "missing USDT marker note: $provider:$probe" }
    }

    private fun parseStapsdt(
        image: ByteBuffer,
        descriptorOffset: Long,
        descriptorSize: Long,
        loads: List<LoadSegment>,
        expectedProvider: String,
        expectedProbe: String,
    ): UsdtProbeNote? {
        require(descriptorSize >= 24) { "truncated stapsdt descriptor" }
        val descriptorEnd = checkedAdd(descriptorOffset, descriptorSize, "stapsdt descriptor end")
        val location = u64(image, descriptorOffset).toULong()
        val semaphore = u64(image, checkedAdd(descriptorOffset, 16, "stapsdt semaphore")).toULong()
        var cursor = checkedAdd(descriptorOffset, 24, "stapsdt strings")
        val provider = nulString(image, cursor, descriptorEnd, "stapsdt provider").also { cursor = it.next }.value
        val probe = nulString(image, cursor, descriptorEnd, "stapsdt probe").also { cursor = it.next }.value
        val arguments = nulString(image, cursor, descriptorEnd, "stapsdt arguments").value
        if (provider != expectedProvider || probe != expectedProbe) return null
        require(semaphore == 0uL) { "USDT marker semaphore is unsupported" }
        val expectedArguments = when (probe) {
            STACK_MARKER_V2 -> EXPECTED_ARGUMENTS_V2
            else -> EXPECTED_ARGUMENTS_V1
        }
        require(arguments == expectedArguments) { "unsupported USDT marker argument contract: $arguments" }
        val fileOffset = loads.firstNotNullOfOrNull { load -> load.fileOffsetFor(location) }
            ?: throw IllegalArgumentException("USDT marker location is not mapped by a PT_LOAD segment")
        return UsdtProbeNote(provider, probe, arguments, location, fileOffset, semaphore)
    }

    private fun LoadSegment.fileOffsetFor(address: ULong): Long? {
        if (fileSize == 0L || address < virtualAddress.toULong()) return null
        val relative = address - virtualAddress.toULong()
        if (relative >= fileSize.toULong()) return null
        return checkedAdd(fileOffset, relative.toLong(), "USDT probe file offset")
    }

    private fun nulString(
        image: ByteBuffer,
        offset: Long,
        end: Long,
        label: String,
    ): ParsedString {
        var cursor = offset
        while (cursor < end && image.get(cursor.toInt()).toInt() != 0) cursor++
        require(cursor < end) { "unterminated $label" }
        val length = (cursor - offset).toInt()
        val bytes = ByteArray(length)
        repeat(length) { index -> bytes[index] = image.get(offset.toInt() + index) }
        return ParsedString(bytes.toString(Charsets.US_ASCII), cursor + 1)
    }

    private fun bytesEqual(
        image: ByteBuffer,
        offset: Long,
        length: Long,
        expected: String,
    ): Boolean {
        val expectedBytes = expected.encodeToByteArray()
        if (length != expectedBytes.size.toLong()) return false
        return expectedBytes.indices.all { image.get(offset.toInt() + it) == expectedBytes[it] }
    }

    private fun u16(
        image: ByteBuffer,
        offset: Long,
    ): Int {
        requireRange(image, offset, 2, "u16")
        return image.getShort(offset.toInt()).toInt() and 0xffff
    }

    private fun u32(
        image: ByteBuffer,
        offset: Long,
    ): Long {
        requireRange(image, offset, 4, "u32")
        return image.getInt(offset.toInt()).toLong() and 0xffff_ffffL
    }

    private fun u64(
        image: ByteBuffer,
        offset: Long,
    ): Long {
        requireRange(image, offset, 8, "u64")
        return image.getLong(offset.toInt())
    }

    private fun align4(value: Long): Long = checkedAdd(value, 3, "note alignment") and 3.inv().toLong()

    private fun checkedAdd(
        left: Long,
        right: Long,
        label: String,
    ): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "overflow computing $label" }
        return left + right
    }

    private fun checkedMultiply(
        left: Long,
        right: Long,
        label: String,
    ): Long {
        require(left >= 0 && right >= 0 && (left == 0L || right <= Long.MAX_VALUE / left)) { "overflow computing $label" }
        return left * right
    }

    private fun requireRange(
        image: ByteBuffer,
        offset: Long,
        size: Long,
        label: String,
    ) {
        require(offset >= 0 && size >= 0 && offset <= image.limit().toLong() && size <= image.limit().toLong() - offset) {
            "truncated $label"
        }
    }

    private data class FileRange(
        val offset: Long,
        val size: Long,
    )

    private data class LoadSegment(
        val fileOffset: Long,
        val virtualAddress: Long,
        val fileSize: Long,
    )

    private data class ParsedString(
        val value: String,
        val next: Long,
    )
}
