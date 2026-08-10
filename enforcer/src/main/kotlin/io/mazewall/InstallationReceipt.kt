package io.mazewall

/**
 * A receipt describing the result of a seccomp or Landlock sandbox installation attempt.
 * 
 * **Warning:** Linux containment filters (Seccomp and Landlock) are irreversible for the lifetime of the thread.
 * This receipt is strictly for diagnostic and assessment purposes and cannot be "closed" or "uninstalled" to lift the sandbox.
 * 
 * If the policy required an active out-of-process supervisor (e.g. for dynamic path resolution or logging), 
 * the [supervisorSession] property will contain a closeable session to release the background tracking resources when the 
 * thread terminates. Closing the supervisor session **does not** uninstall the seccomp filter; it simply tears down 
 * the notification listener. Any subsequent syscalls that would have been routed to the supervisor will receive ENOSYS.
 *
 * Callers performing diagnostics or attestation must check [outcome]. When explicitly configured fallback behavior allows
 * execution to continue after an installation failure, [outcome] is [InstallationOutcome.BYPASSED] and no containment is
 * implied by this receipt.
 */
public data class InstallationReceipt(
    public val processWide: Boolean,
    public val requestedPolicy: PolicyDefinition<*>,
    public val supervisorSession: AutoCloseable? = null,
    public val timestampMillis: Long = System.currentTimeMillis(),
    public val outcome: InstallationOutcome = InstallationOutcome.INSTALLED,
) {
    /** Preserves the JVM constructor exposed before [outcome] was added. */
    public constructor(
        processWide: Boolean,
        requestedPolicy: PolicyDefinition<*>,
        supervisorSession: AutoCloseable?,
        timestampMillis: Long,
    ) : this(
        processWide = processWide,
        requestedPolicy = requestedPolicy,
        supervisorSession = supervisorSession,
        timestampMillis = timestampMillis,
        outcome = InstallationOutcome.INSTALLED,
    )
}

/** The effective result represented by an [InstallationReceipt]. */
public enum class InstallationOutcome {
    /** The requested containment is active on the target. */
    INSTALLED,

    /** Explicit fallback configuration allowed uncontained execution after installation could not complete. */
    BYPASSED,
}
