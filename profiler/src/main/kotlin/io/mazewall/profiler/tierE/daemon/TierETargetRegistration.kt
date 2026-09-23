package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.ProcessIncarnationId
import io.mazewall.profiler.internal.attribution.SessionEpoch
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Operator-owned binding between one target process and the artifacts the collector may consume.
 *
 * This is deliberately separate from control protocol v1: an application-side client cannot
 * select an arbitrary target, agent library, or dictionary artifact by sending an ATTACH line.
 */
internal data class TierERegisteredTarget(
    val registrationId: String,
    val pid: Int,
    val sessionTag: Int,
    val processIncarnation: Long,
    val markerPath: Path,
    val markerOffset: Long,
    val artifactFileName: String,
    val markerProofFileName: String? = null,
) {
    init {
        require(registrationId.matches(REGISTRATION_ID)) { "registration id is invalid" }
        require(pid > 0) { "registered pid must be positive" }
        require(sessionTag > 0) { "registered session tag must be positive" }
        require(processIncarnation > 0) { "registered process incarnation must be positive" }
        require(markerPath.isAbsolute) { "registered marker path must be absolute" }
        require(markerOffset > 0) { "registered marker offset must be positive" }
        require(artifactFileName.isSafeFileName()) { "registered artifact file name is invalid" }
        require(markerProofFileName == null || (markerProofFileName.isSafeFileName() && markerProofFileName != artifactFileName)) {
            "registered marker proof file name is invalid"
        }
    }

    val sessionEpoch: SessionEpoch get() = SessionEpoch(sessionTag.toLong())

    val processIdentity: ProcessIncarnationId get() = ProcessIncarnationId(processIncarnation)

    private companion object {
        val REGISTRATION_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
    }
}

/**
 * Target-side launch inputs for an unprivileged JVM.
 *
 * This produces native-agent configuration only. It cannot request BPF privileges, attach another
 * process, or alter the collector-owned registration boundary.
 */
internal data class TierETargetLaunchPlan(
    val target: TierERegisteredTarget,
    private val definitionDirectory: Path,
) {
    init {
        require(definitionDirectory.isAbsolute) { "definition directory must be absolute" }
        require(!definitionDirectory.toString().contains(',')) { "definition directory must not contain an agent option separator" }
    }

    val definitionPath: Path = definitionDirectory.resolve(target.artifactFileName).normalize().also { path ->
        require(path.parent == definitionDirectory.normalize()) { "definition artifact escaped its configured directory" }
    }

    fun nativeAgentOptions(): String =
        buildString {
        append("session=${target.sessionTag.toString(16)},session-bound-definitions,definitions=$definitionPath")
        target.markerProofFileName?.let { proofName ->
            val proof = definitionDirectory.resolve(proofName).normalize()
            require(proof.parent == definitionDirectory.normalize()) { "marker proof escaped definition directory" }
            append(",marker-version=v2,marker-version-proof=$proof")
        }
    }
}

/** Exact-match admission boundary for legacy v1 ATTACH commands. */
internal class TierETargetRegistrationResolver(
    registrations: Map<String, TierERegisteredTarget>,
) {
    private val registrations = registrations.toMap()

    init {
        require(this.registrations.isNotEmpty()) { "at least one registered target is required" }
        require(this.registrations.all { (id, target) -> id == target.registrationId }) {
            "registration map keys must match registration ids"
        }
    }

    fun resolve(command: ControlCommand.Attach): TierERegisteredTarget =
        registrations.values.singleOrNull {
            it.pid == command.pid &&
                it.sessionTag == command.sessionTag &&
                it.markerPath.toString() == command.markerPath &&
                it.markerOffset == command.markerOffset
        } ?: throw IllegalArgumentException("ATTACH does not match a registered target")
}

internal enum class TierEProcessIdentityCheck { MATCHED, EXITED, REUSED }

/**
 * Validates a registered process incarnation without coupling the collector core to Docker,
 * Kubernetes, or any target-discovery implementation.
 */
