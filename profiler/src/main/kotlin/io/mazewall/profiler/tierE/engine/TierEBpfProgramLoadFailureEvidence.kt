package io.mazewall.profiler.tierE.engine

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Private MZPL v1 image: 2048 metadata bytes, submitted program, then raw supplied-buffer prefix. */
internal object TierEBpfProgramLoadFailureEvidence {
    const val MAXIMUM_BYTES = 43008L
    const val HEADER_BYTES = 2048
    const val PROGRAM_BYTES = 8192
    const val MAXIMUM_SLOTS = 1024
    const val PREFIX_BYTES = 32768L
    private const val SCRATCH_BYTES = 256
    private const val MAGIC = 0x4c505a4d
    private const val LOG_CAPACITY = 65536L
    private const val UINT_MAX = 0xffffffffL
    private const val EFAULT = 14
    private const val SUMMARY_BYTES = 96L
    private const val BYTE_MASK = 0xff
    private const val MAXIMUM_ORDINAL = 4
    private const val INSTRUCTION_BYTES = 8
    private const val HASH_OFFSET = 128
    private const val NAME_OFFSET = 16
    private const val SUMMARY_OFFSET = 80
    private val output = Path.of("/fixture/evidence/prog-load-failure.diag")

    internal data class Capture(
        val request: BpfProgLoadRequestEncoder.Request,
        val installation: Long,
        val target: Int,
        val session: Int,
        val ordinal: Int,
        val name: String,
        val type: Int,
        val backend: TierESysEnterAttachmentBackend,
        val errno: Int,
        val raw: Long,
    )

    internal enum class Capacity {
        UNKNOWN,
        SUFFICIENT,
        INSUFFICIENT,
        SATURATED,
        MALFORMED,
    }

    internal fun classify(
        trueSize: Long,
        capacity: Long,
        nul: Long,
        errno: Int,
    ): Capacity {
        require(trueSize in 0..UINT_MAX && capacity > 0 && nul in -1 until capacity)
        return when {
            errno == EFAULT || nul < 0 -> Capacity.MALFORMED
            trueSize == 0L -> Capacity.UNKNOWN
            trueSize == UINT_MAX -> Capacity.SATURATED
            trueSize > capacity -> Capacity.INSUFFICIENT
            trueSize <= nul -> Capacity.MALFORMED
            else -> Capacity.SUFFICIENT
        }
    }

    fun write(capture: Capture) = write(capture, output)

    internal fun summary(log: MemorySegment): String {
        val text = StringBuilder()
        for (index in 0 until minOf(SUMMARY_BYTES, log.byteSize())) {
            val byte = log.get(JAVA_BYTE, index).toInt() and BYTE_MASK
            if (byte == 0) break
            text.append(if (byte in BoundaryLayout.ASCII_SPACE..BoundaryLayout.ASCII_LAST_PRINTABLE) byte.toChar() else '.')
        }
        return text.toString()
    }

