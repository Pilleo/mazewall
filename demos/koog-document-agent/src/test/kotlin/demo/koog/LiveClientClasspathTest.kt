package demo.koog

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveClientClasspathTest {
    @TempDir private lateinit var root: Path

    @Test fun `the real HTTP client cancels at the agent deadline before the stalled server is released`() {
        executeProbe("demo.koog.LiveClientDeadlineProbe", "LIVE_HTTP_CANCELLED before server release")
    }

    @Test fun `both real provider clients construct on the packaged runtime without making a request`() {
        executeProbe("demo.koog.LiveClientConstructionProbe", "LIVE_CLIENTS_CONSTRUCTED no requests or inference")
    }

    private fun executeProbe(
        entry: String,
        marker: String,
    ) {
        val log = root.resolve("clients.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-cp",
            requireNotNull(System.getProperty("example.boundary.classpath")),
            entry,
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains(marker), output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}

internal object LiveClientConstructionProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        OpenAILLMClient("synthetic-key-never-sent").use { }
        OllamaClient(baseUrl = "http://127.0.0.1:11434").use { }
        println("LIVE_CLIENTS_CONSTRUCTED no requests or inference")
    }
}
