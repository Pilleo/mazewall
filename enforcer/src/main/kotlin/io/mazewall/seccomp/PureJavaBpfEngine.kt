package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.CompiledSandbox
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.core.PrctlCommand
import io.mazewall.enforcer.ThreadStateRegistry
import io.mazewall.enforcer.ContainerState
import io.mazewall.enforcer.ProcessStateRegistry
import io.mazewall.ffi.NativeConstants
import java.util.logging.Logger
import java.util.logging.Level

/**
 * Pure Java implementation of the seccomp engine.
 * Generates BPF filters manually and installs them using Downcalls.
 */
// @ref: docs/internals/designs/enforcer/containment-design.md — prctl/seccomp(2) install sequence, TSYNC flag semantics, FFM memory layout
internal object PureJavaBpfEngine : SeccompEngine<EngineState> {
    private val logger = Logger.getLogger(PureJavaBpfEngine::class.java.name)

    /**
     * Clears the native filter cache. Used for testing.
     */
    internal fun clearCache() {
        BpfNativeCache.clear()
    }

    override val state: EngineState
        get() = when (ContainerState.resolveCurrentState().engineState) {
            is SeccompInstallationState.Uninitialized -> EngineState.UnprivilegedImpl
            is SeccompInstallationState.Verified -> EngineState.LoadedImpl
            else -> EngineState.ConfiguredImpl
        }

    override val isSupported: Boolean
        get() = Platform.isSupported()

