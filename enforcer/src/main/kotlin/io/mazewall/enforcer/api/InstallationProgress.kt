package io.mazewall.enforcer.api

/** Newly applied irreversible stages; inherited restrictions and cached CET proof are not included. */
public data class InstallationProgress(
    val processWide: Boolean,
    val landlockApplied: Boolean,
    val seccompApplied: Boolean,
    val cetLocked: Boolean = false,
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

/** Fallback is selected once. Applied-stage marks are written only by successful native-stage publication. */
internal class InstallationAttempt(
    private val processWide: Boolean,
    private val environment: InstallationEnvironment,
) {
    val native get() = environment.native
    val selfVerificationEnabled get() = environment.selfVerificationEnabled
    val platform get() = environment.platform
    val fallback get() = environment.fallback
    var landlockApplied: Boolean = false
    var seccompApplied: Boolean = false
    var failure: Throwable? = null
    var cetLocked: Boolean = false
    var cetOutcome: CetOutcome = CetOutcome.NOT_REQUESTED

    fun snapshot(): InstallationProgress = InstallationProgress(processWide, landlockApplied, seccompApplied, cetLocked)
}
