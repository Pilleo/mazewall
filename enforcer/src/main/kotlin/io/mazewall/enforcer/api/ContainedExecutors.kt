package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.InstallationAssessment
import io.mazewall.enforcer.api.InstallationAssessor
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyPresets
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.compile
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.internal.ContainedExecutorWrapper
import io.mazewall.enforcer.state.*
import io.mazewall.landlock.Landlock
import io.mazewall.landlock.toFailure
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.lifecycle.isUnderAny
import io.mazewall.platform.lifecycle.resolveReal
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.PureJavaBpfEngine
import java.util.concurrent.ExecutorService
import java.util.logging.Logger

// @ref: docs/internals/designs/core/security-considerations.md — Shared-memory ACE escape threat model, Tier 1/Tier 2 boundary definitions
// @ref: docs/internals/designs/enforcer/containment-design.md — Filter installation ordering (Landlock before Seccomp), TSYNC semantics

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
public object ContainedExecutors {
    private val logger = Logger.getLogger(ContainedExecutors::class.java.name)
    private val processLock = Any()

    init {
        // Pre-load exception-related classes to avoid NoClassDefFoundError when classloading under active seccomp
        val classes = listOf(
            ContainmentViolationDetector::class.java,
            ContainmentViolationException::class.java,
            io.mazewall.enforcer.api.Platform.FallbackBehavior::class.java,
            io.mazewall.enforcer.api.InstallationReceipt::class.java,
            // Receipt deltas and partial-failure reporting must resolve before class reads can be denied.
            InstallationProgress::class.java,
            PartialInstallationException::class.java,
            ContainmentRegistryEffect.InstallationFailed::class.java,
            ContainmentRegistryEffect.CetLocked::class.java,
            InstallationEvidence::class.java,
            CetThreadProof::class.java,
            CetOutcome::class.java,
            // Post-install self-verification must be resolvable BEFORE any restrictive filter
            // exists: under a jvmFloor-style policy, reading build/classes/** afterwards is
            // denied and manifests as NoClassDefFoundError (issue-20260823-172003).
            io.mazewall.seccomp.InstallSelfVerifier::class.java,
            io.mazewall.seccomp.InstallSelfVerifier.SelfVerificationException::class.java,
            io.mazewall.seccomp.BpfSimulator::class.java,
            io.mazewall.seccomp.SyscallProbeMatrix::class.java,
            // Self-verification result emission touches the diagnostics SPI post-install.
            io.mazewall.enforcer.diagnostics.MazewallEvents::class.java,
            io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult::class.java,
        )
        for (c in classes) {
            try {
                Class.forName(c.name)
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("Failed to preload ${c.name} before Seccomp installation", e)
            } catch (e: LinkageError) {
                throw IllegalStateException("Failed to link ${c.name} before Seccomp installation", e)
            }
        }
        // Warm the self-verification transitive closure (method-level, not just Class objects):
        // lazy JVM/Kotlin machinery must be resolved BEFORE containment makes class reads
        // unreliable (issue-20260823-172003).
        try {
            io.mazewall.seccomp.InstallSelfVerifier
                .warmup()
        } catch (e: IllegalStateException) {
            throw IllegalStateException("Failed to warm self-verification before Seccomp installation", e)
        }
    }

