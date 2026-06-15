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
     */
    fun parseToPolicy(
        path: Path,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        return parseJsonToPolicy(Files.readString(path), base)
    }

    /**
     * Parses a Bill of Behavior from a JSON input stream and applies it to a base policy.
     */
    fun parseToPolicy(
        stream: InputStream,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return parseJsonToPolicy(content, base)
    }

    private val jsonDecoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Parses an SBoB JSON string and generates a [Policy].
     */
    fun parseJsonToPolicy(
        json: String,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
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

        val prunedReads = pruneSubpaths(opens)
        val prunedWrites = pruneSubpaths(fsWritePaths)

        for (path in prunedReads) builder.allowFsRead(SandboxedPath.of(path, allowNonExistent = true))
        for (path in prunedWrites) builder.allowFsWrite(SandboxedPath.of(path, allowNonExistent = true))

        return builder.build()
    }

    private fun pruneSubpaths(paths: Set<String>): Set<String> {
        if (paths.size <= 1) return paths

        val sortedPaths = paths.map { Paths.get(it).toAbsolutePath().normalize() }.sorted()
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

