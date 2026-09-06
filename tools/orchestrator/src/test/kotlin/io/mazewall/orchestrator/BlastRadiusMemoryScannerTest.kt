package io.mazewall.orchestrator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlastRadiusMemoryScannerTest {

    @Test
    fun `extractImpactSymbols parses Codanna impact JSON output`() {
        val sampleJson = """
        {
          "data": [
            {
              "id": 1,
              "name": "connectWithRetry",
              "kind": "Method",
              "file_path": "./platform/src/main/kotlin/io/mazewall/core/SocketManager.kt"
            },
            {
              "id": 2,
              "name": "SupervisorSocketInputStream",
              "kind": "Class",
              "file_path": "./enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketInputStream.kt"
            },
            {
              "id": 3,
              "name": "testSupervisorInterruption",
              "kind": "Method",
              "file_path": "./enforcer/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketInputStreamTest.kt"
            }
          ]
        }
        """.trimIndent()

        val symbols = BlastRadiusMemoryScanner.extractImpactSymbols("connectWithRetry") { sampleJson }
        assertEquals(listOf("connectWithRetry", "SupervisorSocketInputStream", "testSupervisorInterruption"), symbols)
    }

    @Test
    fun `queryAgentMemory finds matching memories by symbol and deduplicates`(@TempDir tempDir: File) {
        val memoryFile = File(tempDir, "standalone.json").apply {
            writeText("""
            {
              "mem:memories": {
                "mem_1": {
                  "id": "mem_1",
                  "type": "anti-pattern",
                  "title": "SupervisorSocketInputStream tight loop spinning on EINTR",
                  "content": "SupervisorSocketInputStream must use progressive backoff on EINTR to prevent 100% CPU."
                },
                "mem_2": {
                  "id": "mem_2",
                  "type": "fact",
                  "title": "General JVM FFM note",
                  "content": "Unrelated fact about MemorySegment downcalls."
                },
                "mem_3": {
                  "id": "mem_3",
                  "type": "anti-pattern",
                  "title": "connectWithRetry CLOEXEC invariant",
                  "content": "Always pass SOCK_CLOEXEC when opening supervisor sockets."
                }
              }
            }
            """.trimIndent())
        }

        val symbols = listOf("connectWithRetry", "SupervisorSocketInputStream")
        val hits = BlastRadiusMemoryScanner.queryAgentMemory(symbols, memoryFile)

        assertEquals(2, hits.size)
        assertTrue(hits.any { it.id == "mem_1" && it.matchedSymbol == "SupervisorSocketInputStream" })
        assertTrue(hits.any { it.id == "mem_3" && it.matchedSymbol == "connectWithRetry" })
    }

    @Test
    fun `formatMarkdownReport produces clean markdown summary`() {
        val report = BlastRadiusReport(
            rootSymbol = "connectWithRetry",
            impactedSymbols = listOf("SupervisorSocketInputStream", "ProfilerInstaller"),
            memories = listOf(
                MemoryHit(
                    id = "mem_1",
                    type = "anti-pattern",
                    title = "EINTR CPU Burn",
                    content = "Supervisor socket reader spins on EINTR without backoff.",
                    matchedSymbol = "SupervisorSocketInputStream",
                ),
            ),
        )

        val markdown = BlastRadiusMemoryScanner.formatMarkdownReport(report)
        assertTrue(markdown.contains("Blast Radius Impact Memory: `connectWithRetry`"))
        assertTrue(markdown.contains("SupervisorSocketInputStream"))
        assertTrue(markdown.contains("EINTR CPU Burn"))
    }
}
