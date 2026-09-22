package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Benchmark-only exact operation evidence, never policy input or a normal terminal field. */
internal data class TierESplitOperationManifest(
    val registrationId: String,
    val sessionEpoch: Long,
    val emissionMode: String,
    val exactDirectoryFds: List<Long>,
    val unresolvedObservations: Int,
) {
    init {
        require(registrationId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")))
        require(sessionEpoch >= 0)
        require(exactDirectoryFds.size <= MAX_OPERATION_ROWS) { "benchmark operation manifest exceeds row budget" }
        require(unresolvedObservations >= 0)
    }

    fun toJson(): String =
        "{\"schema_version\":1,\"registration_id\":\"$registrationId\",\"session_epoch\":$sessionEpoch," +
            "\"emission_mode\":\"$emissionMode\",\"representative_only\":${emissionMode != "FULL_STREAM"}," +
            "\"exact_directory_fds\":[${exactDirectoryFds.joinToString(",")}]," +
            "\"unresolved_observations\":$unresolvedObservations,\"policy_eligibility\":false}"

    companion object {
        private const val MAX_OPERATION_ROWS = 100_000

        fun from(
            result: TierECollectorRuntimeResult,
            target: TierERegisteredTarget,
        ): TierESplitOperationManifest {
            val exact = result.profile.syscalls
                .asSequence()
                .filter { it.resolutionStatus == ResolutionStatus.RESOLVED }
                .filter { it.observation.syscallNumber == 257 }
                .filter { it.stackDefinition?.captureQuality == StackCaptureQuality.COMPLETE }
                .filter { syscall ->
                    syscall.stackDefinition?.frames?.any { frame ->
                        frame.className.contains("TierESharedOpenatOracleWorkload$") &&
                            frame.methodName == "runRange"
                    } == true
                }.map { it.observation.args[0] }
                .take(MAX_OPERATION_ROWS + 1)
                .toList()
            return TierESplitOperationManifest(
                target.registrationId,
                target.sessionEpoch.value,
                result.profile.integrity.emissionMode.name,
                exact,
                result.profile.syscalls.count { it.resolutionStatus != ResolutionStatus.RESOLVED },
            )
        }
    }
}

internal class TierESplitOperationManifestFileSink(
    root: Path,
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(this.root))
    }

    fun accept(manifest: TierESplitOperationManifest) {
        val destination = root.resolve("${manifest.registrationId}-${manifest.sessionEpoch}.tier-e-operations.json").normalize()
        require(destination.parent == root)
        val temporary = Files.createTempFile(root, ".tier-e-operations-", ".tmp")
        try {
            Files.writeString(temporary, manifest.toJson())
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
