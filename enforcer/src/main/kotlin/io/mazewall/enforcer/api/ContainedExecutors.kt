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
 * Explicit permanent process/thread installation, assessment and violation detection.
 * Synchronous application work uses [io.mazewall.enforcer.api.execution.ManagedRuntime]
 * preparation and owned Callable invocations. Closing a receipt releases registration, never filters.
 *
 * ARCHITECTURAL INVARIANT: Container state is maintained via immutable [ContainerState] objects,
 * which are updated atomically via [ContainmentStateRegistry]. This ensures
 * that the library has a consistent and race-free view of the active sandbox configuration
 * during concurrent or nested filter installations.
 *
 * ### Thread Safety & Global Policies
 * Modifying process-wide global security policies (e.g., via [installOnProcess]) while
 * asynchronous contained workers are active, executing tasks,
 * or undergoing shutdown is unsupported. Doing so can cause late-running tasks in the queue to
 * resolve an inconsistent security state because global state transitions and per-task filter
 * installations may interleave unpredictably. All global policies should be fully installed
 * before preparing or running asynchronous contained workers.
 *
 * ### Signal Mask Inheritance Warning
 * When using thread installations with policies that configure `SeccompAction.ACT_TRAP`, the seccomp
 * filter relies on the kernel delivering `SIGSYS` to the violating thread. Standard JVM thread pools
 * (such as [ExecutorService]) do not automatically reset POSIX signal masks (`sigprocmask`) or alternate
 * signal stacks (`sigaltstack`) when reusing threads. If a previous uncontained task executing native code (JNI/FFM)
 * blocked `SIGSYS` or corrupted the signal stack, a subsequently contained task on that same carrier thread
 * will not receive `SIGSYS` when it violates the seccomp policy, defeating `ACT_TRAP` actions.
 * Therefore, `ACT_TRAP` is unreliable in environments where native libraries might modify thread signal masks.
 * For guaranteed immediate enforcement in such environments, prefer process- or thread-killing actions like
 * `ACT_KILL_PROCESS` or `ACT_KILL_THREAD`.
 *
 * Furthermore, when a new thread is spawned by an [ExecutorService], it inherits the signal mask of its parent.
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

    internal fun installOnCurrentThread(
        policy: PolicyDefinition<*>,
        scoping: io.mazewall.enforcer.internal.execution.FrozenStackScoping,
        environment: InstallationEnvironment,
        preparedLandlock: io.mazewall.landlock.PreparedLandlock?,
        liveValidation: io.mazewall.enforcer.internal.execution.LiveValidationTransport? = null,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        io.mazewall.enforcer.diagnostics
            .requirePlatformThread()
        return when (scoping) {
            is io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy -> installInternal(false, policy, scoping.policy, environment, preparedLandlock)
            is io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live -> {
                augmentForScoping(policy, scoping)
                throw UnsupportedOperationException("Live stack scoping dispatcher is not available")
                @Suppress("UNREACHABLE_CODE")
                installInternal(false, policy, environment = environment, preparedLandlock = preparedLandlock, live = scoping to requireNotNull(liveValidation))
            }
        }
    }

    /** Read-only preflight. Does not install filters. */
    public fun assessOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationAssessment {
        policy.definition.requireNoWorkerFilesystemIntent()
        return InstallationAssessor.assess(policy.definition, processWide = true)
    }

    /** Read-only preflight for the current thread. Does not install filters. */
    public fun assessOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationAssessment = InstallationAssessor.assess(policy.definition, processWide = false)

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     */
    public fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): io.mazewall.enforcer.api.InstallationReceipt {
        val combined = acceptedProcessDefinition(policies.map { it.definition })
        return installInternal(true, combined)
    }

    internal fun installOnProcess(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        environment: InstallationEnvironment,
    ): InstallationReceipt {
        policy.claimProcessScope()
        return installInternal(true, policy, environment = environment)
    }

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
    private sealed interface InstallationScoping {
        class Legacy(
            val captured: io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy,
        ) : InstallationScoping

        class Live(
            val receipt: io.mazewall.enforcer.internal.execution.LiveValidationTransport,
        ) : InstallationScoping
    }

    @Suppress("TooGenericExceptionCaught") // Installation preserves fatal identity and records irreversible progress.
    private fun installInternal(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy = io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
        environment: InstallationEnvironment = InstallationEnvironment.capture(),
        preparedLandlock: io.mazewall.landlock.PreparedLandlock? = null,
        live: Pair<io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live, io.mazewall.enforcer.internal.execution.LiveValidationTransport>? = null,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        io.mazewall.enforcer.diagnostics
            .requirePlatformThread()
        requireNonQuarantinedScope()
        // A failed permanent listener cannot be repaired by policy planning or operator bypass.
        io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
            .requireReusableInstallations()
        val capturedScoping = if (live == null) captureSupportedScoping(policy, scopingPolicy, environment)?.let { InstallationScoping.Legacy(it) } else InstallationScoping.Live(live.second)
        val augmentedPolicy = when (capturedScoping) {
            null -> null
            is InstallationScoping.Legacy -> prepareSpawnPolicy(policy, capturedScoping.captured)
            is InstallationScoping.Live -> augmentForScoping(policy.copy(syscallActions = java.util.Map.copyOf(policy.syscallActions)), requireNotNull(live).first).also(::validateSpawnComposition)
        }
        val attempt = InstallationAttempt(processWide, environment, preparedLandlock)
        if (policy.lockIntelCet) attempt.cetOutcome = CetOutcome.UNCONFIRMED
        try {
            check(!processWide || !policy.lockIntelCet) { "Process-wide shadow-stack locking is unsupported; CET controls apply to the installing platform thread" }
            unsupportedPlatformReceipt(processWide, policy, attempt)?.let { return recordInstallationResult(it) }
            val captured = requireNotNull(capturedScoping)
            val preparedPolicy = requireNotNull(augmentedPolicy)
            check(captured !is InstallationScoping.Live || preparedPolicy.hasSupervisedSyscalls) { "Live supervision requires a listener" }

            check(attempt.platform.isLinux) { "Mazewall requires Linux for kernel-level containment." }
            preparePreSeccompEffects(preparedPolicy, attempt, captured is InstallationScoping.Legacy)
            return recordInstallationResult(installLandlockThenSeccomp(processWide, policy, preparedPolicy, captured, attempt))
        } catch (fatal: Error) {
            fatal.addSuppressed(PartialInstallationException(attempt.snapshot()))
            ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(processWide, fatal))
            throw fatal
        } catch (expectedInstallFailure: Throwable) {
            return handleInstallationFailure(processWide, policy, attempt, expectedInstallFailure)
        }
    }

    private fun handleInstallationFailure(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
        expectedInstallFailure: Throwable,
    ): InstallationReceipt {
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
        val landlockInForce = attempt.landlockApplied || scopeLandlockApplied(processWide)
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
            return io.mazewall.enforcer.api.InstallationReceipt.failed(
                processWide = processWide,
                requestedPolicy = policy,
                cause = failure,
                fallback = fallback,
                landlockApplied = landlockInForce,
                progress = progress,
                cetOutcome = attempt.cetOutcome,
            )
        }
        throw failure
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

    internal fun augmentForScoping(
        policy: PolicyDefinition<*>,
        scoping: io.mazewall.enforcer.internal.execution.FrozenStackScoping,
    ): PolicyDefinition<*> =
        when (scoping) {
            is io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy -> augmentForScoping(policy, scoping.policy)
            is io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live -> scoping.bootstrap.planScoping(policy)
        }

    private fun augmentForScoping(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
    ): PolicyDefinition<*> {
        validateScopingComposition(policy, scopingPolicy)
        return policy.copy(
            syscallActions = policy.syscallActions +
                scopingPolicy.handlers.keys
                    .mapNotNull { syscall ->
                val action = policy.syscallActions[syscall] ?: policy.defaultAction
                when (action) {
                    SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_TRAP, is SeccompAction.ACT_ERRNO -> null
                    SeccompAction.ACT_ALLOW, SeccompAction.ACT_NOTIFY, SeccompAction.ACT_LOG, is SeccompAction.ACT_TRACE -> syscall to SeccompAction.ACT_NOTIFY
                }
            }.toMap(),
        )
    }

    private fun captureSupportedScoping(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        environment: InstallationEnvironment,
    ): io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy? {
        if (!environment.platform.isSupported()) return null
        val captured = io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy
            .capture(scopingPolicy)
        validateScopingComposition(policy, captured.dispatch)
        return captured
    }

    private fun validateScopingComposition(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
    ) {
        for (syscall in scopingPolicy.handlers.keys) {
            when (policy.syscallActions[syscall] ?: policy.defaultAction) {
                SeccompAction.ACT_LOG, is SeccompAction.ACT_TRACE ->
                    throw IllegalArgumentException("Selected stacktrace handler cannot compose with LOG or TRACE: $syscall")
                SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_TRAP,
                is SeccompAction.ACT_ERRNO, SeccompAction.ACT_ALLOW, SeccompAction.ACT_NOTIFY,
                -> Unit
            }
        }
    }

    private fun prepareSpawnPolicy(
        policy: PolicyDefinition<*>,
        captured: io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy?,
    ): PolicyDefinition<*>? {
        if (captured == null) return null
        val snapshot = policy.copy(syscallActions = java.util.Map.copyOf(policy.syscallActions))
        return augmentForScoping(snapshot, captured.dispatch).also(::validateSpawnComposition)
    }

    private fun validateSpawnComposition(policy: PolicyDefinition<*>) {
        val auditDefault = policy.defaultAction == SeccompAction.ACT_LOG || policy.defaultAction is SeccompAction.ACT_TRACE
        val supervisedExec = listOf(Syscall.EXECVE, Syscall.EXECVEAT).any { policy.syscallActions[it] == SeccompAction.ACT_NOTIFY }
        val missingSpawn = listOf(Syscall.FORK, Syscall.VFORK, Syscall.CLONE).any { it !in policy.syscallActions }
        require(!auditDefault || !supervisedExec || !missingSpawn) { "Supervised exec cannot compose with inherited LOG or TRACE spawn action" }
    }

    private fun unsupportedPlatformReceipt(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt? {
        if (attempt.platform.isSupported()) return null
        handleUnsupportedPlatform(attempt.fallback)
        return io.mazewall.enforcer.api.InstallationReceipt.bypassed(
            processWide = processWide,
            requestedPolicy = policy,
            reason = UnsupportedOperationException("Platform does not support seccomp"),
            fallback = attempt.fallback,
            progress = attempt.snapshot(),
            cetOutcome = attempt.cetOutcome,
        )
    }

    private fun preparePreSeccompEffects(
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
        spawnLegacyDaemon: Boolean,
    ) {
        io.mazewall.enforcer.api.InstallationReceipt
            .preloadForSeccompInstall()
        if (policy.lockIntelCet) armIntelCet(attempt)
        if (policy.hasSupervisedSyscalls && spawnLegacyDaemon) {
            io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
                .getInstance()
                .getOrSpawnSharedDaemon(attempt.native)
        }
    }

    private fun installLandlockThenSeccomp(
        processWide: Boolean,
        requestedPolicy: PolicyDefinition<*>,
        policy: PolicyDefinition<*>,
        scopingPolicy: InstallationScoping,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        val landlockApplied =
            when (val landlockStep = applyLandlockIfNecessary(processWide, policy, attempt)) {
                LandlockStep.Applied -> true
                is LandlockStep.Bypassed ->
                    return io.mazewall.enforcer.api.InstallationReceipt.bypassed(
                        processWide = processWide,
                        requestedPolicy = requestedPolicy,
                        reason = landlockStep.reason,
                        fallback = attempt.fallback,
                        landlockApplied = scopeLandlockApplied(processWide),
                        progress = attempt.snapshot(),
                        cetOutcome = attempt.cetOutcome,
                    )
                LandlockStep.Unchanged -> scopeLandlockApplied(processWide)
            }
        return installSeccompFilter(processWide, policy, scopingPolicy, landlockApplied, attempt)
    }

    private fun installSeccompFilter(
        processWide: Boolean,
        combinedPolicy: PolicyDefinition<*>,
        scopingPolicy: InstallationScoping,
        landlockApplied: Boolean,
        attempt: InstallationAttempt,
    ): io.mazewall.enforcer.api.InstallationReceipt {
        // FAST PATH: Check if the current thread state already satisfies the policy without locking
        val fastState = installationState(processWide)
        val fastDecision = FilterInstallationPlanner.plan(combinedPolicy, fastState)
        if (fastDecision is FilterInstallationPlanner.PlanDecision.AlreadySatisfied && scopingPolicy is InstallationScoping.Live) {
            error("Live supervision requires a new listener")
        }
        if (fastDecision is FilterInstallationPlanner.PlanDecision.AlreadySatisfied && (!combinedPolicy.hasSupervisedSyscalls || processWide)) {
            return io.mazewall.enforcer.api.InstallationReceipt.installed(
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
            val session =
                when (val decision = FilterInstallationPlanner.plan(combinedPolicy, state)) {
                    is FilterInstallationPlanner.PlanDecision.AlreadySatisfied -> {
                        if (scopingPolicy is InstallationScoping.Live) error("Live supervision requires a new listener")
                        null
                    }
                    is FilterInstallationPlanner.PlanDecision.Install -> {
                        // Kernel depth belongs to this thread's complete filter tree, even
                        // though process policy coverage is planned from processState only.
                        FilterInstallationPlanner.verifyFilterDepth(ContainerState.resolveCurrentState().filterDepth)
                        applyBpfFilter(
                            processWide,
                            decision.fragment,
                            scopingPolicy,
                            attempt,
                            warnOnUnsafePrctl = combinedPolicy.allowUnsafePrctl,
                        )
                    }
                }

            if (!processWide && combinedPolicy.hasSupervisedSyscalls) {
                val supervisorRegistration =
                    if (session is io.mazewall.enforcer.internal.supervisor.SupervisorSession) {
                        session
                    } else {
                        when (scopingPolicy) {
                            is InstallationScoping.Legacy ->
                                io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
                                .acquireCapturedTaskSession(scopingPolicy.captured, attempt.native)
                            is InstallationScoping.Live -> error("Live supervision requires its original listener")
                        }
                    }
                return io.mazewall.enforcer.api.InstallationReceipt.installed(
                    processWide = processWide,
                    requestedPolicy = combinedPolicy,
                    landlockApplied = landlockApplied,
                    fallback = attempt.fallback,
                    progress = attempt.snapshot(),
                    cetOutcome = attempt.cetOutcome,
                    supervisorRegistration = supervisorRegistration,
                )
            }
            return io.mazewall.enforcer.api.InstallationReceipt.installed(
                processWide = processWide,
                requestedPolicy = combinedPolicy,
                landlockApplied = landlockApplied,
                fallback = attempt.fallback,
                progress = attempt.snapshot(),
                cetOutcome = attempt.cetOutcome,
            )
        }
    }

    private sealed interface LandlockStep {
        data object Unchanged : LandlockStep

        data object Applied : LandlockStep

        data class Bypassed(
            val reason: Throwable,
        ) : LandlockStep
    }

    private fun applyLandlockIfNecessary(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
        attempt: InstallationAttempt,
    ): LandlockStep {
        if (!policy.enforceLandlock) return LandlockStep.Unchanged

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
                        return LandlockStep.Applied
                    }
                    is io.mazewall.landlock.LandlockApplyResult.Bypassed -> {
                        return LandlockStep.Bypassed(applied.toFailure())
                    }
                    is io.mazewall.landlock.LandlockApplyResult.Rejected -> applied.orThrow()
                }
            }
            return LandlockStep.Unchanged
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
        scopingPolicy: InstallationScoping,
        attempt: InstallationAttempt,
        warnOnUnsafePrctl: Boolean = toInstall.allowUnsafePrctl,
    ): AutoCloseable? {
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
                    ),
                )
            val onApplied = {
                attempt.seccompApplied = true
                publish()
            }
            val session = when (scopingPolicy) {
                is InstallationScoping.Legacy -> io.mazewall.enforcer.internal.supervisor.SupervisorInstaller.installCapturedSupervisedFilterForThread(
                    toInstall,
                    scopingPolicy.captured,
                    onApplied,
                    attempt.native,
                    attempt.platform.capabilities.matrix.seccompUserNotifSupported,
                )
                is InstallationScoping.Live -> io.mazewall.enforcer.internal.supervisor.SupervisorInstaller.installLiveSupervisedFilterForThread(
                    toInstall,
                    scopingPolicy.receipt,
                    onApplied,
                    attempt.native,
                    attempt.platform.capabilities.matrix.seccompUserNotifSupported,
                )
            }
            return session
        } else {
            val compiledSandbox = io.mazewall.enforcer.api.PolicyCompilationCache
                .getOrCompile(toInstall, arch, warnOnUnsafePrctl = warnOnUnsafePrctl)
            val currentState = ContainerState.resolveCurrentState()
            val priorFilterDepth = currentState.filterDepth

            // Compute merged state for union-aware self-verification (issue-20260824-011900)
            val mergedState = currentState.withAppliedFilter(toInstall)

            val publish = ContainmentRegistryEffectInterpreter.prepare(
                ContainmentRegistryEffect.SeccompInstalled(
                    processWide,
                    toInstall,
                ),
            )
            PureJavaBpfEngine.installWithPublication(
                compiledSandbox,
                processWide,
                native = attempt.native,
                isLinux = attempt.platform.isLinux,
                tsyncSupported = if (processWide) attempt.platform.capabilities.matrix.seccompTsyncSupported else false,
            ) {
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
            // No supervisor lease: linking a no-op callback here can require executable memory after installation.
            return null
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
