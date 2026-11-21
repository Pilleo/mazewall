package io.mazewall.lockdown

import io.mazewall.lockdown.internal.enforcer.api.PolicyDefinition
import io.mazewall.lockdown.internal.enforcer.api.PolicyScope
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import java.util.Objects

/**
 * Immutable process-wide seccomp policy for [Mazewall.installOnProcess].
 */
public class ProcessPolicy private constructor(
    @get:JvmSynthetic
    internal val runtime: RuntimeProfile,
    @get:JvmSynthetic
    internal val definition: PolicyDefinition<PolicyScope.ProcessWideSafe>,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is ProcessPolicy && runtime == other.runtime && definition == other.definition)

    override fun hashCode(): Int = Objects.hash(runtime, definition)

    override fun toString(): String = "ProcessPolicy(runtime=$runtime)"

    public companion object {
        /**
         * Creates a process-wide policy blocking external program execution and memory/async
         * execution vectors (`execve`, `execveat`, `memfd_create`, `io_uring_setup`, `io_uring_enter`)
         * with `EPERM`.
         *
         * The compiled `0.1.0` filter also restricts `prctl` to the safe thread-naming, seccomp, and
         * `no_new_privs` options (`PR_SET_NAME`, `PR_GET_NAME`, `PR_GET_SECCOMP`, `PR_SET_SECCOMP`,
         * `PR_SET_NO_NEW_PRIVS`, `PR_GET_NO_NEW_PRIVS`) returning `EPERM` for other `prctl` subcommands,
         * and returns `ENOSYS` for `clone3` so glibc falls back cleanly to `clone`. `fork`, `vfork`,
         * and `clone` remain permitted in `0.1.0` (any child inherits the irreversible seccomp filter).
         *
         * [RuntimeProfile.HOTSPOT_JIT] keeps executable mappings permitted for the JIT code cache,
         * while [RuntimeProfile.NATIVE_IMAGE] denies executable `mmap`/`mprotect`/`pkey_mprotect`
         * (`PROT_EXEC`) mappings as well.
         */
        @JvmStatic
        public fun denyProcessCreation(runtime: RuntimeProfile): ProcessPolicy =
            ProcessPolicy(
                runtime = runtime,
                definition =
                    ProcessFilterInstaller.denyProcessCreationDefinition(
                        allowExecutableMappings = runtime == RuntimeProfile.HOTSPOT_JIT,
                    ),
            )
    }
}
