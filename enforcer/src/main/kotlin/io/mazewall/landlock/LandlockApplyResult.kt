package io.mazewall.landlock

import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.ffi.NativeConstants

/**
 * Outcome of applying a Landlock ruleset. Callers must unpack this type;
 * there is no implicit success.
 */
internal sealed interface LandlockFdOutcome {
    data class Ok(
        val fd: io.mazewall.core.FileDescriptor<io.mazewall.core.FileDescriptorRole.Ruleset, io.mazewall.core.FdState.Open>,
    ) : LandlockFdOutcome

    data class Err(val errno: Int, val rawValue: Long) : LandlockFdOutcome
}

internal sealed interface LandlockRestrictOutcome {
    data class Ok(val ruleset: LandlockRuleset<RulesetState.Sealed>) : LandlockRestrictOutcome
    data class Err(val errno: Int) : LandlockRestrictOutcome
}

public sealed interface LandlockApplyResult {
    public data object Applied : LandlockApplyResult

    /** Operator-configured fallback allowed the process to continue without Landlock. */
    public data class Bypassed(val reason: String) : LandlockApplyResult

    public data class Rejected(
        val reason: String,
        val errno: Int? = null,
        val cause: Throwable? = null,
    ) : LandlockApplyResult

    /**
     * Fail-closed unpack. [Applied] and operator [Bypassed] return; [Rejected] always throws.
     * EPERM/EACCES are never treated as success.
     */
    public fun orThrow() {
        when (this) {
            is Applied, is Bypassed -> return
            is Rejected -> throw toException()
        }
    }

    public fun isApplied(): Boolean = this is Applied
}

internal fun LandlockApplyResult.toFailure(): Throwable = when (this) {
    is LandlockApplyResult.Rejected -> toException()
    is LandlockApplyResult.Bypassed -> IllegalStateException(reason)
    is LandlockApplyResult.Applied -> IllegalStateException("Landlock applied")
}

internal fun LandlockApplyResult.Rejected.toException(): Throwable {
    cause?.let { return it }
    val errno = errno
    if (errno == NativeConstants.ENOSYS ||
        errno == NativeConstants.EOPNOTSUPP ||
        errno == NativeConstants.ENOPKG ||
        reason.contains("not supported", ignoreCase = true)
    ) {
        return UnsupportedKernelFeatureException(reason)
    }
    return IllegalStateException(
        if (errno != null) "$reason (errno=$errno)" else reason,
    )
}
