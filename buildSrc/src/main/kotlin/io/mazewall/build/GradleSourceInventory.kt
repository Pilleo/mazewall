package io.mazewall.build

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Checkout-local inventory; private/generated directories are pruned before access. */
internal class GradleSourceInventory(directory: Path, environment: Map<String, String> = System.getenv()) {
    private val root = directory.toRealPath()
    private val childEnvironment = environment.toMutableMap()
    private val privateNames = setOf(".git", ".gradle", ".worktrees")
    private val aliasPrivateNames = privateNames + "build"

    private data class Directory(val path: Path, val forced: Boolean)
    private data class Resolved(val path: Path, val aliased: Boolean)
    private data class Command(val status: Int, val output: String, val error: String)

    fun scan(): List<String> {
        val paths = inventory()
        val failures = mutableListOf<String>()
        for (path in paths) {
            try {
                val source = Files.readString(resolve(path.toString()).path)
                val lines = source.lines()
                GradleSourceGuard.findings(source, path.toString().endsWith(".gradle")).forEach { line ->
                    failures.add("Eager Gradle configuration resolution: $path:$line: ${lines[line - 1].trim()}")
                }
            } catch (failure: Exception) {
                val detail = if (failure is AccessDeniedException) "Permission denied: ${failure.message}" else failure.message
                failures.add("Gradle resolution scan failed: $path: $detail")
            }
        }
        return failures
    }

    private fun inventory(): List<Path> {
        val paths = sortedSetOf<Path>(compareBy { it.toString() })
        val indexed = mutableSetOf<Path>()
        val hasGit = Files.exists(root.resolve(".git"), NOFOLLOW_LINKS)
        if (hasGit) {
            val locals = command(listOf("git", "--no-optional-locks", "rev-parse", "--local-env-vars"))
            check(locals.status == 0) { "Git local-environment inventory failed: ${locals.error}" }
            locals.output.lines().filter(String::isNotBlank).forEach(childEnvironment::remove)
            val checkout = git("rev-parse", "--show-toplevel")
            check(Path.of(checkout.trimEnd('\n')).toRealPath() == root) { "Git context is not the caller's checkout" }
            git("ls-files", "--cached", "--stage", "-z").split('\u0000').filter(String::isNotEmpty).forEach { row ->
                val filename = row.substringAfter('\t')
                val path = Path.of(filename)
                if (path.none { it.toString() in privateNames }) {
                    if (script(path)) paths.add(path)
                    if (row.startsWith("120000")) indexed.add(path)
                }
            }
        }
        val pending = ArrayDeque<Directory>()
        pending.add(Directory(root, false))
        val visited = mutableSetOf<Pair<Any, Boolean>>()
        val edges = mutableMapOf<Any, MutableSet<Any>>()
        for (path in paths + indexed) {
            val resolved = resolve(path.toString())
            if (resolved.aliased && Files.isDirectory(resolved.path, NOFOLLOW_LINKS)) pending.add(Directory(resolved.path, true))
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val identity = identity(current.path)
            if (!visited.add(identity to current.forced) || !current.forced && identity to true in visited) continue
            val successors = edges.getOrPut(identity) { mutableSetOf() }
            val candidates = Files.newDirectoryStream(current.path).use { entries ->
                entries.filter { it.fileName.toString() !in aliasPrivateNames }.sortedBy { it.toString() }
            }
            val ignored = if (hasGit && !current.forced && candidates.isNotEmpty()) {
                val selection = command(
                    listOf("git", "--no-optional-locks", "-c", "core.excludesFile=/dev/null", "check-ignore", "--no-index", "-z", "--stdin"),
                    candidates.joinToString("\u0000", postfix = "\u0000") { root.relativize(it).toString() },
                )
                check(selection.status in 0..1) { "Git ignore inventory failed: ${selection.error}" }
                selection.output.split('\u0000').filter(String::isNotEmpty).toSet()
            } else emptySet()
            for (candidate in candidates) {
                val relative = root.relativize(candidate)
                if (relative.toString() in ignored) continue
                val resolved = if (Files.isSymbolicLink(candidate)) resolve(relative.toString()) else Resolved(candidate, false)
                if (Files.isDirectory(resolved.path, NOFOLLOW_LINKS)) {
                    successors.add(identity(resolved.path))
                    pending.add(Directory(resolved.path, current.forced || resolved.aliased))
                } else if (script(relative)) paths.add(relative)
            }
        }
        val colors = mutableMapOf<Any, Int>()
        fun visit(start: Any) {
            val stack = ArrayDeque<Pair<Any, Iterator<Any>>>()
            colors[start] = 1
            stack.add(start to edges.getValue(start).iterator())
            while (stack.isNotEmpty()) {
                val (node, children) = stack.last()
                if (!children.hasNext()) {
                    colors[node] = 2
                    stack.removeLast()
                } else {
                    val child = children.next()
                    check(colors[child] != 1) { "Unsupported source symlink: directory cycle" }
                    if (colors[child] == null) {
                        colors[child] = 1
                        stack.add(child to edges.getOrDefault(child, emptySet()).iterator())
                    }
                }
            }
        }
        edges.keys.filter { colors[it] == null }.forEach { if (colors[it] == null) visit(it) }
        return paths.toList()
    }

