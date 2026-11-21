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
public class InstallationReceipt private constructor(
    public val processWide: Boolean,
    public val requestedPolicy: PolicyDefinition<*>,
    public val timestampMillis: Long,
    public val fallback: Platform.FallbackBehavior,
    public val landlockApplied: Boolean,
    public val cetOutcome: CetOutcome,
    internal val outcome: Outcome,
    private var supervisorRegistration: AutoCloseable?,
) : AutoCloseable {
    public constructor(
        processWide: Boolean,
        requestedPolicy: PolicyDefinition<*>,
        timestampMillis: Long = System.currentTimeMillis(),
        installed: Boolean = true,
        fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
        landlockApplied: Boolean = false,
        progress: InstallationProgress? = null,
        failure: Throwable? = null,
        cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
    ) : this(
        processWide = processWide,
        requestedPolicy = requestedPolicy,
        timestampMillis = timestampMillis,
        fallback = fallback,
        landlockApplied = landlockApplied,
        cetOutcome = cetOutcome,
        outcome = outcomeFromFlags(installed, fallback, progress, failure),
        supervisorRegistration = null,
    )

    internal sealed interface Outcome {
        val progress: InstallationProgress?

        data class Installed(
            override val progress: InstallationProgress? = null,
        ) : Outcome

        data class Failed(
            val cause: Throwable?,
            override val progress: InstallationProgress? = null,
        ) : Outcome

        data class Bypassed(
            val reason: Throwable?,
            override val progress: InstallationProgress? = null,
        ) : Outcome
    }

    public val installed: Boolean
        get() = outcome is Outcome.Installed

    /** Stages newly applied by this attempt, not a summary of inherited restrictions or cached CET proof. */
    public val progress: InstallationProgress?
        get() = outcome.progress

    /** Original installation failure, or the recorded reason for an explicit unsupported-feature bypass. */
    public val failure: Throwable?
        get() =
            when (outcome) {
                is Outcome.Installed -> null
                is Outcome.Failed -> outcome.cause
                is Outcome.Bypassed -> outcome.reason
            }

    /** Retires only the supervisor registration; installed containment remains irreversible. */
    @Synchronized
    override fun close() {
        val registration = supervisorRegistration ?: return
        supervisorRegistration = null
        registration.close()
    }

    /**
     * Fail-closed unpack. Requires installed Seccomp, requested filesystem restriction, and requested
     * current-thread shadow-stack proof. Process-wide CET coverage is unsupported.
     * Closing the receipt after rejection still releases its registration; applied filters remain irreversible.
     * A recorded installation failure is rethrown with its original identity and cause chain.
     */
    public fun requireInstalled(): InstallationReceipt {
        when (val current = outcome) {
            is Outcome.Failed -> {
                current.cause?.let { throw it }
                error(INCOMPLETE_INSTALLATION_MESSAGE)
            }
            is Outcome.Bypassed -> {
                current.reason?.let { throw it }
                error(INCOMPLETE_INSTALLATION_MESSAGE)
            }
            is Outcome.Installed -> Unit
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
        private const val INCOMPLETE_INSTALLATION_MESSAGE =
            "Required containment installation did not complete. Already applied restrictions remain in force."

        init {
            preloadForSeccompInstall()
        }

        internal fun preloadForSeccompInstall() {
            Outcome::class.java.name
            Outcome.Installed::class.java.name
            Outcome.Failed::class.java.name
            Outcome.Bypassed::class.java.name
        }

        private fun outcomeFromFlags(
            installed: Boolean,
            fallback: Platform.FallbackBehavior,
            progress: InstallationProgress?,
            failure: Throwable?,
        ): Outcome =
            if (installed) {
                require(failure == null) { "Installed receipt cannot carry an installation failure" }
                Outcome.Installed(progress)
            } else if (fallback == Platform.FallbackBehavior.FAIL) {
                Outcome.Failed(cause = failure, progress = progress)
            } else {
                Outcome.Bypassed(reason = failure, progress = progress)
            }

        fun installed(
            processWide: Boolean,
            requestedPolicy: PolicyDefinition<*>,
            timestampMillis: Long = System.currentTimeMillis(),
            fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
            landlockApplied: Boolean = false,
            progress: InstallationProgress? = null,
            cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
            supervisorRegistration: AutoCloseable? = null,
        ): InstallationReceipt =
            InstallationReceipt(
                processWide = processWide,
                requestedPolicy = requestedPolicy,
                timestampMillis = timestampMillis,
                fallback = fallback,
                landlockApplied = landlockApplied,
                cetOutcome = cetOutcome,
                outcome = Outcome.Installed(progress),
                supervisorRegistration = supervisorRegistration,
            )

        fun failed(
            processWide: Boolean,
            requestedPolicy: PolicyDefinition<*>,
            cause: Throwable,
            timestampMillis: Long = System.currentTimeMillis(),
            fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
            landlockApplied: Boolean = false,
            progress: InstallationProgress? = null,
            cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
            supervisorRegistration: AutoCloseable? = null,
        ): InstallationReceipt =
            InstallationReceipt(
                processWide = processWide,
                requestedPolicy = requestedPolicy,
                timestampMillis = timestampMillis,
                fallback = fallback,
                landlockApplied = landlockApplied,
                cetOutcome = cetOutcome,
                outcome = Outcome.Failed(cause = cause, progress = progress),
                supervisorRegistration = supervisorRegistration,
            )

        fun bypassed(
            processWide: Boolean,
            requestedPolicy: PolicyDefinition<*>,
            reason: Throwable,
            timestampMillis: Long = System.currentTimeMillis(),
            fallback: Platform.FallbackBehavior = Platform.configuredFallback(),
            landlockApplied: Boolean = false,
            progress: InstallationProgress? = null,
            cetOutcome: CetOutcome = if (requestedPolicy.lockIntelCet) CetOutcome.UNCONFIRMED else CetOutcome.NOT_REQUESTED,
            supervisorRegistration: AutoCloseable? = null,
        ): InstallationReceipt =
            InstallationReceipt(
                processWide = processWide,
                requestedPolicy = requestedPolicy,
                timestampMillis = timestampMillis,
                fallback = fallback,
                landlockApplied = landlockApplied,
                cetOutcome = cetOutcome,
                outcome = Outcome.Bypassed(reason = reason, progress = progress),
                supervisorRegistration = supervisorRegistration,
            )

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
            InstallationReceipt(
                processWide = processWide,
                requestedPolicy = requestedPolicy,
                timestampMillis = timestampMillis,
                fallback = fallback,
                landlockApplied = landlockApplied,
                cetOutcome = cetOutcome,
                outcome = outcomeFromFlags(installed, fallback, progress, failure),
                supervisorRegistration = supervisorRegistration,
            )
    }
}