    override fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
        installInternal(policy, useTsync = false)
        @Suppress("UNCHECKED_CAST")
        return this as SeccompEngine<EngineState.Loaded>
    }

    override fun installOnProcess(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
        if (!Platform.featureMatrix.seccompTsyncSupported) {
            throw UnsupportedKernelFeatureException("Process-wide Seccomp synchronization (TSYNC) requires Linux 3.17+.")
        }
        installInternal(policy, useTsync = true)
        @Suppress("UNCHECKED_CAST")
        return this as SeccompEngine<EngineState.Loaded>
    }

    @Suppress("TooGenericExceptionCaught")
    private fun installInternal(
        policy: CompiledSandbox<*>,
        useTsync: Boolean,
    ) {
        // Pre-charge classloading of engine states to prevent deadlocks/failures under active filters
        EngineState.UnprivilegedImpl.toString()
        EngineState.LoadedImpl.toString()
        EngineState.ConfiguredImpl.toString()
        SeccompInstallationState.Uninitialized.toString()
        SeccompInstallationState.PrivilegesLocked::class.java.name
        SeccompInstallationState.SystemCallApplied.toString()
        SeccompInstallationState.FallbackPrctlApplied.toString()
        SeccompInstallationState.Verified.toString()
        SeccompInstallationState.FilterBuilt::class.java.name
        SeccompInstallationState.Failed::class.java.name
        BpfNativeCache.toString()

        updateState(SeccompInstallationState.Uninitialized, useTsync)
        try {
            val arch = Arch.current()
            val filters = policy.compiledFilters
            val cachedProg = BpfNativeCache.getOrCompute(filters)

            val built = SeccompInstallationState.FilterBuilt(cachedProg)
            updateState(built, useTsync)
            val locked = built.lockPrivileges()
            updateState(locked, useTsync)
            val applied = locked.applyFilter(arch, useTsync)
            updateState(applied, useTsync)
            val verified = applied.verify(policy.definition)
            updateState(verified, useTsync)
        } catch (e: Exception) {
            val stepName = getStepName()
            val errno = getErrno(e)
            updateState(SeccompInstallationState.Failed(stepName, errno, e), useTsync)
            throw e
        } catch (e: Error) {
            val stepName = getStepName()
            logger.log(Level.SEVERE, "FATAL: Uncaught native or JVM error during seccomp installation at step $stepName", e)
            val errno = getErrno(e)
            updateState(SeccompInstallationState.Failed(stepName, errno, e), useTsync)
            throw e
        }
    }

    private fun getStepName(): String {
        return when (ContainerState.resolveCurrentState().engineState) {
            is SeccompInstallationState.PrivilegesLocked -> "installFilter"
            is SeccompInstallationState.SystemCallApplied -> "verifyInstallation"
            is SeccompInstallationState.FallbackPrctlApplied -> "verifyInstallation"
            is SeccompInstallationState.FilterBuilt -> "setNoNewPrivs"
            is SeccompInstallationState.Uninitialized -> "buildFilter"
            is SeccompInstallationState.Verified -> "verified"
            is SeccompInstallationState.Failed -> "failed"
        }
    }

    private fun getErrno(e: Throwable): Int {
        return when {
            e.message?.contains("errno") == true -> {
                val match = Regex("errno\\s*=?\\s*(-?\\d+)").find(e.message ?: "")
                match?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
            else -> -1
        }
    }

    private fun updateState(next: SeccompInstallationState, useTsync: Boolean) {
        if (useTsync) {
            ProcessStateRegistry.update { it.withEngineState(next) }
        }
        ThreadStateRegistry.state = ThreadStateRegistry.state.withEngineState(next)
    }

    /**
     * Irreversibly locks the process from gaining new privileges.
     *
     * Once set, the PR_SET_NO_NEW_PRIVS flag cannot be cleared. This affects the
     * current thread/process and all its future children spawned via fork/exec.
     * This is a prerequisite for installing seccomp filters for unprivileged users.
     *
     * Note: This system call is executed within a retry loop to handle signal
     * interruptions (EINTR) robustly, ensuring that temporary interruptions do not
     * abort or bypass privilege locking.
     */
    internal fun setNoNewPrivs() {
        // Step 1: Set no_new_privs (mandatory for non-root seccomp)
        while (true) {
            val result = LinuxNative.process.prctl(PrctlCommand.SetNoNewPrivs(true))
            if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) {
                continue
            }
            result.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")
            break
        }
    }

    /**
     * Installs the compiled BPF filter program into the kernel.
     *
     * Note: This invokes the modern seccomp(2) syscall first and falls back to
     * prctl if seccomp(2) is unsupported. Both system calls are explicitly wrapped
     * in retry loops that check for and handle signal interruptions (EINTR). This
     * prevents premature failures due to asynchronous signals (e.g., JVM GC, JIT, or profiling).
     */
    internal fun installFilter(
        arch: Arch,
        prog: ManagedSegment,
        useTsync: Boolean,
    ): SeccompInstallationState.FilterApplied {
        // Try modern seccomp(2) syscall first
        val flags = if (useTsync) NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong() else 0L
        var seccompResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
        while (true) {
            seccompResult = LinuxNative.raw.syscall(
                arch.seccompSyscallNumber.toLong(),
                io.mazewall.core.NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                io.mazewall.core.NativeArg.LongArg(flags),
                io.mazewall.core.NativeArg.MemoryArg(prog),
            )
            if (seccompResult is LinuxNative.SyscallResult.Error && seccompResult.errno == NativeConstants.EINTR) {
                continue
            }
            break
        }

        if (seccompResult is LinuxNative.SyscallResult.Error) {
            // Fall back to prctl for older kernels
            val errno1 = seccompResult.errno

            if (useTsync) {
                val detail = if (errno1 == 13) {
                    "failed with EACCES (Permission denied). This typically occurs because some pre-existing sibling threads (e.g. GC, JIT, or VM helper threads spawned during JVM startup) do not have the 'no_new_privs' flag enabled. To fix this, ensure the JVM process is started with privilege escalation disabled (e.g., OCI/Kubernetes 'allowPrivilegeEscalation: false', or running under a wrapper launcher that sets the flag before calling execve)."
                } else {
                    "failed with errno $errno1. Your kernel may be too old to support SECCOMP_FILTER_FLAG_TSYNC or the parameters are invalid."
                }
                throw IllegalStateException("Process-wide seccomp installation (TSYNC) failed: $detail")
            }

            var prctlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
            while (true) {
                prctlResult = LinuxNative.process.prctl(
                    PrctlCommand.SetSeccomp(
                        NativeConstants.SECCOMP_MODE_FILTER.toLong(),
                        io.mazewall.core.NativeArg.MemoryArg(prog)
                    )
                )
                if (prctlResult is LinuxNative.SyscallResult.Error && prctlResult.errno == NativeConstants.EINTR) {
                    continue
                }
                break
            }

            if (prctlResult is LinuxNative.SyscallResult.Error) {
                throw IllegalStateException(
                    "seccomp installation failed: seccomp(2) errno=$errno1, prctl errno=${prctlResult.errno}",
                )
            } else {
                return SeccompInstallationState.FallbackPrctlApplied
            }
        } else {
            return SeccompInstallationState.SystemCallApplied
        }
    }

    /**
     * Verifies that the seccomp filter was successfully installed and is active.
     *
     * Note: This invokes the PR_GET_SECCOMP prctl wrapped in a retry loop to robustly
     * handle signal interruptions (EINTR).
     */
    internal fun verifyInstallation(definition: PolicyDefinition<*>) {
        val currentState = ContainerState.resolveCurrentState()
        val canVerify = currentState.isSyscallAllowed(Syscall.PRCTL) &&
            definition.isSyscallAllowed(Syscall.PRCTL)

        if (!canVerify) {
            return // Cannot verify because prctl itself is restricted (now or previously)
        }

        // Verify filter is actually installed
        var verifyResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
        while (true) {
            verifyResult = LinuxNative.process.prctl(PrctlCommand.GetSeccomp)
            if (verifyResult is LinuxNative.SyscallResult.Error && verifyResult.errno == NativeConstants.EINTR) {
                continue
            }
            break
        }
        val mode = verifyResult.getOrThrow("prctl(PR_GET_SECCOMP)")
        if (mode != 2L) {
            throw IllegalStateException(
                "Seccomp filter verification failed: expected mode 2, got $mode",
            )
        }
    }
}
