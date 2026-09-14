package io.mazewall.profiler.api
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse

class ProfilerApiDumpTest {
    @Test
    fun `public profiler API exposes only the supported application facade`() {
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
            "io/mazewall/profiler/attribution/",
            "io/mazewall/profiler/ipc/",
            "InvocationReplayMachine",
            "TierERingBufferCollector",
            "io/mazewall/profiler/engine/",
            "io/mazewall/profiler/collector/",
            "io/mazewall/profiler/compiler/",
            "io/mazewall/profiler/iterative/",
            "io/mazewall/profiler/strace/",
            "io/mazewall/profiler/triage/",
        ).forEach { forbiddenSymbol ->
            assertFalse(apiDump.contains(forbiddenSymbol), "profiler.api exposes $forbiddenSymbol")
        }

        assertFalse(
            apiDump
                .lineSequence()
                .any { it.contains("io/mazewall/profiler/tierE/") && !it.contains("TierEKotlinDaemonMain") },
            "profiler.api exposes a Tier E library type",
        )
    }
}
