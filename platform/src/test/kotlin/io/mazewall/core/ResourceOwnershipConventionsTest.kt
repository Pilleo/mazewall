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

    @Test
    fun `production confined arenas are NativeArena scoped with use`() {
        val violations = scanMainSources { path, source ->
            val text = path.toString()
            if (text.endsWith("NativeArena.kt")) return@scanMainSources emptyList()
            if (text.endsWith("TierEbpfEngine.kt")) return@scanMainSources emptyList()
            ARENA_OF_CONFINED
                .findAll(source)
                .map { match ->
                val line = source.take(match.range.first).count { it == '\n' } + 1
                "$path:$line"
            }.toList() +
                OF_CONFINED_WITHOUT_USE
                    .findAll(source)
                    .map { match ->
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    "$path:$line"
                }.toList()
        }
        assertTrue(
            violations.isEmpty(),
            "Use NativeArena.ofConfined().use (not a raw Arena.ofConfined or an unscoped ofConfined):\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `production getFdOrThrow transfers ownership`() {
        val violations = scanMainSources { path, source ->
            GET_FD_OR_THROW
                .findAll(source)
                .mapNotNull { match ->
                val lineStart = source.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                val lineEnd = source.indexOf('\n', match.range.last).let { if (it < 0) source.length else it }
                val window = source.substring(lineStart, minOf(source.length, lineEnd + 400))
                if (OWNED_FD_TRANSFER.containsMatchIn(window)) {
                    null
                } else {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    "$path:$line"
                }
            }.toList()
        }
        assertTrue(
            violations.isEmpty(),
            "getFdOrThrow must return, .use, or assign into an owner that closes:\n" +
                violations.joinToString("\n"),
        )
    }

    private fun scanMainSources(inspect: (Path, String) -> List<String>): List<String> {
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
                            "/.worktrees/" !in text
                    }.forEach { path ->
                        violations += inspect(path, Files.readString(path))
                    }
            }
        }
        return violations
    }

    private companion object {
        val DESCENDANTS_WITHOUT_USE = Regex("""\.descendants\s*\(\s*\)\s*(?!\s*\.use)""")
        val ARENA_OF_CONFINED = Regex("""(?<!Native)Arena\.ofConfined\s*\(""")
        val OF_CONFINED_WITHOUT_USE = Regex("""\.ofConfined\s*\(\s*\)\s*(?!\s*\.use)""")
        val GET_FD_OR_THROW = Regex("""getFdOrThrow\s*\(""")
        val OWNED_FD_TRANSFER = Regex("""return |\.use\b|owned\s*=|Opened\(|listenerFdVal\.set|Capability\.ReadFd|clientFd\s*=""")
    }
}
