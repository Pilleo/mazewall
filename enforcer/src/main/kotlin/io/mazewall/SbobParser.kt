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
 * This class is designed to be used in production environments to load
 * profiling results without requiring the heavy `mazewall:profiler` module.
 *
 * It delegates core responsibilities to specialized internal components:
 * - [BobDeserializer] for JSON decoding.
 * - [PathNormalizer] for path canonicalization and subpath pruning.
 * - [PolicyTransformer] for generating the final [Policy].
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
        // 1. DESERIALIZE: Convert JSON to DTO
        val dto = BobDeserializer.deserialize(json)

        // 2. NORMALIZE & PRUNE: Handle filesystem paths
        val prunedReads = PathNormalizer.normalizeAndPrune(dto.opens, baseCwd)
        val prunedWrites = PathNormalizer.normalizeAndPrune(dto.fsWritePaths, baseCwd)

        // 3. TRANSFORM: Convert DTO data into a Policy
        return PolicyTransformer.transform(dto, prunedReads, prunedWrites, base)
    }
}
