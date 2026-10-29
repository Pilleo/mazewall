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
        val classpath = assertNotNull(System.getProperty("example.classpath"))
        val log = root.resolve("document-agent.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "demo.koog.DocumentAgent")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owned document-agent JVM exceeded its deadline")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            for (marker in listOf("MODEL: scripted (offline)", "ALLOWED:", "DENIED:", "RECOVERED:", "RETIRED:")) {
                assertEquals(1, output.lineSequence().count { it.startsWith(marker) }, output)
            }
            assertFalse(output.contains("DUMMY_PRIVATE_CANARY"), "Private contents must not appear in demonstration output")
            println(output)
        } finally {
            if (process.isAlive) process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned document-agent JVM did not retire")
        }
    }
}
