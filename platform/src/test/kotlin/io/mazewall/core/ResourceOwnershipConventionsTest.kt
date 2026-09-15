package io.mazewall.platform.lifecycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ResourceOwnershipConventionsTest {
    @Test
    fun `production descendants snapshots go through ProcessTree`() {
        val root = Path.of("").toAbsolutePath()
        val modules = listOf("platform", "enforcer", "profiler", "portals", "demos")
        val violations = mutableListOf<String>()
        modules.forEach { module ->
            val moduleRoot = root.resolve(module)
            if (!Files.isDirectory(moduleRoot)) return@forEach
            Files.walk(moduleRoot).use { paths ->
                paths
                    .filter { path ->
                        val text = path.toString()
                        text.contains("/src/main/") &&
                            text.endsWith(".kt") &&
                            "/build/" !in text &&
                            "/.worktrees/" !in text &&
                            !text.endsWith("ProcessTree.kt")
                    }.forEach { path ->
                        val source = Files.readString(path)
                        DESCENDANTS_WITHOUT_USE.findAll(source).forEach { match ->
                            val line = source.take(match.range.first).count { it == '\n' } + 1
                            violations += "$path:$line"
                        }
                    }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Call Process.destroyForciblyWithDescendants or ProcessHandle.snapshotDescendants instead of an unowned descendants() stream:\n" +
                violations.joinToString("\n"),
        )
    }

    private companion object {
        val DESCENDANTS_WITHOUT_USE = Regex("""\.descendants\s*\(\s*\)\s*(?!\s*\.use)""")
    }
}
