package io.mazewall.enforcer.api

/** Irreversible stages completed by an installation attempt before it failed. */
public data class InstallationProgress(
    val processWide: Boolean,
    val landlockApplied: Boolean,
    val seccompApplied: Boolean,
)

/** Preserves the original failure as cause and explicitly reports partial installation. */
public class PartialInstallationException(
    public val progress: InstallationProgress,
    cause: Throwable? = null,
) : IllegalStateException("Containment installation failed after irreversible stages: $progress", cause)

internal fun installationState(processWide: Boolean): io.mazewall.enforcer.state.ContainerState =
    if (processWide) {
        io.mazewall.enforcer.state.ContainmentStateRegistry.processState
    } else {
        io.mazewall.enforcer.state.ContainerState
            .resolveCurrentState()
    }

/** Attempt-local marks are written only by successful native-stage publication. */
internal class InstallationAttempt(
    private val processWide: Boolean,
) {
    var landlockApplied: Boolean = false
    var seccompApplied: Boolean = false

    fun snapshot(): InstallationProgress = InstallationProgress(processWide, landlockApplied, seccompApplied)
}
