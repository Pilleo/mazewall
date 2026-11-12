package io.mazewall.profiler.testing.tierE

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.testing.containers.ContainerCommandResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Test-only effects shared with the integration source set, not the installed distribution. */
object TierEInstalledFixtureSafety {
    data class DaemonStartupIdentity(
        val pid: Long,
        val start: Long,
        val cgroup: String,
    )

    fun stopDaemon(
        launchAttempted: Boolean,
        startup: DaemonStartupIdentity?,
        execClient: Process?,
        absent: (DaemonStartupIdentity) -> Boolean,
        terminate: (DaemonStartupIdentity) -> Unit,
    ) {
        if (!launchAttempted) return
        val identity = checkNotNull(startup) { "collector startup identity unavailable; retain owned container for recovery" }
        if (!absent(identity)) {
            terminate(identity)
            check(absent(identity)) { "physical daemon absence is unverified" }
        }
        execClient?.let { check(it.waitFor(15, TimeUnit.SECONDS)) { "collector exec client did not physically exit" } }
    }

    fun finish(
        exercise: () -> Unit,
        quiesce: () -> Unit,
        export: () -> Unit,
        restore: () -> Unit,
    ) {
        var outcome: Throwable? = null
        try {
            exercise()
        } catch (failure: Throwable) {
            outcome = failure
        }
        try {
            quiesce()
            export()
            if (outcome == null) restore()
        } catch (failure: Throwable) {
            outcome = CleanupFailures.merge(outcome, failure)
        }
        outcome?.let { throw it }
    }

    fun retainArtifact(
        result: ContainerCommandResult,
        required: Boolean,
        destination: Path,
        write: (Path, ByteArray) -> Unit = { path, bytes -> Files.write(path, bytes) },
    ) {
        if (!required && result.exitCode == 44) return
        check(result.exitCode == 0) { "artifact export failed (${result.exitCode}): ${result.output}" }
        val bytes = Base64.getDecoder().decode(result.output.trim())
        write(destination, bytes)
        check(Files.readAllBytes(destination).contentEquals(bytes)) { "retained artifact bytes differ: $destination" }
    }

    fun script(name: String): String = requireNotNull(javaClass.getResource("/tier-e-fixture-$name.py")).readText()
}