    internal fun write(
        capture: Capture,
        destination: Path,
        write: (FileChannel, ByteBuffer) -> Int = { channel, bytes -> channel.write(bytes) },
        force: (FileChannel) -> Unit = { it.force(true) },
        publish: (Path, Path) -> Unit = { temporary, final -> Files.createLink(final, temporary) },
        close: (FileChannel) -> Unit = { it.close() },
    ) {
        val request = capture.request
        require(destination.isAbsolute && destination == destination.normalize())
        require(capture.installation > 0 && capture.target > 0 && capture.session > 0 && capture.ordinal in 1..MAXIMUM_ORDINAL)
        require(request.attr.byteSize() == BpfProgLoadLayout.DIAGNOSTIC_SIZE)
        require(request.instructions.byteSize() in 0..PROGRAM_BYTES.toLong() && request.instructions.byteSize() % INSTRUCTION_BYTES == 0L)
        require(request.verifierLog.byteSize() == LOG_CAPACITY)
        require(capture.name.length in 1 until BpfProgLoadLayout.PROGRAM_NAME_SIZE && capture.name.all { it.code in 1..Byte.MAX_VALUE })
        require(capture.backend.name.length <= BoundaryLayout.CONTROL_USED_BYTES)
        if (Files.exists(destination, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(destination.toString())
        val trueSize = Integer.toUnsignedLong(request.attr.get(JAVA_INT, BpfProgLoadLayout.LOG_TRUE_SIZE))
        val nul = firstNul(request.verifierLog)
        val state = classify(trueSize, LOG_CAPACITY, nul, capture.errno)
        // Byte updates avoid the provider's unaccounted direct-ByteBuffer copy fallback.
        val programHash = hash(request.instructions)
        val fullHash = hash(request.verifierLog)
        val prefix = request.verifierLog.asSlice(0, PREFIX_BYTES)
        val prefixHash = hash(prefix)
        val temporary = destination.resolveSibling("${destination.fileName}.tmp")
        val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Arena.ofConfined().use { arena ->
            val scratch = arena.allocate(SCRATCH_BYTES.toLong())
            withOwnedChannel(FileChannel.open(temporary, options, mode), close) { channel ->
                val header = scratch.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
                header
                    .putInt(MAGIC)
                    .putInt(1)
                    .putInt(HEADER_BYTES)
                    .putInt(request.instructions.byteSize().toInt())
                header.putLong(capture.installation).putInt(capture.target).putInt(capture.session)
                header
                    .putInt(capture.ordinal)
                    .putInt(capture.type)
                    .putInt(capture.errno)
                    .putInt(state.ordinal)
                header
                    .putLong(capture.raw)
                    .putLong(trueSize)
                    .putLong(LOG_CAPACITY)
                    .putLong(nul)
                header.putInt(PREFIX_BYTES.toInt()).putInt(1) // shortened raw buffer, always incomplete retention
                header.putInt(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) 1 else 2)
                header.putInt(request.attr.get(JAVA_INT, BpfProgLoadLayout.LOG_LEVEL))
                header.putInt(request.attr.get(JAVA_INT, BpfProgLoadLayout.PROGRAM_FLAGS))
                header.putInt(request.instructions.byteSize().toInt() / INSTRUCTION_BYTES)
                header.putInt(if (trueSize == 0L) 0 else 1).putInt(if (nul < 0) 0 else 1)
                header.position(HASH_OFFSET)
                header.put(programHash).put(fullHash).put(prefixHash)
                header.clear()
                writeFully(channel, header, write)
                scratch.fill(0)
                header.clear()
                putAscii(header, capture.name)
                header.position(NAME_OFFSET)
                putAscii(header, capture.backend.name)
                header.position(SUMMARY_OFFSET)
                putAscii(header, summary(request.verifierLog))
                header.clear()
                writeFully(channel, header, write)
                scratch.fill(0)
                repeat(HEADER_BYTES / SCRATCH_BYTES - 2) {
                    header.clear()
                    writeFully(channel, header, write)
                }
                writeFully(channel, request.instructions.asByteBuffer(), write)
                writeFully(channel, prefix.asByteBuffer(), write)
                check(channel.position() == HEADER_BYTES + request.instructions.byteSize() + PREFIX_BYTES)
                force(channel)
            }
        }
        publish(temporary, destination)
        Files.delete(temporary)
    }

    @Suppress("TooGenericExceptionCaught") // close errors participate in the same fatal-first evidence contract
    private fun withOwnedChannel(
        channel: FileChannel,
        close: (FileChannel) -> Unit,
        write: (FileChannel) -> Unit,
    ) {
        var failure: Throwable? = null
        try {
            write(channel)
        } catch (writeFailure: Throwable) {
            failure = writeFailure
        } finally {
            try {
                close(channel)
            } catch (closeFailure: Throwable) {
                failure = CleanupFailures.merge(failure, closeFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun putAscii(
        buffer: ByteBuffer,
        value: String,
    ) {
        value.forEach { buffer.put(it.code.toByte()) }
    }

    private fun writeFully(
        channel: FileChannel,
        bytes: ByteBuffer,
        write: (FileChannel, ByteBuffer) -> Int,
    ) {
        while (bytes.hasRemaining()) {
            val before = bytes.position()
            val count = write(channel, bytes)
            if (count <= 0 || bytes.position() - before != count) throw IOException("BPF failure evidence write made invalid progress")
        }
    }

    private fun firstNul(segment: MemorySegment): Long {
        for (index in 0 until segment.byteSize()) if (segment.get(JAVA_BYTE, index) == 0.toByte()) return index
        return -1
    }

    private fun hash(segment: MemorySegment): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until segment.byteSize()) digest.update(segment.get(JAVA_BYTE, index))
        return digest.digest()
    }
}
