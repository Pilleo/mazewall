package io.mazewall.portal

import io.mazewall.platform.lifecycle.Deadline
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Cooperative work bounds; blocking operations additionally belong to a physical capture task. */
internal class PortalMeasurementBudget(
    private val deadline: Deadline? = null,
    private val active: () -> Boolean = { true },
) {
    private var bytes = 0L
    private var entries = 0

    fun check() {
        check(active() && !Thread.currentThread().isInterrupted) { "portal generation capture cancelled" }
        check(deadline == null || deadline.remainingMillis() > 0) { "portal generation capture deadline expired" }
    }

    fun entry() {
        check()
        require(++entries <= MAX_ENTRIES) { "portal generation enumeration exceeds $MAX_ENTRIES entries" }
    }

    fun read(
        input: InputStream,
        buffer: ByteArray,
    ): Int {
        check()
        val count = input.read(buffer)
        check()
        if (count > 0) bytes += count
        require(bytes <= MAX_BYTES) { "portal generation measurement exceeds $MAX_BYTES bytes" }
        return count
    }

    fun <T> file(
        path: Path,
        action: (InputStream) -> T,
    ): T {
        check()
        // Platform capture-thread interruption closes an in-flight FileChannel read.
        return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            Channels.newInputStream(channel).use(action)
        }
    }

    companion object {
        const val MAX_ENTRIES = 16384
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_CLASSPATH_ENTRIES = 256
        const val MAX_CLASSPATH_CHARS = 1024 * 1024
        const val MAX_DEPTH = 16
    }
}
