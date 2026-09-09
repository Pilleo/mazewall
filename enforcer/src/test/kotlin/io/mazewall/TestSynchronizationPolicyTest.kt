package io.mazewall

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TestSynchronizationPolicyTest {
    @Test
    fun `test synchronization has observable barriers instead of fixed sleeps`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("enforcer/src")) }
        val roots = listOf("enforcer", "platform", "profiler", "portal", "portal-worker", "portal-codegen")
            .flatMap { module -> listOf("$module/src/test", "$module/src/integrationTest") } +
            "src/sharedTest"
        val violations = roots.flatMap { relative ->
            val directory = root.resolve(relative)
            if (!Files.exists(directory)) {
                emptyList()
            } else {
                Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList().flatMap { file ->
                    Files.readAllLines(file).mapIndexedNotNull { index, line ->
                        if (line.contains("Thread" + ".sleep(") && !line.contains("// Workload delay:")) {
                            "${root.relativize(file)}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
            }
            }
        }
        assertTrue(violations.isEmpty(), "Fixed synchronization sleeps need observable barriers:\n${violations.joinToString("\n")}")
    }
}
