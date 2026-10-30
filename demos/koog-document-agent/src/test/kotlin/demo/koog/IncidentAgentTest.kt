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
class IncidentAgentTest {
    @TempDir private lateinit var root: Path

    @Test fun `different real bundles return distinct agent reports backed by successful reads`() {
        val pool = execute("pool-exhaustion", "pool")
        val endpoint = execute("wrong-endpoint", "endpoint")
        assertTrue(pool.contains("Pool maximum changed from20 to2."), pool)
        assertTrue(endpoint.contains("Database port changed from5432 to6543."), endpoint)
        assertFalse(pool.contains("Database port changed from5432 to6543."))
        assertTrue(pool.contains("deployment.md:3") && endpoint.contains("application.log:2"))
    }

    @Test fun `the actual agent cannot finish with invented document evidence`() {
        val output = execute("pool-exhaustion", "invalid")
        assertTrue(output.contains("INCIDENT_INVALID_REPORT_REFUSED"), output)
        assertFalse(output.contains("# Incident analysis"), output)
    }

    private fun execute(
        fixture: String,
        mode: String,
    ): String {
        val log = root.resolve("$mode.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            requireNotNull(System.getProperty("example.boundary.classpath")),
            "demo.koog.IncidentFixtureProbe",
            Path.of("fixtures/incidents", fixture).toAbsolutePath().toString(),
            mode,
        ).redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("MODEL: explicit offline fixture") && output.contains("INCIDENT_RETIRED"), output)
            println(output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
