package io.mazewall.ffi.internal

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** ZIP32 central namespace and exact local mappings of already bounded, owned inner bytes. */
@Suppress("MagicNumber") // PKWARE APPNOTE fixed field offsets/widths, not tunable policy limits.
internal class NestedZipTable(
    private val bytes: ByteArray,
) {
    internal data class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressed: Int,
        val size: Int,
        val local: Int,
        val nameBytes: ByteArray,
    )

    fun validate(): Map<String, Entry> {
        require(bytes.size >= END_SIZE) { "Truncated nested ZIP end record" }
        val end = findEnd()
        require(u16(end + 4) == 0 && u16(end + 6) == 0) { "Split nested ZIP is unsupported" }
        val count = u16(end + 10)
        require(count in 1..ArtifactBytes.MAX_ENTRIES && u16(end + 8) == count) { "Nested ZIP central entry count exceeds bounds or disagrees" }
        val centralSize = boundedInt(end + 12)
        val central = boundedInt(end + 16)
        require(central.toLong() + centralSize == end.toLong()) { "Nested ZIP central directory bounds disagree" }
        val entries = linkedMapOf<String, Entry>()
        var cursor = central
        var expanded = 0L
        repeat(count) {
            range(cursor, CENTRAL_SIZE, end)
            require(u32(cursor) == CENTRAL_SIGNATURE) { "Missing nested ZIP central header" }
            val nameSize = u16(cursor + 28)
            val extraSize = u16(cursor + 30)
            val commentSize = u16(cursor + 32)
            range(cursor, CENTRAL_SIZE + nameSize + extraSize + commentSize, end)
            require(u16(cursor + 34) == 0) { "Split nested ZIP entry is unsupported" }
            val flags = u16(cursor + 8)
            val method = u16(cursor + 10)
            require(flags and SUPPORTED_FLAGS.inv() == 0 && method in setOf(0, 8)) { "Unsupported nested ZIP flags or compression" }
            val rawName = bytes.copyOfRange(cursor + CENTRAL_SIZE, cursor + CENTRAL_SIZE + nameSize)
            val name = decodeName(rawName, flags)
            ArtifactBytes.requireEntryName(name)
            val entry = Entry(name, flags, method, u32(cursor + 16), boundedInt(cursor + 20), boundedInt(cursor + 24), boundedInt(cursor + 42), rawName)
            require(entries.putIfAbsent(name, entry) == null) { "Ambiguous nested ZIP central names" }
            require(method != 0 || entry.compressed == entry.size && flags and DESCRIPTOR_FLAG == 0) { "Unsupported stored nested ZIP entry metadata" }
            expanded += entry.size
            require(expanded <= ArtifactBytes.MAX_ARTIFACT_BYTES) { "Nested ZIP expanded central sizes exceed bounded identity budget" }
            validateExtra(cursor + CENTRAL_SIZE + nameSize, extraSize)
            cursor += CENTRAL_SIZE + nameSize + extraSize + commentSize
        }
        require(cursor == end) { "Nested ZIP central entry count or length disagrees" }
        var localEnd = 0
        entries.values.sortedBy { it.local }.forEach { entry ->
            require(entry.local == localEnd) { "Nested ZIP local mappings overlap, alias or leave unindexed bytes" }
            localEnd = validateLocal(entry, central)
        }
        require(localEnd == central) { "Nested ZIP has unindexed local entries or trailing data" }
        return entries.toMap()
    }

    private fun validateLocal(
        entry: Entry,
        central: Int,
    ): Int {
        val local = entry.local
        range(local, LOCAL_SIZE, central)
        require(u32(local) == LOCAL_SIGNATURE) { "Nested ZIP central offset does not identify a local header" }
        require(u16(local + 6) == entry.flags && u16(local + 8) == entry.method) { "Nested ZIP local/central flags or methods disagree" }
        val nameSize = u16(local + 26)
        val extraSize = u16(local + 28)
        range(local, LOCAL_SIZE + nameSize + extraSize, central)
        val rawName = bytes.copyOfRange(local + LOCAL_SIZE, local + LOCAL_SIZE + nameSize)
        require(rawName.contentEquals(entry.nameBytes)) { "Nested ZIP local/central names disagree" }
        validateExtra(local + LOCAL_SIZE + nameSize, extraSize)
        val descriptor = entry.flags and DESCRIPTOR_FLAG != 0
        val crc = u32(local + 14)
        val compressed = u32(local + 18)
        val size = u32(local + 22)
        if (descriptor) {
            require((crc == 0L || crc == entry.crc) && (compressed == 0L || compressed == entry.compressed.toLong()) && (size == 0L || size == entry.size.toLong())) {
                "Nested ZIP local/central descriptor metadata disagrees"
            }
        } else {
            require(crc == entry.crc && compressed == entry.compressed.toLong() && size == entry.size.toLong()) { "Nested ZIP local/central metadata disagrees" }
        }
        val data = local + LOCAL_SIZE + nameSize + extraSize
        range(data, entry.compressed, central)
        var end = data + entry.compressed
        if (descriptor) {
            range(end, 12, central)
            if (u32(end) == DESCRIPTOR_SIGNATURE) end += 4
            range(end, 12, central)
            require(u32(end) == entry.crc && u32(end + 4) == entry.compressed.toLong() && u32(end + 8) == entry.size.toLong()) { "Nested ZIP data descriptor disagrees with central record" }
            end += 12
        }
        return end
    }

    private fun findEnd(): Int {
        val minimum = maxOf(0, bytes.size - END_SIZE - MAX_COMMENT)
        for (offset in bytes.size - END_SIZE downTo minimum) {
            if (u32(offset) == END_SIGNATURE && offset + END_SIZE + u16(offset + 20) == bytes.size) return offset
        }
        throw IllegalArgumentException("Nested ZIP end record is absent or inconsistent")
    }

    private fun decodeName(
        raw: ByteArray,
        flags: Int,
    ): String {
        require(flags and UTF8_FLAG != 0 || raw.all { it >= 0 }) { "Non-UTF8 nested ZIP entry names are unsupported" }
        val decoder = Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(raw)).toString()
    }

    private fun validateExtra(
        start: Int,
        size: Int,
    ) {
        val end = start + size
        var offset = start
        while (offset < end) {
            range(offset, 4, end)
            val id = u16(offset)
            val length = u16(offset + 2)
            require(id != ZIP64_EXTRA && id != UNICODE_PATH_EXTRA) { "Unsupported nested ZIP64 or alternate Unicode name metadata" }
            range(offset + 4, length, end)
            offset += 4 + length
        }
    }

    private fun boundedInt(offset: Int): Int {
        val value = u32(offset)
        require(value <= ArtifactBytes.MAX_ARTIFACT_BYTES) { "Nested ZIP offset/size exceeds bounded ZIP32 budget" }
        return value.toInt()
    }

    private fun range(
        offset: Int,
        length: Int,
        end: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= end.toLong() && end <= bytes.size) { "Nested ZIP record exceeds byte bounds" }
    }

    private fun u16(offset: Int): Int {
        range(offset, 2, bytes.size)
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    companion object {
        private const val END_SIZE = 22
        private const val CENTRAL_SIZE = 46
        private const val LOCAL_SIZE = 30
        private const val MAX_COMMENT = 65535
        private const val SUPPORTED_FLAGS = 0x080e
        private const val UTF8_FLAG = 0x0800
        private const val DESCRIPTOR_FLAG = 0x0008
        private const val ZIP64_EXTRA = 0x0001
        private const val UNICODE_PATH_EXTRA = 0x7075
        private const val END_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val DESCRIPTOR_SIGNATURE = 0x08074b50L
    }
}
