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
     * Fail-closed unpack. Requires installed Seccomp and any filesystem restriction requested by this policy.
     * Closing the receipt after rejection still releases its registration; applied filters remain irreversible.
     */
    public fun requireInstalled(): InstallationReceipt {
        check(installed) {
            "Required containment installation did not complete. Already applied restrictions remain in force."
        }
        check(!requestedPolicy.enforceLandlock || landlockApplied) {
            "Containment installation did not apply the requested filesystem restriction."
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
        ): InstallationReceipt =
            InstallationReceipt(processWide, requestedPolicy, timestampMillis, installed, fallback, landlockApplied).also {
                it.supervisorRegistration = supervisorRegistration
            }
    }
}
