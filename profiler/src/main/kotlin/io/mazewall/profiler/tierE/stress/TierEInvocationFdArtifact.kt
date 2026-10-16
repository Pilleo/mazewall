package io.mazewall.profiler.tierE.stress

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Opt-in agent witness. FD identity comes from the independent target-written manifest. */
internal object TierEInvocationFdArtifact {
    private const val HEADER_BYTES = 32
    private const val ROW_BYTES = 16
    private const val MAX_ROWS = 65_536
    private const val SESSION_SHIFT_BITS = 32

    fun read(
        file: Path,
        declaredFds: Set<Int>,
        sessionTag: Int,
    ): Map<Int, Long> {
        check(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "missing owned invocation-FD witness" }
        val size = Files.size(file)
        check(size in HEADER_BYTES.toLong()..(HEADER_BYTES + MAX_ROWS * ROW_BYTES).toLong()) {
            "invocation-FD witness exceeds bounded retention"
        }
        val data = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN)
        check(
            data.get().toInt() == 'M'.code &&
            data.get().toInt() == 'Z'.code &&
            data.get().toInt() == 'I'.code &&
            data.get().toInt() == 'F'.code &&
            data.short.toInt() == 1 &&
            data.short.toInt() == ROW_BYTES &&
            data.int == sessionTag &&
            data.int == 0,
        ) { "invalid invocation-FD witness version or session" }
        val rows = data.long
        val dropped = data.long
        check(rows in 1..MAX_ROWS.toLong() && dropped == 0L && size == HEADER_BYTES + rows * ROW_BYTES) {
            "incomplete or truncated invocation-FD witness"
        }
        val ids = HashSet<Long>(rows.toInt())
        val joined = HashMap<Int, Long>(declaredFds.size)
        repeat(rows.toInt()) {
            val id = data.long
            val fd = data.int
            val reserved = data.int
            check(id != 0L && (id ushr SESSION_SHIFT_BITS).toInt() == sessionTag && ids.add(id) && fd >= 0 && reserved == 0) {
                "invalid, repeated or incomplete native invocation-FD row"
            }
            if (fd in declaredFds) {
                check(joined.put(fd, id) == null) { "duplicate declared FD in native invocation-FD witness: $fd" }
            }
        }
        check(joined.keys == declaredFds) { "native invocation-FD witness differs from original target-authored manifest" }
        return joined.toMap()
    }
}
