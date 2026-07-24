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
 *
 * ### ⚠️ Security Warning: Atomic Directory Symlink Swapping Incompatibility
 * Landlock sandboxing rules resolved by [SbobParser] (and applied using Landlock rulesets)
 * are inherently **incompatible with atomic directory symlink swapping** (e.g., Capistrano-style
 * deployment workflows where `/app/current` is a symlink pointing to `/app/v1` and then updated
 * atomically to point to `/app/v2`).
 *
 * This incompatibility arises because:
 * 1. During SBoB parsing, paths are resolved to their absolute physical locations on disk
 *    (e.g., `/app/v1/file`) using physical path resolution (such as `toRealPath()`) to prevent
 *    TOCTOU bypasses via path-traversal.
 * 2. When Landlock rules are registered (at `addRule` time), Landlock strictly binds the permission
 *    rules directly to the underlying filesystem directory inode via its absolute physical path.
 * 3. If a sibling thread or background process subsequently updates the symlink to point to `/app/v2`,
 *    the previously applied Landlock rule remains bound to the `/app/v1` inode, and the application
 *    will be blocked with access denied (`EACCES`/`EPERM`) when trying to access `/app/v2/file`
 *    at runtime because the new target path is not allowed in the policy.
 *
 * **Recommendation for deployment environments with dynamic directory swaps:**
 * Instead of profiling or restricting the specific dynamic targets (e.g., `/app/current` or `/app/v1`),
 * users must **profile and restrict the parent umbrella directory** (e.g., `/app/`) under which both the
 * symlink and its versioned targets reside. This ensures Landlock successfully allows traversal and
 * read/write operations under any valid resolved symlink target located within the parent umbrella directory.
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
