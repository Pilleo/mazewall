package io.mazewall

import io.mazewall.sbob.BobDeserializer
import io.mazewall.sbob.PathNormalizer
import io.mazewall.sbob.PolicyTransformer
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A lightweight parser for Software Bill of Behavior (SBoB) JSON files.
 *
 * NOTE ON MULTI-THREADED I/O & TOCTOU: SBoB parsing performing static pruning and
 * path resolution assumes a stable filesystem layout during execution. If symlinks or
 * directories within pruned paths (e.g., /opt/app vs /opt/app/config) are modified
 * concurrently by another process or thread during parsing, static string-based/path-based
 * pruning might yield incorrect target paths.
 * Once the compiled Landlock policy is applied, however, Landlock's enforcement is
 * completely secure and immune to post-normalization rename TOCTOU attacks, because rules
 * are dynamically evaluated and bound to kernel inodes (via O_PATH file descriptors)
 * rather than path strings.
 */
object SbobParser {
    /**
     * Parses a Bill of Behavior from a Path pointing to an SBoB JSON file
     * and applies it to a base policy.
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
     */
    fun parseToPolicy(
        stream: InputStream,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return parseJsonToPolicy(content, base, baseCwd)
    }

    /**
     * Parses an SBoB JSON string and generates a [Policy].
     */
    fun parseJsonToPolicy(
        json: String,
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        // 1. DESERIALIZE: Convert JSON to DTO
        val dto = BobDeserializer.deserialize(json)

        // 2. NORMALIZE & PRUNE: Handle filesystem paths
        val prunedReads = PathNormalizer.normalizeAndPrune(dto.opens, baseCwd)
        val prunedWrites = PathNormalizer.normalizeAndPrune(dto.fsWritePaths, baseCwd)

        // 3. TRANSFORM: Convert DTO data into a PolicyDefinition
        val def = PolicyTransformer.transform(dto, prunedReads, prunedWrites, base.definition)
        return Policy(def)
    }
}
