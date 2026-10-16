package io.mazewall.portal

import java.nio.file.Path

/** Immutable worker-bundle input, addressed by a SHA-256 digest. */
public data class PortalWorkerArtifact(
    val path: Path,
    val sha256: String,
) {
    init {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "worker artifact SHA-256 must be 64 hexadecimal characters" }
    }
}

/** Verified, extracted worker input; its [classpath] is the sole child-JVM classpath source. */
public data class MaterializedWorkerBundle(
    val directory: Path,
    val classpath: List<Path>,
) {
    init {
        require(classpath.isNotEmpty()) { "materialized worker bundle classpath must not be empty" }
    }
}
