package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*

/**
 * Seccomp status mode of the JVM process.
 */
public sealed interface SeccompMode {
    /** Seccomp is disabled or not configured. */
    public data object Disabled : SeccompMode

    /** Seccomp strict mode is active. */
    public data object Strict : SeccompMode

    /** Seccomp filter mode (mode 2) is active and running user-defined filters. */
    public data object Filter : SeccompMode

    /** Failed to query or retrieve the Seccomp mode. */
    public data class Error(
        public val errno: Int,
    ) : SeccompMode
}

/**
 * Yama ptrace_scope status.
 */
public sealed interface YamaPtraceScope {
    /** Classic ptrace permissions (0). */
    public data object Classic : YamaPtraceScope

    /** Restricted ptrace permissions to parent/descendants (1). */
    public data object Restricted : YamaPtraceScope

    /** Admin-only ptrace attach (CAP_SYS_PTRACE required) (2). */
    public data object AdminOnly : YamaPtraceScope

    /** Ptrace disabled entirely (3). */
    public data object Disabled : YamaPtraceScope

    /** Unknown raw ptrace scope value. */
    public data class Unknown(
        public val rawValue: Int,
    ) : YamaPtraceScope

    /** Yama security module is not loaded or ptrace_scope file is unavailable. */
    public data object Unavailable : YamaPtraceScope
}
