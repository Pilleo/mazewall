package demo.koog

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("native")
class DocumentAgentNativeTest {
    @TempDir lateinit var root: Path

    @Test fun `actual agent preserves denial recovery and physical worker retirement`() {
        verifyProgram(
            "demo.koog.DocumentAgent",
            "example.classpath",
            listOf("MODEL: scripted (offline)", "ALLOWED:", "DENIED:", "RECOVERED:", "RETIRED:"),
        )
    }

    @Test fun `generated outline service stays opaque and its actual child retires`() {
        verifyProgram("demo.koog.PortalDocumentAgent", "example.classpath", listOf("OUTLINE:", "BOUNDARY:", "RETIRED:"))
    }

    @Test fun `separate test only fault worker cannot poison the ordinary outline service`() {
        verifyProgram(
            "demo.koog.PortalBoundaryProbe",
            "example.boundary.classpath",
            listOf("CONTROL:", "OUTLINE:", "DENIED:", "RECOVERED:", "SURVIVED:", "RETIRED:"),
        )
    }

    private fun verifyProgram(
        mainClass: String,
        classpathProperty: String,
        markers: List<String>,
    ) {
        val classpath = assertNotNull(System.getProperty(classpathProperty))
        val log = root.resolve(mainClass.substringAfterLast('.') + ".log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            mainClass,
        ).redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owned application JVM exceeded its deadline")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            for (marker in markers) {
                assertEquals(1, output.lineSequence().count { it.startsWith(marker) }, output)
            }
            assertFalse(output.contains("DUMMY_PRIVATE_CANARY"))
            println(output)
        } finally {
            if (process.isAlive) process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned application JVM did not retire")
        }
    }
}