internal class TierEProcessIdentityVerifier(
    private val observedIncarnation: (Int) -> Long?,
) {
    fun requireCurrent(target: TierERegisteredTarget) {
        require(observedIncarnation(target.pid) == target.processIncarnation) {
            "registered target process identity is stale or unavailable"
        }
    }

    fun checkAtTerminal(target: TierERegisteredTarget): TierEProcessIdentityCheck =
        when (observedIncarnation(target.pid)) {
            null -> TierEProcessIdentityCheck.EXITED
            target.processIncarnation -> TierEProcessIdentityCheck.MATCHED
            else -> TierEProcessIdentityCheck.REUSED
        }

    public companion object {
        /** Linux /proc starttime is stable for one PID lifetime within the current boot. */
        public val linux: TierEProcessIdentityVerifier = TierEProcessIdentityVerifier(::observeLinuxIncarnation)

        internal fun observeLinuxIncarnation(pid: Int): Long? =
            runCatching {
                val stat = Files.readString(Path.of("/proc/$pid/stat"))
                val closingParenthesis = stat.lastIndexOf(')')
                require(closingParenthesis >= 0) { "malformed proc stat" }
                val fields = stat.substring(closingParenthesis + 1).trim().split(Regex("\\s+"))
                fields.getOrNull(19)?.toLongOrNull()
            }.getOrNull()
    }
}

/** Strict, dependency-free reader for one read-only operator registration file. */
internal object TierETargetRegistrationFile {
    private const val VERSION_1 = "1"
    private const val VERSION_2 = "2"
    private val REQUIRED_KEYS = setOf(
        "version",
        "registration_id",
        "pid",
        "session_tag",
        "process_incarnation",
        "marker_path",
        "marker_offset",
        "artifact_file",
    )
    private val REQUIRED_KEYS_V2 = REQUIRED_KEYS + "marker_proof_file"

    fun read(path: Path): TierERegisteredTarget {
        require(!Files.isSymbolicLink(path)) { "target registration file must not be a symbolic link" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "target registration file must be an existing regular file"
        }
        val values = Files.readAllLines(path).fold(linkedMapOf<String, String>()) { parsed, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@fold parsed
            val separator = trimmed.indexOf('=')
            require(separator > 0 && separator == trimmed.lastIndexOf('=')) { "invalid target registration line" }
            val key = trimmed.substring(0, separator)
            val value = trimmed.substring(separator + 1)
            require(key in REQUIRED_KEYS_V2 && value.isNotBlank() && parsed.put(key, value) == null) {
                "invalid or duplicate target registration key"
            }
            parsed
        }
        val version = values["version"]
        require(version == VERSION_1 || version == VERSION_2) { "unsupported target registration version" }
        require(values.keys == if (version == VERSION_2) REQUIRED_KEYS_V2 else REQUIRED_KEYS) {
            "target registration is incomplete"
        }
        val sessionTag = values.getValue("session_tag").removePrefix("0x").toIntOrNull(16)
            ?: throw IllegalArgumentException("target registration session tag is invalid")
        return TierERegisteredTarget(
            registrationId = values.getValue("registration_id"),
            pid = values.getValue("pid").toIntOrNull()
                ?: throw IllegalArgumentException("target registration pid is invalid"),
            sessionTag = sessionTag,
            processIncarnation = values.getValue("process_incarnation").toLongOrNull()
                ?: throw IllegalArgumentException("target registration process incarnation is invalid"),
            markerPath = Path.of(values.getValue("marker_path")),
            markerOffset = values.getValue("marker_offset").removePrefix("0x").toLongOrNull(16)
                ?: throw IllegalArgumentException("target registration marker offset is invalid"),
            artifactFileName = values.getValue("artifact_file"),
            markerProofFileName = values["marker_proof_file"],
        )
    }
}

private fun String.isSafeFileName(): Boolean = isNotBlank() && Path.of(this).fileName.toString() == this && !contains('/') && !contains('\\') && this != "." && this != ".."
