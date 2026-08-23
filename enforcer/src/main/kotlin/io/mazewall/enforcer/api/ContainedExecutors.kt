package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.InstallationAssessment
import io.mazewall.InstallationAssessor
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.PolicyScope
import io.mazewall.PolicyPresets
import io.mazewall.Uncompiled
import io.mazewall.compile
import io.mazewall.core.SandboxedPath
import io.mazewall.core.isUnderAny
import io.mazewall.core.resolveReal
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.internal.ContainedExecutorWrapper
import io.mazewall.landlock.Landlock
import io.mazewall.seccomp.PureJavaBpfEngine
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import java.util.concurrent.ExecutorService
import java.util.logging.Logger

/**
 * Public API for wrapping an existing [java.util.concurrent.ExecutorService] to enforce seccomp containment.
 *
 * ### Graceful Shutdown
 * When using wrapped executors, it is strongly recommended to use a graceful shutdown pattern:
 * ```kotlin
 * executor.shutdown()
 * if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
 *     executor.shutdownNow()
 * }
 * ```
 * Avoid using `shutdownNow()` immediately, as it interrupts threads during the critical seccomp
 * installation and handshake phases. While this implementation is robust against such interruptions
 * to prevent kernel/JVM state desync, an interrupted handshake may still leave the thread in a
 * partially initialized supervisor state.
 *
 * ARCHITECTURAL INVARIANT: Container state is maintained via immutable [ContainerState] objects,
 * which are updated atomically via [ContainmentStateRegistry]. This ensures
 * that the library has a consistent and race-free view of the active sandbox configuration
 * during concurrent or nested filter installations.
 *
 * ### Thread Safety & Global Policies
 * Modifying process-wide global security policies (e.g., via [installOnProcess]) while
 * asynchronous wrapped executors ([ContainedExecutorWrapper]) are active, executing tasks,
 * or undergoing shutdown is unsupported. Doing so can cause late-running tasks in the queue to
 * resolve an inconsistent security state because global state transitions and per-task filter
 * installations may interleave unpredictably. All global policies should be fully installed
 * before wrapping or running asynchronous task executors.
 *
 * ### Signal Mask Inheritance Warning
 * When using [wrap] or thread installations with policies that configure `SeccompAction.ACT_TRAP`, the seccomp
 * filter relies on the kernel delivering `SIGSYS` to the violating thread. Standard JVM thread pools
 * (such as [ExecutorService]) do not automatically reset POSIX signal masks (`sigprocmask`) or alternate
 * signal stacks (`sigaltstack`) when reusing threads. If a previous uncontained task executing native code (JNI/FFM)
 * blocked `SIGSYS` or corrupted the signal stack, a subsequently contained task on that same carrier thread
 * will not receive `SIGSYS` when it violates the seccomp policy, defeating `ACT_TRAP` actions.
 * Therefore, `ACT_TRAP` is unreliable in environments where native libraries might modify thread signal masks.
 * For guaranteed immediate enforcement in such environments, prefer process- or thread-killing actions like
 * `ACT_KILL_PROCESS` or `ACT_KILL_THREAD`.
 *
 * Furthermore, when a new thread is spawned by a wrapped [ExecutorService], it inherits the signal mask of its parent.
 * If the seccomp filter restricts `rt_sigprocmask`, the new thread might be permanently trapped with blocked signals.
 * To prevent unkillable threads or missed interruptions (e.g. `Thread.interrupt()` failing to wake up blocked I/O calls),
 * policies should ideally allow `rt_sigprocmask` and `rt_sigaction` for standard JVM thread management.
 * Note that `BpfFilter.getJvmCriticalNrs` explicitly and unconditionally whitelists `rt_sigprocmask`, `rt_sigaction`, and
 * `rt_sigreturn` to protect against this failure mode.
 */
// @ref: docs/internals/designs/core/security-considerations.md — Shared-memory ACE escape threat model, Tier 1/Tier 2 boundary definitions
// @ref: docs/internals/designs/enforcer/containment-design.md — Filter installation ordering (Landlock before Seccomp), TSYNC semantics
object ContainedExecutors {
    private val logger = Logger.getLogger(ContainedExecutors::class.java.name)
    private val processLock = Any()

