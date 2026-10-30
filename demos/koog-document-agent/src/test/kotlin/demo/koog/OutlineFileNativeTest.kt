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
class OutlineFileNativeTest {
    @TempDir private lateinit var root: Path

    @Test fun `two actual files produce different process backed outlines and captured children retire`() {
        val first = execute("first.md", "# First é🙂\n```\n# not a heading\n```\n## Next")
        val second = execute("second.md", "# Different\n### Actual input")
        assertEquals(0, first.first, first.second)
        assertEquals(0, second.first, second.second)
        assertTrue(first.second.contains("# Document outline\nFirst é🙂\nNext"), first.second)
        assertFalse(first.second.contains("not a heading"))
        assertTrue(second.second.contains("# Document outline\nDifferent\nActual input"), second.second)
        for (output in listOf(first.second, second.second)) {
            assertTrue(output.contains("OUTLINE_FILE_BOUNDARY") && output.contains("OUTLINE_FILE_RETIRED"), output)
        }
    }

    @Test fun `oversized host input fails before an outline worker is prepared`() {
        val result = execute("large.md", "x".repeat(65_537))
        assertTrue(result.first != 0, result.second)
        assertFalse(result.second.contains("OUTLINE_FILE_BOUNDARY") || result.second.contains("# Document outline"), result.second)
    }

    @Test fun `an untrusted heading cannot inject terminal control into the displayed artifact`() {
        val result = execute("control.md", "# heading\u001b[2Jhidden")
        assertTrue(result.first != 0, result.second)
        assertFalse(result.second.contains('\u001b') || result.second.contains("# Document outline"), result.second)
    }

    private fun execute(
        name: String,
        content: String,
    ): Pair<Int, String> {
        val input = Files.writeString(root.resolve(name), content)
        val log = root.resolve("$name.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            requireNotNull(System.getProperty("example.classpath")),
            "demo.koog.OutlineFileApplication",
            input.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            val output = Files.readString(log)
            println(output)
            return process.exitValue() to output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
