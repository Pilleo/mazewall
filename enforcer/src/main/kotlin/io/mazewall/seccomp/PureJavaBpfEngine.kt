package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.ScopedBpfProgram
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.CompiledSandbox
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.state.ContainerState
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

    override val state: EngineState
        get() = when (ContainerState.resolveCurrentState().engineState) {
            is SeccompInstallationState.Uninitialized -> EngineState.UnprivilegedImpl
            is SeccompInstallationState.Verified -> EngineState.LoadedImpl
            is SeccompInstallationState.FilterBuilt,
            is SeccompInstallationState.PrivilegesLocked,
            is SeccompInstallationState.SystemCallApplied,
            is SeccompInstallationState.FallbackPrctlApplied,
            is SeccompInstallationState.Failed,
            -> EngineState.ConfiguredImpl
        }

    override val isSupported: Boolean
        get() = Platform.isSupported()

    private class LoadedEngine(
        private val delegate: PureJavaBpfEngine,
    ) : SeccompEngine<EngineState.Loaded> {
        override val state: EngineState.Loaded get() = EngineState.LoadedImpl
        override val isSupported: Boolean get() = delegate.isSupported
        override fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> = delegate.install(policy)
        override fun installOnProcess(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> = delegate.installOnProcess(policy)
    }

    override fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
        installInternal(policy, useTsync = false)
        return LoadedEngine(this)
    }

    override fun installOnProcess(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
        if (!Platform.featureMatrix.seccompTsyncSupported) {
            throw UnsupportedKernelFeatureException("Process-wide Seccomp synchronization (TSYNC) requires Linux 3.17+.")
        }
        installInternal(policy, useTsync = true)
        return LoadedEngine(this)
    }

    private fun installInternal(
        policy: CompiledSandbox<*>,
        useTsync: Boolean,
        native: NativeEngine = LinuxNative.snapshot(),
        onApplied: () -> Unit = {},
    ) {
        io.mazewall.enforcer.diagnostics.requirePlatformThread()
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

        updateState(SeccompInstallationState.Uninitialized, useTsync)
        try {
            val arch = Arch.current()
            val filters = policy.compiledFilters
            ScopedBpfProgram.withProgram(filters, native) { program ->
                val built = SeccompInstallationState.FilterBuilt(program)
                updateState(built, useTsync)
                val locked = built.lockPrivileges(native)
                updateState(locked, useTsync)
                val applied = locked.applyFilter(arch, useTsync, native)
                onApplied()
                updateState(applied, useTsync)
                val verified = applied.verify(policy.definition, native)
                updateState(verified, useTsync)
            }
        } catch (expectedInstallFailure: Exception) {
            val stepName = getStepName()
            val errno = getErrno(expectedInstallFailure)
            updateState(SeccompInstallationState.Failed(stepName, errno, expectedInstallFailure), useTsync)
            throw expectedInstallFailure
        } catch (expectedFatalFailure: Error) {
            val stepName = getStepName()
            logger.log(
                Level.SEVERE,
                "FATAL: Uncaught native or JVM error during seccomp installation at step $stepName",
                expectedFatalFailure,
            )
            val errno = getErrno(expectedFatalFailure)
            updateState(SeccompInstallationState.Failed(stepName, errno, expectedFatalFailure), useTsync)
            throw expectedFatalFailure
        }
    }

    /** Publication occurs immediately after native success, before verification can fail. */
    internal fun installWithPublication(policy: CompiledSandbox<*>, processWide: Boolean, native: NativeEngine = LinuxNative.snapshot(), onApplied: () -> Unit) {
        if (processWide && !Platform.featureMatrix.seccompTsyncSupported) {
            throw UnsupportedKernelFeatureException("Process-wide Seccomp synchronization (TSYNC) requires Linux 3.17+.")
        }
        installInternal(policy, processWide, native, onApplied)
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
        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.EngineStateUpdated(processWide = useTsync, state = next),
        )
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
    internal fun lockNoNewPrivileges(native: NativeEngine = LinuxNative.snapshot()) {
        // Step 1: Set no_new_privs (mandatory for non-root seccomp)
        while (true) {
            val result = native.process.prctl(PrctlCommand.SetNoNewPrivs(true))
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
        native: NativeEngine = LinuxNative.snapshot(),
    ): SeccompInstallationState.FilterApplied {
        val flags = if (useTsync) NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong() else 0L
        while (true) {
            val result = native.raw.syscall(
                arch.seccompSyscallNumber.toLong(),
                io.mazewall.platform.syscall.NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                io.mazewall.platform.syscall.NativeArg.LongArg(flags),
                io.mazewall.platform.syscall.NativeArg.MemoryArg(prog),
            )
            if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) {
                continue
            }
            return when (result) {
                is LinuxNative.SyscallResult.Success -> successfulSeccompInstall(result.value, useTsync)
                is LinuxNative.SyscallResult.Error -> fallbackToPrctl(prog, useTsync, result.errno, native)
            }
        }
    }

    private fun fallbackToPrctl(
        prog: ManagedSegment,
        useTsync: Boolean,
        seccompErrno: Int,
        native: NativeEngine,
    ): SeccompInstallationState.FilterApplied {
        if (useTsync) {
            throw IllegalStateException(
                "Process-wide seccomp installation (TSYNC) failed: ${tsyncFailureDetail(seccompErrno, offendingTid = null)}",
            )
        }
        while (true) {
            val result = native.process.prctl(
                    PrctlCommand.SetSeccomp(
                        NativeConstants.SECCOMP_MODE_FILTER.toLong(),
                        io.mazewall.platform.syscall.NativeArg.MemoryArg(prog)
                    )
                )
            if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) continue
            if (result is LinuxNative.SyscallResult.Error) {
                throw IllegalStateException(
                    "seccomp installation failed: seccomp(2) errno=$seccompErrno, prctl errno=${result.errno}",
                )
            }
            return SeccompInstallationState.FallbackPrctlApplied
        }
    }

    private fun successfulSeccompInstall(
        result: Long,
        useTsync: Boolean,
    ): SeccompInstallationState.FilterApplied {
        if (useTsync && result > 0L) {
            throw IllegalStateException(
                "Process-wide seccomp installation (TSYNC) failed: ${tsyncFailureDetail(errno = null, offendingTid = result)}",
            )
        }
        return SeccompInstallationState.SystemCallApplied
    }

    /**
     * `seccomp(2)`: the caller needs `CAP_SYS_ADMIN` or `no_new_privs`.
     * TSYNC fails when a sibling is in strict mode or has a divergent filter tree,
     * or when an outer profile denies nested seccomp. Sibling `no_new_privs` is not
     * the documented prerequisite.
     */
    internal fun tsyncFailureDetail(errno: Int?, offendingTid: Long?): String {
        if (offendingTid != null && offendingTid > 0L) {
            return "kernel reported offending sibling tid=$offendingTid " +
                "(strict-mode or a divergent seccomp filter tree on that thread)"
        }
        return when (errno) {
            13 ->
                "EACCES. The calling thread needs CAP_SYS_ADMIN or no_new_privs; " +
                    "or a sibling is in SECCOMP_MODE_STRICT / has a divergent filter tree; " +
                    "or an outer OCI seccomp profile denied nested seccomp(2). " +
                    "Sibling threads do not each need no_new_privs beforehand."
            22 ->
                "EINVAL. TSYNC unsupported, flags invalid, or a filter-tree mismatch " +
                    "(pre-5.7 kernels often return EINVAL for any sibling mismatch)."
            NativeConstants.EPERM ->
                "EPERM. An outer security policy denied seccomp(2) or the caller lacks privilege."
            else ->
                "errno=$errno. Not sibling no_new_privs by default; decode against seccomp(2)."
        }
    }

    /**
     * Verifies that the seccomp filter was successfully installed and is active.
     *
     * Note: This invokes the PR_GET_SECCOMP prctl wrapped in a retry loop to robustly
     * handle signal interruptions (EINTR).
     */
    internal fun verifyInstallation(definition: PolicyDefinition<*>, native: NativeEngine = LinuxNative.snapshot()) {
        val currentState = ContainerState.resolveCurrentState()
        val canVerify = currentState.isSyscallAllowed(Syscall.PRCTL) &&
            definition.isSyscallAllowed(Syscall.PRCTL)

        if (!canVerify) {
            return // Cannot verify because prctl itself is restricted (now or previously)
        }

        // Verify filter is actually installed
        var verifyResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
        while (true) {
            verifyResult = native.process.prctl(PrctlCommand.GetSeccomp)
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