    init {
        // Pre-load exception-related classes to avoid NoClassDefFoundError when classloading under active seccomp
        val classes = listOf(
            ContainmentViolationDetector::class.java,
            ContainmentViolationException::class.java,
            io.mazewall.Platform.FallbackBehavior::class.java,
            io.mazewall.InstallationReceipt::class.java,
            // Post-install self-verification must be resolvable BEFORE any restrictive filter
            // exists: under a jvmFloor-style policy, reading build/classes/** afterwards is
            // denied and manifests as NoClassDefFoundError (issue-20260823-172003).
            io.mazewall.seccomp.InstallSelfVerifier::class.java,
            io.mazewall.seccomp.InstallSelfVerifier.SelfVerificationException::class.java,
            io.mazewall.seccomp.BpfSimulator::class.java,
            io.mazewall.seccomp.SyscallProbeMatrix::class.java
        )
        for (c in classes) {
            try {
                Class.forName(c.name)
            } catch (e: Exception) {
                System.err.println("WARNING: Failed to preload class ${c.name} for Seccomp: ${e.message}")
            }
        }
        // Warm the self-verification transitive closure (method-level, not just Class objects):
        // lazy JVM/Kotlin machinery must be resolved BEFORE containment makes class reads
        // unreliable (issue-20260823-172003).
        try {
            io.mazewall.seccomp.InstallSelfVerifier.warmup()
        } catch (t: Throwable) {
            System.err.println("WARNING: Self-verification warmup failed: $t")
        }
    }