    private fun resolve(requested: String): Resolved {
        val links = mutableSetOf<Any>()
        var cursor = root
        fun parts(raw: String): List<String> {
            val components = raw.split('/').filter(String::isNotEmpty)
            val selected = if (raw.startsWith('/')) {
                val anchor = root.toString().split('/').filter(String::isNotEmpty)
                require(components.take(anchor.size) == anchor) { "alias leaves checkout" }
                components.drop(anchor.size)
            } else components
            return selected + if (raw.endsWith('/')) listOf(".") else emptyList()
        }
        val pending = ArrayDeque(parts(requested))
        try {
            while (pending.isNotEmpty()) {
                val part = pending.removeFirst()
                if (part == ".") continue
                if (part == "..") {
                    require(cursor != root) { "alias leaves checkout" }
                    cursor = cursor.parent
                    continue
                }
                require(links.isEmpty() || part !in aliasPrivateNames) { "alias crosses generated/private boundary" }
                val candidate = cursor.resolve(part)
                val attributes = Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isSymbolicLink) {
                    require(links.isNotEmpty() || root.relativize(cursor).none { it.toString() in aliasPrivateNames }) { "alias crosses generated/private boundary" }
                    require(links.size < 40 && links.add(identity(candidate))) { "alias cycle or 40-link resolution bound" }
                    // JDK Path removes a trailing slash; readlink preserves the raw target's directory requirement.
                    val target = command(listOf("readlink", "--", candidate.toString()))
                    check(target.status == 0) { "Cannot read source symlink: ${target.error}" }
                    val raw = target.output.removeSuffix("\n")
                    val expanded = parts(raw)
                    if (raw.startsWith('/')) cursor = root
                    expanded.asReversed().forEach(pending::addFirst)
                    continue
                }
                cursor = candidate
                require(pending.isEmpty() || attributes.isDirectory) { "Not a directory: $cursor" }
                if (links.isNotEmpty()) {
                    require(attributes.isRegularFile || attributes.isDirectory) { "unsupported target type" }
                    if (!Files.isReadable(cursor) || attributes.isDirectory && !Files.isExecutable(cursor)) throw AccessDeniedException(cursor.toString())
                }
            }
            return Resolved(cursor, links.isNotEmpty())
        } catch (failure: Exception) {
            if (links.isNotEmpty()) {
                val reason = if (failure is AccessDeniedException) "Permission denied: ${failure.message}" else failure.message
                throw IllegalArgumentException("Unsupported source symlink: $requested: $reason", failure)
            }
            throw failure
        }
    }

    private fun identity(path: Path): Any = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        ?: throw IllegalArgumentException("Missing physical filesystem identity: $path")

    private fun script(path: Path): Boolean = path.toString().endsWith(".gradle") || path.toString().endsWith(".gradle.kts")

    private fun git(vararg args: String): String {
        val result = command(listOf("git", "--no-optional-locks") + args)
        check(result.status == 0) { "Git inventory failed: ${result.error}" }
        return result.output
    }

    private fun command(arguments: List<String>, input: String = ""): Command {
        val process = ProcessBuilder(arguments).directory(root.toFile()).apply {
            environment().clear()
            environment().putAll(childEnvironment)
        }.start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readAllBytes().decodeToString() }
        val error = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readAllBytes().decodeToString() }
        try {
            process.outputStream.use { it.write(input.toByteArray()) }
            check(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) { "Source inventory command timed out: ${arguments.first()}" }
            return Command(process.exitValue(), output.get(), error.get())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Source inventory child did not retire" }
            }
        }
    }
}
