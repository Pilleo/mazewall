package io.mazewall.enforcer.diagnostics

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.RuntimeProfile
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class ProbeRun(
    val completed: Boolean,
    val exitCode: Int?,
    val journal: Path,
    val log: Path,
    val fatalReports: List<Path>,
)

class NativeCompletionProbeTest : BaseIntegrationTest() {
    private val directory =
        Path
            .of("build/reports/native-completion", UUID.randomUUID().toString())
            .toAbsolutePath()
            .also { Files.createDirectories(it) }

    @Test
    @EnabledIfLinuxAndSupported
    fun `JVM compatible completion and strict denial are recorded in owned children`() {
        val hot = runProbe(RuntimeProfile.HOTSPOT_JIT, directory.resolve("hot"))
        assertTrue(hot.completed)
        assertEquals(0, hot.exitCode)
        assertTrue(Files.readString(hot.journal).contains("phase=WORKER_RETIRED"))
        val strict = runProbe(RuntimeProfile.NATIVE_IMAGE, directory.resolve("strict"))
        assertTrue(Files.readString(strict.journal).contains("profile=NATIVE_IMAGE"))
        assertTrue(strict.exitCode != null)
        if (!strict.completed) assertTrue(strict.fatalReports.isNotEmpty() || Files.size(strict.log) > 0)
    }

    private fun runProbe(
        profile: RuntimeProfile,
        output: Path,
    ): ProbeRun {
        Files.createDirectory(output)
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Xms128m",
                "-Xmx256m",
                "-XX:ErrorFile=${output.resolve("hs_err_pid%p.log")}",
                "-cp",
                System.getProperty("java.class.path"),
                NativeCompletionProbe::class.java.name,
                profile.name,
                output.toString(),
            )
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.resolve("child.log").toFile())
                .start()
        var finished = false
        var code: Int? = null
        IsolatedProcessTester.awaitOwnedProcess(process, Duration.ofSeconds(30), {}) { done ->
            finished = done
            if (done) code = process.exitValue()
        }
        val fatal =
            Files.list(output).use { stream ->
                stream.filter { it.fileName.toString().startsWith("hs_err_pid") }.toList()
            }
        check(finished) { "Owned diagnostic timed out; retain log/owner failure, not a native-crash classification" }
        Files.writeString(
            output.resolve("outcome.json"),
            """{"profile":"${profile.name}","exit_code":$code,"completed":${code == 0}}""" + "\n",
        )
        check(Files.exists(output.resolve("journal.txt")) || fatal.isNotEmpty()) {
            "Diagnostic exited without a journal or fatal report; retain $output"
        }
        return ProbeRun(code == 0, code, output.resolve("journal.txt"), output.resolve("child.log"), fatal)
    }
}