    /**
     * Installs the given policies onto the current thread immediately.
     *
     * @deprecated This method returns Unit for backward compatibility. Use the overload that returns
     * [io.mazewall.InstallationReceipt] for receipt and diagnostic information.
     */
    @Deprecated(
        "This variant returns Unit. Use installOnCurrentThread(vararg policies) that returns InstallationReceipt.",
        ReplaceWith("installOnCurrentThread(*policies)"),
        DeprecationLevel.HIDDEN
    )
    fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>): Unit {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        installOnCurrentThread(combined)
    }

    /**
     * Installs the given policies onto the current thread immediately.
     */
    fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>): io.mazewall.InstallationReceipt {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return installOnCurrentThread(combined)
    }

    /**
     * @deprecated This method returns Unit for backward compatibility. Use the overload that returns
     * [io.mazewall.InstallationReceipt] for receipt and diagnostic information.
     */
    @Deprecated(
        "This variant returns Unit. Use installOnCurrentThread(policy, scopingPolicy) that returns InstallationReceipt.",
        ReplaceWith("installOnCurrentThread(policy, scopingPolicy)"),
        DeprecationLevel.HIDDEN
    )
    fun installOnCurrentThread(policy: Policy<*, Uncompiled>, scopingPolicy: StacktraceScopingPolicy): Unit {
        installOnCurrentThread(policy.definition, scopingPolicy)
    }

    fun installOnCurrentThread(policy: Policy<*, Uncompiled>, scopingPolicy: StacktraceScopingPolicy): io.mazewall.InstallationReceipt {
        return installOnCurrentThread(policy.definition, scopingPolicy)
    }

    internal fun installOnCurrentThread(policy: PolicyDefinition<*>) : io.mazewall.InstallationReceipt {
        return installOnCurrentThread(policy, io.mazewall.enforcer.supervisor.DefaultStacktraceScopingPolicy)
    }

    internal fun installOnCurrentThread(policy: PolicyDefinition<*>, scopingPolicy: StacktraceScopingPolicy) : io.mazewall.InstallationReceipt {
        return installInternal(false, policy, scopingPolicy)
    }

    /** Read-only preflight. Does not install filters. */
    fun assessOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationAssessment =
        InstallationAssessor.assess(policy.definition, processWide = true)

    /** Read-only preflight for the current thread. Does not install filters. */
    fun assessOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationAssessment =
        InstallationAssessor.assess(policy.definition, processWide = false)

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     *
     * @deprecated This method returns Unit for backward compatibility. Use the overload that returns
     * [io.mazewall.InstallationReceipt] for receipt and diagnostic information.
     */
    @Deprecated(
        "This variant returns Unit. Use installOnProcess(vararg policies) that returns InstallationReceipt.",
        ReplaceWith("installOnProcess(*policies)"),
        DeprecationLevel.HIDDEN
    )
    fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): Unit {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        installInternal(true, combined)
    }

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     */
    fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): io.mazewall.InstallationReceipt {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return installInternal(true, combined)
    }

    /**
     * Wraps an [java.util.concurrent.ExecutorService] so that any task submitted to it will have the given
     * [policies] applied before execution.
     *
     * The caller still owns [delegate]. Filters stay on each worker after a task returns.
     * Do not reuse the raw delegate for unrestricted work. An owned
     * `ContainedExecutorService` is **not** planned (issue 032520 is deferred: high
     * maintenance risk around thread lifecycle vs irreversible seccomp).
     */
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ExecutorService {
        val combinedPolicy = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return ContainedExecutorWrapper(delegate, combinedPolicy)
    }

    fun wrap(
        delegate: ExecutorService,
        policy: Policy<*, Uncompiled>,
        scopingPolicy: StacktraceScopingPolicy,
    ): ExecutorService {
        return ContainedExecutorWrapper(delegate, policy.definition, scopingPolicy)
    }

    @Suppress("TooGenericExceptionCaught", "noGenericExceptionCatchingInEnforcer")
    /**
     * Concurrency model (issue-20260823-135557 resolution):
     *
     * - [ContainmentStateRegistry.threadState] is a true ThreadLocal; only the current thread ever
     *   reads or writes its own state. Therefore the gap between the Landlock critical section
     *   ([applyLandlockIfNecessary]) and the Seccomp critical section ([installSeccompFilter]) —
     *   both individually guarded by [processLock] — cannot be corrupted by concurrent
     *   thread-scoped installs on other threads: their interleaved steps touch exclusively their
     *   own ThreadLocal state.
     * - Process-wide installs serialize fully per phase on the same lock. Two process-wide installs
     *   may interleave phases (P1.landlock, P2.landlock, P2.seccomp, P1.seccomp), but both Landlock
     *   self-restriction and TSYNC seccomp are monotonic-restrictive with union semantics, so the
     *   final kernel state is order-independent; [io.mazewall.enforcer.state.ContainmentStateRegistry]
     *   process state is updated atomically per phase.
     * - Catch-block rollback restores only this thread's state from the pre-install snapshot,
     *   which stays valid because no other thread can write it. The
     *   `landlockSuccessfullyApplied` guard prevents reverting past irreversible Landlock changes.
     * - Merging the two phases into one critical section was evaluated and rejected: Java monitors
     *   are reentrant (no self-deadlock either way), and the worst-case lock-hold time is already
     *   bounded by the supervised-filter handshake that runs inside the lock today. The daemon
     *   spawn ([io.mazewall.enforcer.supervisor.SupervisorDaemonManager.getOrSpawnSharedDaemon])
     *   deliberately happens before any locking.
     */
    private fun installInternal(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy = io.mazewall.enforcer.supervisor.DefaultStacktraceScopingPolicy
    ) : io.mazewall.InstallationReceipt {
        val initialState = if (processWide) null else ContainmentStateRegistry.threadState
        var landlockSuccessfullyApplied = false
        try {
            val augmentedPolicy = if (scopingPolicy.handlers.isNotEmpty()) {
                val overriddenActions = policy.syscallActions.toMutableMap()
                for (sys in scopingPolicy.handlers.keys) {
                    overriddenActions[sys] = SeccompAction.ACT_NOTIFY
                }
                policy.copy(syscallActions = overriddenActions)
            } else {
                policy
            }

            if (!Platform.isSupported()) {
                handleUnsupportedPlatform()
                return io.mazewall.InstallationReceipt(
                    processWide = processWide,
                    requestedPolicy = policy,
                    installed = false,
                )
            }

            validateLinuxAndNotVirtual()

            if (augmentedPolicy.hasSupervisedSyscalls) {
                io.mazewall.enforcer.supervisor.SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()
            }

            if (augmentedPolicy.lockIntelCet) {
                armIntelCet()
            }

            when (val landlock = applyLandlockIfNecessary(processWide, augmentedPolicy)) {
                LandlockStep.APPLIED -> landlockSuccessfullyApplied = true
                LandlockStep.BYPASSED -> {
                    return io.mazewall.InstallationReceipt(
                        processWide = processWide,
                        requestedPolicy = policy,
                        installed = false,
                    )
                }
                LandlockStep.UNCHANGED -> {
                    val activeState = if (processWide) {
                        ContainmentStateRegistry.processState
                    } else {
                        ContainmentStateRegistry.threadState
                    }
                    landlockSuccessfullyApplied = activeState.landlockPolicy != null
                }
            }

            return installSeccompFilter(processWide, augmentedPolicy, scopingPolicy, landlockSuccessfullyApplied)
        } catch (t: Throwable) {
            // Landlock is irreversible in the kernel. Only revert thread-local seccomp state
            // if Landlock was NOT applied during this installation attempt.
            if (!processWide && initialState != null && !landlockSuccessfullyApplied) {
                ContainmentStateRegistry.threadState = initialState
            }
            val fallback = Platform.configuredFallback()
            val landlockInForce =
                landlockSuccessfullyApplied ||
                    (if (processWide) {
                        ContainmentStateRegistry.processState
                    } else {
                        ContainmentStateRegistry.threadState
                    }).landlockPolicy != null
            if (fallback != Platform.FallbackBehavior.FAIL) {
                if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
                    if (landlockInForce) {
                        logger.warning(
                            "Seccomp installation failed after Landlock applied: ${t.message}. " +
                                "Filesystem Landlock remains in force; seccomp did not install.",
                        )
                    } else {
                        logger.warning("Seccomp installation failed: ${t.message}. Code will run uncontained.")
                    }
                }
                return io.mazewall.InstallationReceipt(
                    processWide = processWide,
                    requestedPolicy = policy,
                    installed = false,
                    landlockApplied = landlockInForce,
                )
            }
            throw t
        }
    }

    private fun installSeccompFilter(
        processWide: Boolean,
        combinedPolicy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        landlockApplied: Boolean,
    ) : io.mazewall.InstallationReceipt {
        // FAST PATH: Check if the current thread state already satisfies the policy without locking
        val fastState = resolveCurrentState()
        val fastPlan = FilterInstallationPlanner.calculateNewFilter(combinedPolicy, fastState)
        if (!fastPlan.needsNewFilter && (!combinedPolicy.hasSupervisedSyscalls || processWide)) {
            return io.mazewall.InstallationReceipt(
                processWide = processWide,
                requestedPolicy = combinedPolicy,
                supervisorSession = null,
                landlockApplied = landlockApplied,
            )
        }

        synchronized(processLock) {
            val state = resolveCurrentState()
            val plan = FilterInstallationPlanner.calculateNewFilter(combinedPolicy, state)

            val session = if (plan.needsNewFilter) {
                FilterInstallationPlanner.verifyFilterDepth(state.filterDepth)
                applyBpfFilter(processWide, plan.toInstall, plan.newBlocks, plan.newDefaultAction, scopingPolicy)
            } else {
                null
            }

            if (!processWide && combinedPolicy.hasSupervisedSyscalls) {
                if (session is io.mazewall.enforcer.supervisor.SupervisorSession) {
                    return io.mazewall.InstallationReceipt(
                        processWide = processWide,
                        requestedPolicy = combinedPolicy,
                        supervisorSession = session,
                        landlockApplied = landlockApplied,
                    )
                } else {
                    val tid = io.mazewall.LinuxNative.process.gettid()
                    io.mazewall.enforcer.supervisor.SupervisorInstaller.registerThread(tid)
                    return io.mazewall.InstallationReceipt(
                        processWide = processWide,
                        requestedPolicy = combinedPolicy,
                        supervisorSession = io.mazewall.enforcer.supervisor.SupervisorSession(tid),
                        landlockApplied = landlockApplied,
                    )
                }
            }
            return io.mazewall.InstallationReceipt(
                processWide = processWide,
                requestedPolicy = combinedPolicy,
                supervisorSession = null,
                landlockApplied = landlockApplied,
            )
        }
    }

    private enum class LandlockStep { UNCHANGED, APPLIED, BYPASSED }

    private fun applyLandlockIfNecessary(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
    ): LandlockStep {
        if (!needsLandlock(policy)) return LandlockStep.UNCHANGED

        synchronized(processLock) {
            val state = if (processWide) ContainmentStateRegistry.processState else ContainmentStateRegistry.threadState
            val landlockPolicy = state.landlockPolicy

            if (landlockPolicy != null) {
                // Assert that we are not trying to expand Landlock filesystem permissions on nested containment.
                // Comparison is realpath-resolved on both sides: Landlock rules bind to dentries, so
                // syntactically-distinct-but-physically-equal paths must compare equal, and any
                // unresolvable operand fails the subset check conservatively (fail closed).
                val readsSubset = isPathSubset(landlockPolicy.allowedFsReadPaths, policy.allowedFsReadPaths)
                val writesSubset = isPathSubset(landlockPolicy.allowedFsWritePaths, policy.allowedFsWritePaths)
                if (!readsSubset || !writesSubset) {
                    throw IllegalStateException("Cannot expand Landlock filesystem permissions on an already restricted thread.")
                }
            }

            val isDifferent = landlockPolicy == null ||
                landlockPolicy.allowedFsReadPaths != policy.allowedFsReadPaths ||
                landlockPolicy.allowedFsWritePaths != policy.allowedFsWritePaths

            if (isDifferent) {
                when (val applied = Landlock.tryApplyRuleset(policy, processWide)) {
                    is io.mazewall.landlock.LandlockApplyResult.Applied -> {
                        if (processWide) {
                            ContainmentStateRegistry.updateProcessState { it.withLandlockPolicy(policy) }
                        } else {
                            ContainmentStateRegistry.threadState =
                                ContainmentStateRegistry.threadState.withLandlockPolicy(policy)
                        }
                        return LandlockStep.APPLIED
                    }
                    is io.mazewall.landlock.LandlockApplyResult.Bypassed -> return LandlockStep.BYPASSED
                    is io.mazewall.landlock.LandlockApplyResult.Rejected -> applied.orThrow()
                }
            }
            return LandlockStep.UNCHANGED
        }
    }

    /**
     * Landlock is required if the policy enforces Landlock (regardless of whether paths are empty).
     * Note: We no longer implicitly trigger Landlock for io_uring_setup bypass prevention here.
     * Instead, if Landlock is not enforced (empty paths) and a policy restricts open/openat but allows io_uring_setup,
     * PolicyBuilder/PolicyDefinition automatically blocks io_uring_setup in seccomp.
     */
    private fun needsLandlock(policy: PolicyDefinition<*>) = policy.enforceLandlock

    /**
     * True when every [childPaths] entry lies beneath some [parentPaths] entry.
     *
     * Uses the canonical [io.mazewall.core.isUnder] containment predicate with realpath resolution
     * on both sides (see [io.mazewall.core.resolveReal]): Landlock binds rules to dentries, so a
     * symlinked spelling of an already-allowed directory must compare equal, while an operand that
     * cannot be resolved compares only by its syntactic value — two different spellings that fail
     * resolution identically still match, and anything else is rejected (fail closed).
     */
    private fun isPathSubset(
        parentPaths: Set<SandboxedPath>,
        childPaths: Set<SandboxedPath>,
    ): Boolean {
        if (childPaths.isEmpty()) return true
        val resolvedParents = parentPaths.map { it.resolveReal() }.toSet()
        return childPaths.all { child -> child.resolveReal().isUnderAny(resolvedParents) }
    }

    private fun handleUnsupportedPlatform() {
        val fallback = Platform.configuredFallback()
        when (fallback) {
            Platform.FallbackBehavior.FAIL ->
                throw UnsupportedOperationException("Platform does not support seccomp")

            Platform.FallbackBehavior.WARN_AND_BYPASS ->
                logger.warning("Platform does not support seccomp. Code will run uncontained.")

            Platform.FallbackBehavior.SILENT_BYPASS -> {}
        }
    }

    private fun armIntelCet() {
        if (!Platform.isCetPlatformEligible || !Platform.featureMatrix.cetSupported) {
            handleCetUnsupported("Intel CET is requested but the current platform/architecture/CPU does not support it.")
            return
        }

        // 1. Query current status to prevent redundant enabling
        val initialStatus = Platform.queryIntelCetStatus()
        val isAlreadyEnabled = (initialStatus and io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK) != 0L

        if (!isAlreadyEnabled) {
            // Enable Shadow Stack: arch_prctl(ARCH_SHSTK_ENABLE, ARCH_SHSTK_SHSTK)
            val enableRes = io.mazewall.LinuxNative.process.archPrctl(
                io.mazewall.ffi.NativeConstants.ARCH_SHSTK_ENABLE,
                io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK
            )

            if (enableRes is io.mazewall.LinuxNative.SyscallResult.Error) {
                handleCetUnsupported("Failed to enable Intel CET Shadow Stack: ${enableRes.toString()}")
                return
            }
        }

        // 2. Lock Shadow Stack configuration: arch_prctl(ARCH_SHSTK_LOCK, ARCH_SHSTK_SHSTK)
        val lockRes = io.mazewall.LinuxNative.process.archPrctl(
            io.mazewall.ffi.NativeConstants.ARCH_SHSTK_LOCK,
            io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK
        )

        if (lockRes is io.mazewall.LinuxNative.SyscallResult.Error) {
            // EPERM (1) is returned if CET is already locked. If verification is successful, we can ignore this.
            if (lockRes.errno != io.mazewall.ffi.NativeConstants.EPERM) {
                handleCetUnsupported("Failed to lock Intel CET Shadow Stack: ${lockRes.toString()}")
                return
            }
        }

        // 3. Verify status to be absolutely sure:
        val activeStatus = Platform.queryIntelCetStatus()
        if ((activeStatus and io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK) == 0L) {
            handleCetUnsupported("Intel CET is enabled and locked, but verification of status failed.")
            return
        }

        logger.info("Intel CET Shadow Stack successfully enabled and locked.")
    }

    private fun handleCetUnsupported(reason: String) {
        val fallback = Platform.configuredFallback()
        if (fallback == Platform.FallbackBehavior.FAIL) {
            throw io.mazewall.UnsupportedPlatformException(reason)
        } else if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
            logger.warning("$reason Code will run without CET protection.")
        }
    }

    private fun resolveCurrentState(): ContainerState = ContainerState.resolveCurrentState()

    private fun applyBpfFilter(
        processWide: Boolean,
        toInstall: PolicyDefinition<*>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        scopingPolicy: StacktraceScopingPolicy
    ): AutoCloseable {
        val arch = io.mazewall.core.Arch.current()
        if (toInstall.hasSupervisedSyscalls) {
            if (processWide) {
                throw UnsupportedOperationException("Process-wide supervised filters are not supported. Use thread-scoped supervision instead.")
            }
            val onApplied = { updateThreadState(newBlocks, newDefaultAction, toInstall) }
            val session = io.mazewall.enforcer.supervisor.SupervisorInstaller.installSupervisedFilterForThread(
                toInstall,
                scopingPolicy,
                onApplied
            )
            return session
        } else {
            val compiledSandbox = io.mazewall.PolicyCompilationCache.getOrCompile(toInstall, arch)
            if (processWide) {
                PureJavaBpfEngine.installOnProcess(compiledSandbox)
                updateProcessState(newBlocks, newDefaultAction, toInstall)
            } else {
                PureJavaBpfEngine.install(compiledSandbox)
                updateThreadState(newBlocks, newDefaultAction, toInstall)
            }
            // Runtime self-verification (issue-20260823-172003): OPT-IN via
            // -Dio.mazewall.selfVerify=true. Asserts the kernel honors the oracle's predictions;
            // memoized per program identity. See InstallSelfVerifier gate KDoc for why the
            // default is off under narrow allow-list floors.
            io.mazewall.seccomp.InstallSelfVerifier.verify(compiledSandbox.program, arch)
            return AutoCloseable {}
        }
    }

    private fun updateProcessState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: PolicyDefinition<*>,
    ) {
        ContainmentStateRegistry.updateProcessState { current ->
            current.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
        }
    }

    private fun updateThreadState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: PolicyDefinition<*>,
    ) {
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
    }
}
