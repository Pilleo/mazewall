package io.mazewall.portal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Explicit, bounded copy of a caller-owned input stream for worker-local reconstruction. */
public class PortalInputSnapshot private constructor(
    private val contents: ByteArray?,
    private val spill: Path?,
    public val size: Int,
) : AutoCloseable {
    /** Returns a fresh worker-local view; this never exposes or closes the caller stream. */
    public fun openStream(): InputStream = contents?.inputStream() ?: Files.newInputStream(requireNotNull(spill))

    public fun bytes(): ByteArray = openStream().use(InputStream::readBytes)

    override fun close() {
        spill?.let(Files::deleteIfExists)
    }

    public data class Options(
        val maximumBytes: Int,
        val inMemoryThreshold: Int = maximumBytes,
        val spillDirectory: Path? = null,
    ) {
        init {
            require(maximumBytes >= 0) { "portal input snapshot maximumBytes must not be negative" }
            require(inMemoryThreshold in 0..maximumBytes) {
                "portal input snapshot inMemoryThreshold must be between zero and maximumBytes"
            }
        }
    }

    public companion object {
        @JvmStatic
        public fun capture(
            source: InputStream,
            maximumBytes: Int,
        ): PortalInputSnapshot = capture(source, Options(maximumBytes))

        @JvmStatic
        @Suppress("TooGenericExceptionCaught") // Capture must delete an owned spill for every terminal failure.
        public fun capture(
            source: InputStream,
            options: Options,
        ): PortalInputSnapshot {
            val memory = java.io.ByteArrayOutputStream(minOf(options.inMemoryThreshold, 8_192))
            var output: java.io.OutputStream? = null
            var spill: Path? = null
            var size = 0
            try {
            val buffer = ByteArray(8_192)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(size <= options.maximumBytes - read) {
                    "portal input snapshot exceeds configured limit of ${options.maximumBytes} bytes"
                }
                if (output == null && options.spillDirectory != null && size + read > options.inMemoryThreshold) {
                    Files.createDirectories(options.spillDirectory)
                    spill = Files.createTempFile(options.spillDirectory, "portal-input-", ".spill")
                    output = Files.newOutputStream(spill)
                    output.write(memory.toByteArray())
                }
                (output ?: memory).write(buffer, 0, read)
                size += read
            }
            output?.close()
            return if (spill == null) PortalInputSnapshot(memory.toByteArray(), null, size) else PortalInputSnapshot(null, spill, size)
            } catch (failure: Throwable) {
                runCatching { output?.close() }
                spill?.let { runCatching { Files.deleteIfExists(it) } }
                throw failure
            }
        }
    }
}
