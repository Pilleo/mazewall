package demo.koog

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("native")
class IncidentBoundaryTest {
    @TempDir private lateinit var root: Path

    @Test fun `same reader action opens ordinary canary but OS refuses protected open and real work completes`() {
        val log = root.resolve("incident-boundary.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            requireNotNull(System.getProperty("example.boundary.classpath")),
            "demo.koog.IncidentBoundaryProbe",
            Path.of("fixtures/incidents/pool-exhaustion").toAbsolutePath().toString(),
        ).redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            for (marker in listOf("CONTROL_OUTSIDE_OPEN", "BOUNDARY_REFUSED", "INCIDENT_RETIRED")) assertEquals(1, output.lineSequence().count { it.startsWith(marker) }, output)
            assertTrue(output.contains("# Incident analysis") && output.contains("application.log:2"), output)
            assertFalse(output.contains("PRIVATE_CANARY_MUST_NOT_BE_A_MODEL_RESULT"), output)
            println(output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
