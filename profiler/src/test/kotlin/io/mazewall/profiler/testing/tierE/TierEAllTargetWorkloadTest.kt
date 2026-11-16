package io.mazewall.profiler.testing.tierE

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEAllTargetWorkloadTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun completionRequiresBothWorkersToSucceed(failWorkers: Boolean) {
        val source = directory.resolve("TierEAllTargetWorkload.java")
        requireNotNull(javaClass.getResourceAsStream("/tierE/TierEAllTargetWorkload.java")).use { Files.copy(it, source) }
        val compilerOutput = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, compilerOutput, compilerOutput, "-d", directory.toString(), source.toString()), compilerOutput.toString())
        val first = Files.createFile(directory.resolve("first"))
        val second = directory.resolve("second")
        val phase = directory.resolve("phase")
        val input = directory.resolve("input")
        val output = directory.resolve("completed")
        val log = directory.resolve("child.log")
        if (!failWorkers) Files.writeString(input, "input")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            directory.toString(),
            "TierEAllTargetWorkload",
            first.toString(),
            second.toString(),
            phase.toString(),
            input.toString(),
            output.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        IsolatedProcessTester.awaitOwnedProcess(
            process,
            Duration.ofSeconds(10),
            beforeWait = {
                awaitCondition(description = "worker failure or attributed phase completion") { Files.exists(phase) || !process.isAlive }
                // Let the old false-success path finish: only the workers saw a missing input.
                if (Files.exists(phase)) {
                    Files.writeString(input, "input")
                    Files.createFile(second)
                }
            },
            waitCompleted = { finished -> assertTrue(finished, "workload did not terminate") },
        )
        val diagnostic = Files.readString(log)
        if (failWorkers) {
            assertNotEquals(0, process.exitValue(), diagnostic)
            assertFalse(Files.exists(phase), diagnostic)
            assertFalse(Files.exists(output), diagnostic)
            assertEquals(2, Regex("Suppressed: java.lang.RuntimeException").findAll(diagnostic).count(), diagnostic)
            assertTrue(diagnostic.contains("NoSuchFileException"), diagnostic)
        } else {
            assertEquals(0, process.exitValue(), diagnostic)
            assertEquals("attributed-complete", Files.readString(phase))
            assertEquals("completed", Files.readString(output))
        }
    }
}
