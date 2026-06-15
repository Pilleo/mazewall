package io.mazewall

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A lightweight parser for Software Bill of Behavior (SBoB) JSON files.
 * This class is designed to be used in production environments to load
 * profiling results without requiring the heavy `mazewall:profiler` module.
 */
object SbobParser {
    /**
     * Parses a Bill of Behavior from a Path pointing to an SBoB JSON file
     * and applies it to a base policy.
     * 
     * @param baseCwd Optional base directory to resolve any relative paths against.
     * If null, relative paths in the SBoB will trigger an IllegalArgumentException.
     */
    fun parseToPolicy(
        path: Path,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        return parseJsonToPolicy(Files.readString(path), base, baseCwd)
    }

    /**
     * Parses a Bill of Behavior from a JSON input stream and applies it to a base policy.
     * 
     * @param baseCwd Optional base directory to resolve any relative paths against.
     * If null, relative paths in the SBoB will trigger an IllegalArgumentException.
     */
    fun parseToPolicy(
        stream: InputStream,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return parseJsonToPolicy(content, base, baseCwd)
    }

    private val jsonDecoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Parses an SBoB JSON string and generates a [Policy].
     * 
     * @param baseCwd Optional base directory to resolve any relative paths against.
     * If null, relative paths in the SBoB will trigger an IllegalArgumentException.
     */
    fun parseJsonToPolicy(
        json: String,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val dto = jsonDecoder.decodeFromString<BillOfBehaviorDto>(json)
        val opens = dto.opens
        val fsWritePaths = dto.fsWritePaths
        val syscallNames = dto.syscalls

        val mappedSyscalls =
            syscallNames
                .mapNotNull { name ->
                    try {
                        Syscall.valueOf(name.uppercase())
                    } catch (ignored: IllegalArgumentException) {
                        null
                    }
                }.toSet()

        // SBoB parsing may result in Landlock rules, so we transition to ThreadLocalOnly
        @Suppress("UNCHECKED_CAST")
        val builder = Policy.threadLocalBuilder().base(base as Policy<PolicyScope.ThreadLocalOnly, *>)

        if (base.defaultAction == SeccompAction.ACT_ALLOW) {
            val toUnblock =
                mappedSyscalls.filter { base.syscallActions.containsKey(it) }
            builder.unblock(*toUnblock.toTypedArray())
        } else {
            builder.allow(*mappedSyscalls.toTypedArray())
        }

        val prunedReads = pruneSubpaths(opens, baseCwd)
        val prunedWrites = pruneSubpaths(fsWritePaths, baseCwd)

        for (path in prunedReads) builder.allowFsRead(SandboxedPath.of(path, allowNonExistent = true))
        for (path in prunedWrites) builder.allowFsWrite(SandboxedPath.of(path, allowNonExistent = true))

        return builder.build()
    }

    private fun pruneSubpaths(paths: Set<String>, baseCwd: Path?): Set<String> {
        if (paths.isEmpty()) return paths

        val sortedPaths = paths.map { pathStr ->
            val p = Paths.get(pathStr)
            if (!p.isAbsolute) {
                if (baseCwd == null) {
                    throw IllegalArgumentException("SBoB contains relative path '$pathStr' but no baseCwd was provided.")
                }
                baseCwd.resolve(p).normalize()
            } else {
                p.normalize()
            }
        }.sorted()

        val result = mutableListOf<Path>()
        var currentParent: Path? = null

        for (path in sortedPaths) {
            if (currentParent == null || !path.startsWith(currentParent)) {
                result.add(path)
                currentParent = path
            }
        }
        return result.map { it.toString() }.toSet()
    }
}