    /**
     * Installs the given policies onto the current thread immediately.
     */
    public fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>): io.mazewall.enforcer.api.InstallationReceipt {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return installOnCurrentThread(combined)
    }

    public fun installOnCurrentThread(
        policy: Policy<*, Uncompiled>,
        scopingPolicy: StacktraceScopingPolicy,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        return installOnCurrentThread(policy.definition, scopingPolicy)
    }

    internal fun installOnCurrentThread(policy: PolicyDefinition<*>): io.mazewall.enforcer.api.InstallationReceipt {
        return installOnCurrentThread(policy, io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy)
    }

    internal fun installOnCurrentThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        environment: InstallationEnvironment = InstallationEnvironment.capture(),
        preparedLandlock: io.mazewall.landlock.PreparedLandlock? = null,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        return installInternal(false, policy, scopingPolicy, environment, preparedLandlock)
    }

    /** Read-only preflight. Does not install filters. */
    public fun assessOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationAssessment = InstallationAssessor.assess(policy.definition, processWide = true)

    /** Read-only preflight for the current thread. Does not install filters. */
    public fun assessOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationAssessment = InstallationAssessor.assess(policy.definition, processWide = false)

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     */
    public fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): io.mazewall.enforcer.api.InstallationReceipt {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return installInternal(true, combined)
    }

    internal fun installOnProcess(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        environment: InstallationEnvironment,
    ): InstallationReceipt = installInternal(true, policy, environment = environment)

    /**
     * Installs a permanent baseline in a dedicated child process, including existing JVM threads.
     * Unlike the general process API, this internal entry accepts filesystem policies only when
     * Landlock TSYNC is available (ABI 8+). The child must not publish readiness on failure;
     * successful stages remain irreversible and are recorded by the ordinary installer.
     */
    @io.mazewall.platform.lifecycle.MazewallInternal
    // SUPPRESSION-RATIONALE: One defensive vararg composition precedes the irreversible child baseline; preserving the existing PolicyDefinition API avoids semantic changes.
    @Suppress("SpreadOperator")
    public fun installOnDedicatedProcess(vararg policies: Policy<*, Uncompiled>): io.mazewall.enforcer.api.InstallationReceipt {
        io.mazewall.enforcer.diagnostics
            .requirePlatformThread()
        requireNonQuarantinedScope()
        val environment = InstallationEnvironment.capture()
        check(environment.platform.isLinux) { "Mazewall requires Linux for kernel-level containment." }
        check(environment.fallback == Platform.FallbackBehavior.FAIL) { "Dedicated process containment forbids fallback" }
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        check(!combined.enforceLandlock || environment.platform.capabilities.matrix.landlockTsyncSupported) {
            "Dedicated process filesystem containment requires Landlock TSYNC (ABI 8+)"
        }
        val receipt = installInternal(true, combined, environment = environment).requireInstalled()
        check(!combined.enforceLandlock || receipt.landlockApplied) { "Dedicated process Landlock was not applied" }
        return receipt
    }

    /**
     * Wraps an [java.util.concurrent.ExecutorService] so each submitted task installs
     * [policies] on **that worker thread** before it runs.
     *
     * This is thread-scoped containment for trusted code handling untrusted data.
     * It is not an ACE boundary: sibling threads and `CompletableFuture` hops are
     * unfiltered. Untrusted Java belongs in [installOnProcess] or a portal worker.
     *
     * The caller still owns [delegate]. Filters stay on each worker after a task returns.
     * Do not reuse the raw delegate for unrestricted work.
     * Every task requires a completed installation receipt before its body enters;
     * configured manual-install bypass never authorizes a wrapped task to run uncontained.
     */
    public fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ContainedExecutor<PolicyScope.ThreadLocalOnly> {
        val combinedPolicy = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return ContainedExecutorView(ContainedExecutorWrapper(delegate, combinedPolicy), combinedPolicy.asThreadLocalPolicy())
    }

    /**
     * Returns a typed containment view for components that require an explicit
     * execution scope. The delegate lifecycle remains owned by the caller.
     */
    public fun <S : PolicyScope> wrapTyped(
        delegate: ExecutorService,
        policy: Policy<S, Uncompiled>,
    ): ContainedExecutor<S> = ContainedExecutorView(ContainedExecutorWrapper(delegate, policy.definition), policy)

    private fun PolicyDefinition<*>.asThreadLocalPolicy(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = Policy(this as PolicyDefinition<PolicyScope.ThreadLocalOnly>)

    private fun containedThreadFactory(
        tag: String,
        policy: Policy<*, Uncompiled>,
    ): java.util.concurrent.ThreadFactory {
        val name = "mazewall-contained-$tag-${policy.definition.hashCode().toUInt().toString(16)}"
        return java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                setName(name)
            }
        }
    }

    /** Daemon single-thread executor whose threads run under [policy] on wrap. */
    public fun newSingleThreadExecutor(policy: Policy<*, Uncompiled>): ExecutorService =
        wrap(
        java.util.concurrent.Executors
        .newSingleThreadExecutor(containedThreadFactory("single", policy)),
            policy,
    )

    /** Daemon fixed pool whose threads run under [policy] on wrap. */
    public fun newFixedThreadPool(
        nThreads: Int,
        policy: Policy<*, Uncompiled>,
    ): ExecutorService =
        wrap(
        java.util.concurrent.Executors
        .newFixedThreadPool(nThreads, containedThreadFactory("pool", policy)),
            policy,
    )

    /** Daemon cached pool whose threads run under [policy] on wrap. */
    public fun newCachedThreadPool(policy: Policy<*, Uncompiled>): ExecutorService =
        wrap(
        java.util.concurrent.Executors
        .newCachedThreadPool(containedThreadFactory("cached", policy)),
            policy,
    )

    public fun wrap(
        delegate: ExecutorService,
        policy: Policy<*, Uncompiled>,
        scopingPolicy: StacktraceScopingPolicy,
    ): ContainedExecutor<PolicyScope.ThreadLocalOnly> {
        return ContainedExecutorView(
            ContainedExecutorWrapper(delegate, policy.definition, scopingPolicy),
            policy.definition.asThreadLocalPolicy(),
        )
    }

    /**
     * Concurrency model (issue-20260823-135557 resolution):
     *
     * - [ContainmentStateRegistry.threadState] is a ThreadLocal. Supervised installation captures
     *   its holder before the native call so the uncontained coordinator can publish that stage
     *   before releasing the installer's handshake latch. The gap between the Landlock critical section
     *   ([applyLandlockIfNecessary]) and the Seccomp critical section ([installSeccompFilter]) —
     *   both individually guarded by [processLock] — cannot be corrupted by concurrent
     *   thread-scoped installs on other threads: their interleaved steps touch exclusively their
     *   own ThreadLocal state.
     * - Process-wide installs serialize fully per phase on the same lock. Two process-wide installs
     *   may interleave phases (P1.landlock, P2.landlock, P2.seccomp, P1.seccomp), but both Landlock
     *   self-restriction and TSYNC seccomp are monotonic-restrictive with union semantics, so the
     *   final kernel state is order-independent; [io.mazewall.enforcer.state.ContainmentStateRegistry]
     *   process state is updated atomically per phase.
     * - Native stages publish their successful irreversible changes before verification or
     *   diagnostics. Failures expose partial progress; neither this installer nor its wrappers
     *   rewind the registry to a pre-install snapshot.
     * - Merging the two phases into one critical section was evaluated and rejected: Java monitors
     *   are reentrant (no self-deadlock either way), and the worst-case lock-hold time is already
     *   bounded by the supervised-filter handshake that runs inside the lock today. The daemon
     *   spawn ([io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager.getOrSpawnSharedDaemon])
     *   deliberately happens before any locking.
     */
    @Suppress("TooGenericExceptionCaught") // installation boundary preserves fatal identity and records irreversible progress
    private fun installInternal(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy = io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
        environment: InstallationEnvironment = InstallationEnvironment.capture(),
        preparedLandlock: io.mazewall.landlock.PreparedLandlock? = null,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        io.mazewall.enforcer.diagnostics
            .requirePlatformThread()
        requireNonQuarantinedScope()
        // A failed permanent listener cannot be repaired by policy planning or operator bypass.
        io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
            .requireReusableInstallations()
        val attempt = InstallationAttempt(processWide, environment, preparedLandlock)
        if (policy.lockIntelCet) attempt.cetOutcome = CetOutcome.UNCONFIRMED
        try {
            check(!processWide || !policy.lockIntelCet) { "Process-wide shadow-stack locking is unsupported; CET controls apply to the installing platform thread" }
            val augmentedPolicy = augmentForScoping(policy, scopingPolicy)
            unsupportedPlatformReceipt(processWide, policy, attempt)?.let { return recordInstallationResult(it) }

            check(attempt.platform.isLinux) { "Mazewall requires Linux for kernel-level containment." }
            preparePreSeccompEffects(augmentedPolicy, attempt)
            return recordInstallationResult(installLandlockThenSeccomp(processWide, policy, augmentedPolicy, scopingPolicy, attempt))
        } catch (fatal: Error) {
            fatal.addSuppressed(PartialInstallationException(attempt.snapshot()))
            ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(processWide, fatal))
            throw fatal
        } catch (expectedInstallFailure: Throwable) {
            // Installed stages are irreversible. Only their installer publishes state;
            // callers and wrappers never restore a pre-install snapshot.
            val fallback = attempt.fallback
            val progress = attempt.snapshot()
            val failure = if (progress.landlockApplied || progress.seccompApplied || progress.cetLocked) {
                PartialInstallationException(progress, expectedInstallFailure)
            } else {
                expectedInstallFailure
            }
            ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(processWide, failure))
            val landlockInForce =
                attempt.landlockApplied ||
                    (
                        if (processWide) {
                        ContainmentStateRegistry.processState
                    } else {
                        ContainmentStateRegistry.threadState
                    }
                    ).landlockPolicy != null
            if (fallback != Platform.FallbackBehavior.FAIL) {
                if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
                    if (progress.seccompApplied) {
                        logger.warning(
                            "Installation failed after Seccomp applied: ${expectedInstallFailure.message}. " +
                                "The filter remains installed; completed stages=$progress.",
                        )
                    } else if (landlockInForce) {
                        logger.warning(
                            "Seccomp installation failed after Landlock applied: ${expectedInstallFailure.message}. " +
                                "Filesystem Landlock remains in force; seccomp did not install.",
                        )
                    } else {
                        logger.warning("Seccomp installation failed: ${expectedInstallFailure.message}. The receipt reports bypass; required execution will reject it.")
                    }
                }
                return io.mazewall.enforcer.api.InstallationReceipt(
                    processWide = processWide,
                    requestedPolicy = policy,
                    installed = false,
                    landlockApplied = landlockInForce,
                    fallback = fallback,
                    progress = progress,
                    failure = failure,
                    cetOutcome = attempt.cetOutcome,
                )
            }
            throw failure
        }
    }

    private fun requireNonQuarantinedScope() {
        ContainerState.resolveCurrentState().installationFailure?.let { throw it }
    }

    private fun recordInstallationResult(receipt: InstallationReceipt): InstallationReceipt {
        if (!receipt.installed) {
            val failure = receipt.failure ?: IllegalStateException("Required containment installation did not complete")
            ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(receipt.processWide, failure))
        }
        return receipt
    }

    private fun augmentForScoping(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
    ): PolicyDefinition<*> =
        policy.copy(
            syscallActions = policy.syscallActions + scopingPolicy.handlers.keys.associateWith { SeccompAction.ACT_NOTIFY },
        )

    private fun unsupportedPlatformReceipt(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt? {
        if (attempt.platform.isSupported()) return null
        handleUnsupportedPlatform(attempt.fallback)
        return io.mazewall.enforcer.api
            .InstallationReceipt(
                processWide = processWide,
                requestedPolicy = policy,
                installed = false,
                fallback = attempt.fallback,
                progress = attempt.snapshot(),
                failure = UnsupportedOperationException("Platform does not support seccomp"),
                cetOutcome = attempt.cetOutcome,
            )
    }

    private fun preparePreSeccompEffects(
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
    ) {
        if (policy.lockIntelCet) armIntelCet(attempt)
        if (policy.hasSupervisedSyscalls) {
            io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
                .getInstance()
                .getOrSpawnSharedDaemon(attempt.native)
        }
    }

    private fun installLandlockThenSeccomp(
        processWide: Boolean,
        requestedPolicy: PolicyDefinition<*>,
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        val landlockApplied = when (applyLandlockIfNecessary(processWide, policy, attempt)) {
            LandlockStep.APPLIED -> true
            LandlockStep.BYPASSED ->
                return io.mazewall.enforcer.api.InstallationReceipt(
                    processWide = processWide,
                    requestedPolicy = requestedPolicy,
                    installed = false,
                    fallback = attempt.fallback,
                    progress = attempt.snapshot(),
                    failure = attempt.failure,
                    cetOutcome = attempt.cetOutcome,
                )
            LandlockStep.UNCHANGED -> (if (processWide) ContainmentStateRegistry.processState else ContainmentStateRegistry.threadState).landlockPolicy != null
        }
        return installSeccompFilter(processWide, policy, scopingPolicy, landlockApplied, attempt)
    }

    private fun installSeccompFilter(
        processWide: Boolean,
        combinedPolicy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        landlockApplied: Boolean,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        // FAST PATH: Check if the current thread state already satisfies the policy without locking
        val fastState = installationState(processWide)
        val fastPlan = FilterInstallationPlanner.calculateNewFilter(combinedPolicy, fastState)
        if (!fastPlan.needsNewFilter && (!combinedPolicy.hasSupervisedSyscalls || processWide)) {
            return io.mazewall.enforcer.api.InstallationReceipt(
                processWide = processWide,
                requestedPolicy = combinedPolicy,
                landlockApplied = landlockApplied,
                fallback = attempt.fallback,
                progress = attempt.snapshot(),
                cetOutcome = attempt.cetOutcome,
            )
        }

        synchronized(processLock) {
            val state = installationState(processWide)
            val plan = FilterInstallationPlanner.calculateNewFilter(combinedPolicy, state)

            val session = if (plan.needsNewFilter) {
                // Kernel depth belongs to this thread's complete filter tree, even
                // though process policy coverage is planned from processState only.
                FilterInstallationPlanner.verifyFilterDepth(ContainerState.resolveCurrentState().filterDepth)
                applyBpfFilter(processWide, plan.toInstall, plan.newBlocks, plan.newDefaultAction, scopingPolicy, attempt)
            } else {
                null
            }

            if (!processWide && combinedPolicy.hasSupervisedSyscalls) {
                if (session is io.mazewall.enforcer.internal.supervisor.SupervisorSession) {
                    return io.mazewall.enforcer.api.InstallationReceipt.withSupervisorRegistration(
                        processWide = processWide,
                        requestedPolicy = combinedPolicy,
                        supervisorRegistration = session,
                        landlockApplied = landlockApplied,
                        fallback = attempt.fallback,
                        progress = attempt.snapshot(),
                        cetOutcome = attempt.cetOutcome,
                    )
                } else {
                    return io.mazewall.enforcer.api.InstallationReceipt.withSupervisorRegistration(
                        processWide = processWide,
                        requestedPolicy = combinedPolicy,
                        supervisorRegistration = io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
                            .acquireTaskSession(scopingPolicy, attempt.native),
                        landlockApplied = landlockApplied,
                        fallback = attempt.fallback,
                        progress = attempt.snapshot(),
                        cetOutcome = attempt.cetOutcome,
                    )
                }
            }
            return io.mazewall.enforcer.api.InstallationReceipt(
                processWide = processWide,
                requestedPolicy = combinedPolicy,
                landlockApplied = landlockApplied,
                fallback = attempt.fallback,
                progress = attempt.snapshot(),
                cetOutcome = attempt.cetOutcome,
            )
        }
    }

    private enum class LandlockStep { UNCHANGED, APPLIED, BYPASSED }

    private fun applyLandlockIfNecessary(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
    ): LandlockStep {
        if (!policy.enforceLandlock) return LandlockStep.UNCHANGED

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
                val publish = ContainmentRegistryEffectInterpreter.prepare(
                    ContainmentRegistryEffect.LandlockApplied(processWide, policy),
                )
                val capabilities = attempt.platform.capabilities
                val onApplied = {
                    attempt.landlockApplied = true
                    publish()
                }
                val applied = attempt.preparedLandlock?.apply(onApplied)
                    ?: Landlock.tryApplyRuleset(policy, processWide, fallback = attempt.fallback, native = attempt.native, capabilities = capabilities, onRestrictionApplied = onApplied)
                when (applied) {
                    is io.mazewall.landlock.LandlockApplyResult.Applied -> {
                        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
                            io.mazewall.enforcer.diagnostics.MazewallEvents.LandlockApplied(
                                processWide = processWide,
                                abiVersion = capabilities.matrix.landlockAbiVersion,
                            ),
                        )
                        return LandlockStep.APPLIED
                    }
                    is io.mazewall.landlock.LandlockApplyResult.Bypassed -> {
                        attempt.failure = applied.toFailure()
                        return LandlockStep.BYPASSED
                    }
                    is io.mazewall.landlock.LandlockApplyResult.Rejected -> applied.orThrow()
                }
            }
            return LandlockStep.UNCHANGED
        }
    }

    private fun handleUnsupportedPlatform(fallback: Platform.FallbackBehavior) {
        when (fallback) {
            Platform.FallbackBehavior.FAIL ->
                throw UnsupportedOperationException("Platform does not support seccomp")

            Platform.FallbackBehavior.WARN_AND_BYPASS -> {
                logger.warning("Platform does not support seccomp. The receipt reports bypass; required execution will reject it.")
                io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
                    io.mazewall.enforcer.diagnostics.MazewallEvents.FallbackEngaged(
                        behaviorName = fallback.name,
                        reason = "Platform does not support seccomp",
                    ),
                )
            }

            Platform.FallbackBehavior.SILENT_BYPASS -> {}
        }
    }

    private fun armIntelCet(attempt: InstallationAttempt) {
        val native = attempt.native
        val owner = Thread.currentThread()
        val prior = ContainmentStateRegistry.threadState.installationEvidence.cet
        if (prior?.owner === owner && prior.nativeIdentity === native.engineIdentity) {
            attempt.cetOutcome = CetOutcome.VERIFIED_CURRENT_THREAD
            return
        }
            requireEnabledShadowStack(attempt, "before lock")
            val lock = native.process.archPrctl(io.mazewall.ffi.NativeConstants.ARCH_SHSTK_LOCK, io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK)
            check(lock is LinuxNative.SyscallResult.Success && lock.value == 0L) {
                when (lock) {
                    is LinuxNative.SyscallResult.Error -> "ARCH_SHSTK_LOCK failed with errno=${lock.errno}"
                    is LinuxNative.SyscallResult.Success -> "ARCH_SHSTK_LOCK returned unexpected success value ${lock.value}"
                }
            }
            // Successful LOCK is irreversible even if later status/diagnostic work fails.
            attempt.cetLocked = true
            requireEnabledShadowStack(attempt, "after lock")
            ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.CetLocked(CetThreadProof(native.engineIdentity, owner)))
            attempt.cetOutcome = CetOutcome.VERIFIED_CURRENT_THREAD
            io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
                io.mazewall.enforcer.diagnostics.MazewallEvents.CetOutcome(
                    armed = true,
                    detail = "pre-enabled SHSTK locked and verified on the installing platform thread",
                ),
            )
    }

    private fun requireEnabledShadowStack(
        attempt: InstallationAttempt,
        phase: String,
    ) {
        val status = Platform.probeShstkStatus(attempt.native, attempt.platform.isCetEligible)
        if (status !is KernelCapability.Available) throw Platform.cetStatusFailure(status)
        check((status.value and io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK) != 0L) {
            "Shadow stack is inactive $phase. A compatible runtime/loader must enable SHSTK before library entry; late FFM enablement is unsupported."
        }
    }

    private fun applyBpfFilter(
        processWide: Boolean,
        toInstall: PolicyDefinition<*>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        scopingPolicy: StacktraceScopingPolicy,
        attempt: InstallationAttempt,
    ): AutoCloseable {
        val arch = io.mazewall.platform.syscall.Arch
            .current()
        if (toInstall.hasSupervisedSyscalls) {
            if (processWide) {
                throw UnsupportedOperationException("Process-wide supervised filters are not supported. Use thread-scoped supervision instead.")
            }
            val publish = ContainmentRegistryEffectInterpreter.prepare(
                    ContainmentRegistryEffect.SeccompInstalled(
                        processWide = false,
                        policy = toInstall,
                        blocks = newBlocks,
                        defaultAction = newDefaultAction,
                    ),
                )
            val session = io.mazewall.enforcer.internal.supervisor.SupervisorInstaller.installSupervisedFilterForThread(
                toInstall,
                scopingPolicy,
                {
                    attempt.seccompApplied = true
                    publish()
                },
                attempt.native,
                attempt.platform.capabilities.matrix.seccompUserNotifSupported,
            )
            return session
        } else {
            val compiledSandbox = io.mazewall.enforcer.api.PolicyCompilationCache
                .getOrCompile(toInstall, arch)
            val currentState = ContainerState.resolveCurrentState()
            val priorFilterDepth = currentState.filterDepth

            // Compute merged state for union-aware self-verification (issue-20260824-011900)
            val mergedState = currentState.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)

            val publish = ContainmentRegistryEffectInterpreter.prepare(
                ContainmentRegistryEffect.SeccompInstalled(
                    processWide,
                    toInstall,
                    newBlocks,
                    newDefaultAction,
                ),
            )
            PureJavaBpfEngine.installWithPublication(compiledSandbox, processWide, native = attempt.native, platform = attempt.platform) {
                attempt.seccompApplied = true
                publish()
            }
            // Runtime self-verification uses the attempt's captured setting and backend.
            // Default-ON for the real engine; the property can explicitly opt in/out.
            // Union-aware verification (issue-20260824-011900): passes merged state for stacked filters
            io.mazewall.seccomp.InstallSelfVerifier.verify(
                compiledSandbox.program,
                arch,
                priorFilterDepth,
                mergedState,
                attempt.native,
                attempt.selfVerificationEnabled,
            )
            return AutoCloseable {}
        }
    }
}

/**
 * True when every [childPaths] entry lies beneath some [parentPaths] entry.
 * Uses canonical real paths on both sides because Landlock binds rules to dentries.
 * Unresolvable operands compare only by their syntactic value; different unresolved
 * spellings are rejected, preserving the existing fail-closed subset semantics.
 */
private fun isPathSubset(
    parentPaths: Set<SandboxedPath>,
    childPaths: Set<SandboxedPath>,
): Boolean {
    if (childPaths.isEmpty()) return true
    val resolvedParents = parentPaths.map { it.resolveReal() }.toSet()
    return childPaths.all { child -> child.resolveReal().isUnderAny(resolvedParents) }
}
