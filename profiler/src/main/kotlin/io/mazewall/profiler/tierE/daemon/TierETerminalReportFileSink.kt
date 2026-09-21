package io.mazewall.profiler.tierE.daemon

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Atomically stores a platform-neutral terminal report in an operator-owned evidence root. */
internal class TierETerminalReportFileSink(
    root: Path,
) : TierETerminalReportSink {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            "Tier E evidence root must be an existing directory"
        }
        require(!Files.isSymbolicLink(this.root)) { "Tier E evidence root must not be a symbolic link" }
    }

    override fun accept(report: TierETerminalReport) {
        val registrationId = requireNotNull(report.registrationId) { "terminal report lacks registration identity" }
        val sessionEpoch = requireNotNull(report.sessionEpoch) { "terminal report lacks session epoch" }
        require(registrationId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))) {
            "terminal report registration id is invalid"
        }
        val file = root.resolve("$registrationId-$sessionEpoch.tier-e.json").normalize()
        require(file.parent == root) { "terminal report escaped evidence root" }
        val temporary = Files.createTempFile(root, ".$registrationId-$sessionEpoch.", ".tmp")
        try {
            Files.newByteChannel(temporary, setOf(TRUNCATE_EXISTING, WRITE)).use { channel ->
                channel.write(StandardCharsets.UTF_8.encode(report.toJson()))
            }
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun TierETerminalReport.toJson(): String {
    val artifactLength = artifactByteLength?.toString() ?: "null"
    val artifactDigest = artifactSha256?.let { "\"$it\"" } ?: "null"
    return """{"schema_version":1,"registration_id":"$registrationId","session_epoch":$sessionEpoch,""" +
        """"target_pid":$targetPid,"process_incarnation":$processIncarnation,""" +
        """"artifact_byte_length":$artifactLength,"artifact_sha256":$artifactDigest,""" +
        """"evidence_integrity":"$evidenceIntegrity","terminal_reason":"$terminalReason",""" +
        """"resolved_syscalls":$resolvedSyscalls,"capture_domain_validity":$captureDomainValidity,""" +
        """"transport_completeness":$transportCompleteness,"attribution_coverage":$attributionCoverage,""" +
        """"dictionary_integrity":$dictionaryIntegrity,"lifecycle_validity":$lifecycleValidity,""" +
        """"policy_eligibility":false}"""
}
