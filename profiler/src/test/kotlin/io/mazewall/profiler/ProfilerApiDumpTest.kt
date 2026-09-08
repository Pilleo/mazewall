package io.mazewall.profiler

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse

class ProfilerApiDumpTest {
    @Test
    fun `public profiler API does not expose handshake or FFM implementation details`() {
        val apiDump = sequenceOf(
            Path.of("api", "profiler.api"),
            Path.of("profiler", "api", "profiler.api"),
        ).firstOrNull(Files::isRegularFile)
            ?.let(Files::readString)
            ?: error("Cannot locate profiler.api")

        listOf(
            "HandshakeSession",
            "NativeIoOperations",
            "java/lang/foreign/MemorySegment",
        ).forEach { forbiddenSymbol ->
            assertFalse(apiDump.contains(forbiddenSymbol), "profiler.api exposes $forbiddenSymbol")
        }
    }
}
