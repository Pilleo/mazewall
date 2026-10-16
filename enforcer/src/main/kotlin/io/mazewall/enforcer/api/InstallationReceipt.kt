package io.mazewall.enforcer.api

/**
 * A receipt describing the result of a seccomp or Landlock sandbox installation attempt.
 *
 * **Warning:** Linux containment filters (Seccomp and Landlock) are irreversible for the lifetime of the thread.
 * This receipt is strictly for diagnostic and assessment purposes and cannot be "closed" or "uninstalled" to lift the sandbox.
 *
 * If the policy required an active out-of-process supervisor (e.g. for dynamic path resolution or logging),
 * the receipt owns a closeable task-registration lease. Closing the receipt releases only that
 * task's registration; subsequent tasks reacquire the same permanent
 * runtime. The installation owner retires the listener when its platform thread terminates.
 *
 * Callers performing diagnostics or attestation must check [installed]. When explicitly configured fallback behavior allows
 * a manual installer to return after failure, [installed] is `false` and does not imply a seccomp filter.
 * Contained executors require this receipt before target entry and reject such a bypass result.
 * [landlockApplied] is independent: Landlock cannot be undone, so a failed seccomp step after a successful Landlock
 * apply still leaves filesystem rules in force.
 */
public class InstallationReceipt(
    public val processWide: Boolean,
    public val requestedPolicy: PolicyDefinition<*>,
    public val timestampMillis: Long = System.currentTimeMillis(),
    public val installed: Boolean = true,
    public val fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
    public val landlockApplied: Boolean = false,
    /** Stages newly applied by this attempt, not a summary of inherited restrictions or cached CET proof. */
    public val progress: InstallationProgress? = null,
    /** Original installation failure, or the recorded reason for an explicit unsupported-feature bypass. */
    public val failure: Throwable? = null,
    public val cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
) : AutoCloseable {
    private var supervisorRegistration: AutoCloseable? = null

    /** Retires only the supervisor registration; installed containment remains irreversible. */
    @Synchronized
    override fun close() {
        val registration = supervisorRegistration ?: return
        registration.close()
        supervisorRegistration = null
    }

    /**
     * Fail-closed unpack. Requires installed Seccomp, requested filesystem restriction, and requested
     * current-thread shadow-stack proof. Process-wide CET coverage is unsupported.
     * Closing the receipt after rejection still releases its registration; applied filters remain irreversible.
     * A recorded installation failure is rethrown with its original identity and cause chain.
     */
    public fun requireInstalled(): InstallationReceipt {
        if (!installed && failure != null) throw failure
        check(installed) {
            "Required containment installation did not complete. Already applied restrictions remain in force."
        }
        check(!requestedPolicy.enforceLandlock || landlockApplied) {
            "Containment installation did not apply the requested filesystem restriction."
        }
        check(!requestedPolicy.lockIntelCet || (!processWide && cetOutcome == CetOutcome.VERIFIED_CURRENT_THREAD)) {
            "Requested current-thread shadow-stack locking was not verified, or process-wide coverage is unsupported."
        }
        return this
    }

    public fun isInstalled(): Boolean = installed

    public fun isProcessWide(): Boolean = processWide

    public fun isLandlockApplied(): Boolean = landlockApplied

    internal val ownsSupervisorLease: Boolean
        @Synchronized get() = supervisorRegistration != null

    /**
     * For fire-and-forget install APIs that cannot keep a receipt.
     * Supervised installs must retain the receipt until the task ends.
     */
    internal fun releaseIfUnsupervised() {
        check(!ownsSupervisorLease) {
            "Supervised containment requires keeping InstallationReceipt open until the task ends"
        }
        close()
    }

    internal companion object {
        fun withSupervisorRegistration(
            processWide: Boolean,
            requestedPolicy: PolicyDefinition<*>,
            supervisorRegistration: AutoCloseable,
            timestampMillis: Long = System.currentTimeMillis(),
            installed: Boolean = true,
            fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
            landlockApplied: Boolean = false,
            progress: InstallationProgress? = null,
            failure: Throwable? = null,
            cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
        ): InstallationReceipt =
            InstallationReceipt(processWide, requestedPolicy, timestampMillis, installed, fallback, landlockApplied, progress, failure, cetOutcome).also {
                it.supervisorRegistration = supervisorRegistration
            }
    }
}
