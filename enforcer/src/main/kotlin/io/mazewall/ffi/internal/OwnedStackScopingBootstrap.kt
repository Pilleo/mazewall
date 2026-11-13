package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.platform.native.LiveSetup
import io.mazewall.platform.native.WorkerCapacity
import java.nio.file.Path

internal fun prepareOwnedStackScoping(
    runtime: ManagedRuntime,
    artifact: ReviewedOperationArtifact,
    limits: DomainLimits,
    resources: PreparedOperationResources,
    agentArtifact: Path,
    identity: LiveStackBootstrap.LiveDeploymentIdentity,
    owners: List<Class<*>>,
    methods: List<java.lang.reflect.Executable>,
    corridors: List<List<java.lang.reflect.Executable>>,
    scopingPolicy: StacktraceScopingPolicy = DefaultStacktraceScopingPolicy,
): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> =
    OwnedStackScopingBootstrap.prepare(runtime, artifact, limits, resources, agentArtifact, identity, owners, methods, corridors, scopingPolicy)

private const val CHECKED_OPEN_ROUTE = 2
private const val CHECKED_OPENAT_ROUTE = 257

private class CheckedObservations(
    val beforeClaim: () -> Unit,
    val afterClaim: () -> Unit,
    val afterReserve: () -> Unit,
    val beforeNotification: () -> Unit,
)

/** Independently reviewed support and trusted-host custody premise; neither is OS proof. */
internal class TrustedHelperSupport private constructor(
    val recipe: io.mazewall.enforcer.internal.execution.ReviewedHelperSupportRecipe,
) {
    companion object {
        fun reviewed(
            support: List<io.mazewall.enforcer.internal.execution.ReviewedHelperSupport>,
            lifetime: io.mazewall.enforcer.internal.execution.HelperSupportLifetime,
        ): TrustedHelperSupport {
            require(lifetime == io.mazewall.enforcer.internal.execution.HelperSupportLifetime.NoWriters)
            return TrustedHelperSupport(
                io.mazewall.enforcer.internal.execution.ReviewedHelperSupportRecipe
                .capture(support),
            )
        }
    }
}

internal class CheckedBootstrapRequest(
    internal val path: Path,
    internal val identity: LiveStackBootstrap.LiveDeploymentIdentity,
    internal val helperSupport: TrustedHelperSupport?,
) {
    constructor(path: Path, identity: LiveStackBootstrap.LiveDeploymentIdentity) : this(path, identity, null)

    init {
        require(identity.profile() == LiveStackBootstrap.Profile.CLOSED_V1 && identity.variant() == LiveStackBootstrap.Variant.ONLY)
        require(identity.retransformFixture().isEmpty && identity.routeSubset() == listOf(CHECKED_OPEN_ROUTE, CHECKED_OPENAT_ROUTE))
        val agents = identity.artifacts().filter { it.role() == "agent" }
        require(agents.size == 1 && identity.artifacts().count { it.role() == "enforcer" } == 1)
        val agent = agents.single()
        require(
            path.fileSystem === java.nio.file.FileSystems
            .getDefault() &&
            path.isAbsolute &&
            path == Path.of(agent.canonicalPath()),
        )
    }

    fun check(snapshot: io.mazewall.platform.native.RuntimeSnapshot): CheckedBootstrapAssociation {
        return when (val result = RealNativeStackContexts.bootstrapChecked(path, identity, snapshot)) {
            is RealNativeStackContexts.BootstrapResult.Refused -> throw CheckedBootstrapRefusal(result.failure)
            is RealNativeStackContexts.BootstrapResult.Checked -> {
            val baseline = helperSupport?.let {
                val observations = HelperSecurityObservation()
                val pid = io.mazewall.platform.lifecycle
                    .Pid(Math.toIntExact(ProcessHandle.current().pid()))
                val leader = observations.observeAncestry(
                    pid,
                    io.mazewall.platform.lifecycle
                    .Tid(pid.value),
                        HelperSecurityObservation.Lineage.Bootstrap,
                )
                observations.observeAncestry(pid, snapshot.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap).also { current ->
                    check(current.identity == leader.identity) { "Checked bootstrap process changed" }
                    current.requireLineage(leader, HelperSecurityObservation.Lineage.Bootstrap)
                }
            }
                CheckedBootstrapAssociation(this, result, snapshot, baseline)
            }
        }
    }
}

internal class TrustedHelperAdmission private constructor(
    val support: TrustedHelperSupport,
    val baseline: HelperSecurityObservation.Observation,
    val observations: HelperSecurityObservation,
) {
    companion object {
        fun fromChecked(
            support: TrustedHelperSupport,
            baseline: HelperSecurityObservation.Observation,
        ): TrustedHelperAdmission = TrustedHelperAdmission(support, baseline, HelperSecurityObservation())
    }
}

internal class CheckedBootstrapRefusal(
    val failure: io.mazewall.platform.native.LiveFailure,
) : IllegalStateException("Checked runtime bootstrap refused: ${failure.reason}", failure.cause)

internal class CheckedBootstrapAssociation(
    private val request: CheckedBootstrapRequest,
    private val checked: RealNativeStackContexts.BootstrapResult.Checked,
    private val snapshot: io.mazewall.platform.native.RuntimeSnapshot,
    internal val helperBaseline: HelperSecurityObservation.Observation?,
) {
    internal var helperContext: io.mazewall.enforcer.internal.supervisor.SupervisorContext? = null
        private set

    internal fun bindHelperContext(
        owner: io.mazewall.enforcer.api.execution.ManagedRuntime.CanonicalAcquisition,
        context: io.mazewall.enforcer.internal.supervisor.SupervisorContext,
    ) {
        owner.requireHelperContext(this, context)
        check(request.helperSupport != null && helperContext == null)
        helperContext = context
    }

    internal val helperAdmission: TrustedHelperAdmission? = request.helperSupport?.let { support ->
        TrustedHelperAdmission.fromChecked(support, checkNotNull(helperBaseline))
    }
    private val adapter = checked.contextsFor(request.identity, snapshot)

    fun revalidate(
        candidate: CheckedBootstrapRequest,
        candidateSnapshot: io.mazewall.platform.native.RuntimeSnapshot,
    ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.NativeStackContexts> {
        if (candidate.identity !== request.identity ||
            candidateSnapshot !== snapshot ||
            candidate.path.fileSystem !== java.nio.file.FileSystems
            .getDefault() ||
            candidate.path != request.path
        ) {
            return io.mazewall.platform.native.LiveSetup.Refused(
                io.mazewall.platform.native
                    .LiveFailure(io.mazewall.platform.native.LiveFailureStatus.Unsupported, io.mazewall.platform.native.LiveFailureReason.RuntimeMismatch),
            )
        }
        return when (val result = checked.revalidate(request.identity, snapshot)) {
            is RealNativeStackContexts.BootstrapResult.Checked -> {
                check(result === checked)
                io.mazewall.platform.native.LiveSetup
                    .Ready(adapter)
            }
            is RealNativeStackContexts.BootstrapResult.Refused ->
                io.mazewall.platform.native.LiveSetup
                .Refused(result.failure)
        }
    }
}

internal fun newCheckedRuntimeOwner(
    path: Path,
    identity: LiveStackBootstrap.LiveDeploymentIdentity,
): io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner =
    io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner
    .createChecked(CheckedBootstrapRequest(path, identity))

internal fun newCheckedRuntimeOwner(
    path: Path,
    identity: LiveStackBootstrap.LiveDeploymentIdentity,
    support: TrustedHelperSupport,
): io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner =
    io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner
        .createChecked(CheckedBootstrapRequest(path, identity, support))

private const val MAX_CAPTURE_OWNERS = 512
private const val MAX_CAPTURE_METHODS = 4096
private const val MAX_CAPTURE_CORRIDORS = 128
private const val MAX_CAPTURE_CORRIDOR_LENGTH = 128

private val noAfterReserve: (io.mazewall.platform.native.InstallationOwner) -> Unit = {}
private val noBeforePublish: (LiveSetup.Ready<io.mazewall.enforcer.api.execution.PreparedStackScoping>) -> Unit = {}

private class CaptureInputs(
    val identity: LiveStackBootstrap.LiveDeploymentIdentity,
    val artifact: ReviewedOperationArtifact,
    val limits: DomainLimits,
    val resources: PreparedOperationResources,
    val owners: List<Class<*>>,
    val methods: List<java.lang.reflect.Executable>,
    val corridors: List<List<java.lang.reflect.Executable>>,
    val handlers: Map<io.mazewall.platform.syscall.Syscall, io.mazewall.enforcer.api.ScopingHandler>,
) {
    private val worker = artifact.document.policy as? io.mazewall.enforcer.api.CanonicalPolicyArtifact.Worker
        ?: throw IllegalArgumentException("Live capture requires a worker policy")
    val definition = worker.policy.definition.frozenCopy()
    val canonicalJson = worker.toJson()
    var helperAdmission: TrustedHelperAdmission? = null
    var helperContext: io.mazewall.enforcer.internal.supervisor.SupervisorContext? = null
    val routes: Set<io.mazewall.platform.syscall.Syscall> = identity
        .routeSubset()
        .map { route ->
        when (route) {
            2 -> io.mazewall.platform.syscall.Syscall.OPEN
            257 -> io.mazewall.platform.syscall.Syscall.OPENAT
            else -> error("Unsupported route")
        }
    }.toSet()
}

internal class OwnedStackScopingBootstrap private constructor(
    private val runtime: ManagedRuntime,
    private val preparation: io.mazewall.enforcer.internal.execution.ScopingPreparation,
    private val inputs: CaptureInputs,
    internal val staging: io.mazewall.platform.native.InstallationOwner,
    internal val enrollment: io.mazewall.platform.native.Enrollment,
    internal val sealed: io.mazewall.platform.native.InstallationOwner,
) {
    internal val artifact: ReviewedOperationArtifact get() = inputs.artifact
    internal val limits: DomainLimits get() = inputs.limits
    internal val resources: PreparedOperationResources get() = inputs.resources
    internal val helperAdmission: TrustedHelperAdmission? = inputs.helperAdmission
    internal val helperContext: io.mazewall.enforcer.internal.supervisor.SupervisorContext? = inputs.helperContext
    internal val canonicalJson: String get() = inputs.canonicalJson

    internal fun matchesClaim(
        runtime: ManagedRuntime,
        policy: io.mazewall.enforcer.api.PolicyDefinition<*>,
        limits: DomainLimits,
        native: io.mazewall.platform.native.RuntimeSnapshot,
    ): Boolean =
        this.runtime === runtime &&
        inputs.definition == policy &&
        inputs.limits == limits &&
        inputs.resources.native.engineIdentity === native.engineIdentity

    internal fun planScoping(policy: io.mazewall.enforcer.api.PolicyDefinition<*>): io.mazewall.enforcer.api.PolicyDefinition<*> {
        require(policy == inputs.definition) { "Live policy differs from captured policy" }
        require(inputs.routes.all { policy.isSyscallAllowed(it) }) { "Static policy denies reviewed route" }
        require(
            policy.defaultAction != io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY &&
            policy.syscallActions.none { (syscall, action) -> action == io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY && syscall !in inputs.routes },
        ) { "Unrelated notification is not supported" }
        val denied = setOf(io.mazewall.platform.syscall.Syscall.OPEN, io.mazewall.platform.syscall.Syscall.OPENAT, io.mazewall.platform.syscall.Syscall.OPENAT2) - inputs.routes
        val actions = policy.syscallActions.toMutableMap()
        for (route in denied) {
            val existing = actions[route] ?: policy.defaultAction
            actions[route] = existing.stricter(
                io.mazewall.platform.syscall.SeccompAction
                .ACT_ERRNO(),
            )
        }
        for (route in inputs.routes) actions[route] = io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY
        return policy.copy(syscallActions = java.util.Map.copyOf(actions))
    }

    internal fun ownsRetainedShell(shell: io.mazewall.landlock.PreparedLandlock): Boolean = shell.matchesRetained(inputs.resources.resources, preparation.environment)

    internal fun reserveLiveWorker(
        domain: io.mazewall.enforcer.internal.execution.OwnedDomain,
        slot: io.mazewall.enforcer.internal.execution.LiveWorkerSlot,
    ) = runtime.liveWorkerAdmission.reserve(preparation, this, domain, slot)

    internal fun close() = runtime.closeScopingPreparation(preparation)

    companion object {
        @Suppress("TooGenericExceptionCaught") // Retain original failure before pre-delegation quiescence and rethrow it.
        internal fun prepare(
            runtime: ManagedRuntime,
            artifact: ReviewedOperationArtifact,
            limits: DomainLimits,
            resources: PreparedOperationResources,
            agentArtifact: Path,
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
            owners: List<Class<*>>,
            methods: List<java.lang.reflect.Executable>,
            corridors: List<List<java.lang.reflect.Executable>>,
            scopingPolicy: StacktraceScopingPolicy = DefaultStacktraceScopingPolicy,
        ): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> {
            val checked = runtime.checkedOperations
            if (checked != null) {
                val request = CheckedBootstrapRequest(agentArtifact, identity)
                return prepareChecked(runtime, artifact, limits, resources, request, owners, methods, corridors, scopingPolicy, CheckedObservations({}, {}, {}, {}))
            }
            check(!resources.isDirectResult()) { "Direct result Live scoping is not available" }
            val preparation = runtime.admitScopingPreparation()
            var delegated = false
            var failure: Throwable? = null
            try {
                val inputs = captureInputs(identity, artifact, limits, resources, owners, methods, corridors, scopingPolicy)
                require(resources.runtime == runtime.runtime) { "Resource runtime differs from owner" }
                require(resources.native.engineIdentity === preparation.environment.native.engineIdentity) { "Resources belong to a different native runtime" }
                val contexts = RealNativeStackContexts.bootstrap(agentArtifact, identity, preparation.environment.native)
                delegated = true
                return captureAdmitted(runtime, preparation, inputs, contexts, noAfterReserve, noBeforePublish)
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                if (!delegated) runtime.abandonScopingPreparation(preparation, failure)
            }
        }

        @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally") // Preserve original and fatal abandonment failure precedence.
        private fun prepareChecked(
            runtime: ManagedRuntime,
            artifact: ReviewedOperationArtifact,
            limits: DomainLimits,
            resources: PreparedOperationResources,
            request: CheckedBootstrapRequest,
            owners: List<Class<*>>,
            methods: List<java.lang.reflect.Executable>,
            corridors: List<List<java.lang.reflect.Executable>>,
            scopingPolicy: StacktraceScopingPolicy,
            observations: CheckedObservations,
        ): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> {
            check(!resources.isDirectResult()) { "Direct result Live scoping is not available" }
            val preparation = runtime.admitScopingPreparation()
            var delegated = false
            var failure: Throwable? = null
            try {
                val inputs = captureInputs(request.identity, artifact, limits, resources, owners, methods, corridors, scopingPolicy)
                require(resources.runtime == runtime.runtime) { "Resource runtime differs from owner" }
                require(resources.native.engineIdentity === preparation.environment.native.engineIdentity) { "Resources belong to a different native runtime" }
                inputs.helperAdmission = checkNotNull(runtime.checkedOperations).helperAdmission()
                inputs.helperContext = checkNotNull(runtime.checkedOperations).helperContext()
                val reserved = when (
                    val result = checkNotNull(
                        runtime.checkedOperations,
                    ).reserve(preparation, request, WorkerCapacity(inputs.limits.workers), observations.beforeClaim, observations.afterClaim)
                ) {
                    is LiveSetup.Ready -> result
                    is LiveSetup.Refused -> return result
                }
                delegated = true
                return captureCheckedAdmitted(runtime, preparation, inputs, reserved, observations.afterReserve, observations.beforeNotification)
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                if (!delegated) {
                    var abandonmentFailure: Throwable? = null
                    try {
                        checkNotNull(runtime.checkedOperations).abandon(preparation, failure, observations.beforeNotification)
                    } catch (problem: Throwable) {
                        abandonmentFailure = problem
                    }
                    if (failure is InterruptedException || abandonmentFailure is InterruptedException) Thread.currentThread().interrupt()
                    val selectedFailure = if (failure is Error || abandonmentFailure !is Error) failure ?: abandonmentFailure else abandonmentFailure
                    if (selectedFailure != null && selectedFailure !== failure) throw selectedFailure
                }
            }
        }

        private fun captureInputs(
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
            artifact: ReviewedOperationArtifact,
            limits: DomainLimits,
            resources: PreparedOperationResources,
            owners: List<Class<*>>,
            methods: List<java.lang.reflect.Executable>,
            corridors: List<List<java.lang.reflect.Executable>>,
            scopingPolicy: StacktraceScopingPolicy,
        ): CaptureInputs {
            val captured = CaptureInputs(
                identity,
                artifact,
                limits,
                resources,
                java.util.List.copyOf(owners),
                java.util.List.copyOf(methods),
                java.util.List.copyOf(corridors.map { java.util.List.copyOf(it) }),
                java.util.Map.copyOf(scopingPolicy.handlers),
            )
            validateCaptureMembership(captured)
            validateCaptureRoutes(captured)
            validateCapturePolicy(captured)
            return captured
        }

        private fun validateCaptureRoutes(captured: CaptureInputs) {
            val entry = resolveCaptureEntry(captured)
            val dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher", false, null)
            val boundaries = captured.routes.associateWith { route ->
                val name = if (route == io.mazewall.platform.syscall.Syscall.OPEN) "open0" else "openat0"
                val desc = if (route == io.mazewall.platform.syscall.Syscall.OPEN) "(JII)I" else "(IJII)I"
                captured.methods.filterIsInstance<java.lang.reflect.Method>().singleOrNull { method ->
                    method.declaringClass === dispatcher &&
                        method.name == name &&
                        java.lang.reflect.Modifier
                            .isNative(method.modifiers) &&
                        java.lang.reflect.Modifier
                            .isStatic(method.modifiers) &&
                        java.lang.invoke.MethodType
                            .methodType(method.returnType, method.parameterTypes.toList())
                            .toMethodDescriptorString() == desc
                } ?: throw IllegalArgumentException("Reviewed route boundary is not uniquely enrolled")
            }
            require(captured.corridors.all { corridor -> corridor.last() == entry && boundaries.values.any { it == corridor.first() } }) { "Corridor endpoints do not match reviewed binding" }
            require(boundaries.values.all { boundary -> captured.corridors.any { it.first() == boundary } }) { "Reviewed route has no corridor" }
            val expectedBindings = captured.routes.map { if (it == io.mazewall.platform.syscall.Syscall.OPEN) 1 else 2 }.toSet()
            val selectedBindings = captured.identity.bindingTargets().map { it.binding() }
            require(selectedBindings.size == selectedBindings.distinct().size && selectedBindings.toSet().containsAll(expectedBindings)) { "Identity bindings do not match reviewed routes" }
            require(captured.handlers.keys.all { it in captured.routes }) { "Handlers must be limited to reviewed routes" }
        }

        private fun resolveCaptureEntry(captured: CaptureInputs): java.lang.reflect.Method {
            val descriptor = when (captured.artifact.binding.adapter) {
                io.mazewall.enforcer.api.OperationAdapter.CALLABLE ->
                    captured.artifact.binding.adapterIdentity
                    .getValue("entryPoint")
                io.mazewall.enforcer.api.OperationAdapter.SPRING_METHOD ->
                    captured.artifact.binding.adapterIdentity
                    .getValue("methodDescriptor")
                io.mazewall.enforcer.api.OperationAdapter.KOOG_TOOL -> throw IllegalArgumentException("Unsupported live operation adapter")
            }
            return captured.methods.filterIsInstance<java.lang.reflect.Method>().singleOrNull { method ->
                method.declaringClass.isAssignableFrom(captured.resources.implementationClass) &&
                    method.name +
                        java.lang.invoke.MethodType
                        .methodType(method.returnType, method.parameterTypes.toList())
                        .toMethodDescriptorString() == descriptor
            } ?: throw IllegalArgumentException("Reviewed entry is not uniquely enrolled")
        }

        private fun validateCapturePolicy(captured: CaptureInputs) {
            val worker = captured.artifact.document.policy as? io.mazewall.enforcer.api.CanonicalPolicyArtifact.Worker
                ?: throw IllegalArgumentException("Live capture requires a worker policy")
            for (route in captured.routes) {
                val definition = worker.policy.definition
                val action = definition.syscallActions[route] ?: definition.defaultAction
                require(action == io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW) { "Static policy denies reviewed route: $route" }
            }
        }

        private fun validateCaptureMembership(captured: CaptureInputs) {
            val worker = captured.artifact.document.policy as? io.mazewall.enforcer.api.CanonicalPolicyArtifact.Worker
                ?: throw IllegalArgumentException("Live capture requires a worker policy")
            require(worker.runtime == captured.resources.runtime) { "Reviewed runtime differs from resources" }
            require(captured.routes.isNotEmpty()) { "Live capture requires a route" }
            require(captured.owners.isNotEmpty() && captured.owners.size <= MAX_CAPTURE_OWNERS && captured.owners.distinct().size == captured.owners.size) { "Invalid class membership" }
            require(captured.methods.isNotEmpty() && captured.methods.size <= MAX_CAPTURE_METHODS && captured.methods.distinct().size == captured.methods.size) { "Invalid method membership" }
            require(captured.methods.all { method -> captured.owners.any { it === method.declaringClass } }) { "Method class is not enrolled" }
            require(
                captured.corridors.isNotEmpty() &&
                captured.corridors.size <= MAX_CAPTURE_CORRIDORS &&
                captured.corridors.all {
                it.isNotEmpty() && it.size <= MAX_CAPTURE_CORRIDOR_LENGTH
            },
            ) { "Invalid capture corridors" }
            require(captured.corridors.flatten().all { member -> captured.methods.any { it == member } }) { "Corridor member is not enrolled" }
        }

        private fun validateEnrollment(
            enrollment: io.mazewall.platform.native.Enrollment,
            staging: io.mazewall.platform.native.InstallationOwner,
            inputs: CaptureInputs,
        ) {
                require(enrollment.owner === staging)
                require(
                    enrollment.classes.size == inputs.owners.size &&
                    enrollment.classes.indices.all {
                    enrollment.classes[it].identity === inputs.owners[it]
                },
                ) { "Enrollment classes differ from captured identities" }
                require(
                    enrollment.methods.size == inputs.methods.size &&
                    enrollment.methods.indices.all { index ->
                    val enrolled = enrollment.methods[index]
                    val actual = inputs.methods[index]
                    val returnType = if (actual is java.lang.reflect.Method) actual.returnType else Void.TYPE
                    val name = if (actual is java.lang.reflect.Method) actual.name else "<init>"
                    enrolled.identity === actual &&
                        enrolled.name == name &&
                        enrolled.modifiers == actual.modifiers &&
                        enrolled.descriptor == java.lang.invoke.MethodType
                            .methodType(returnType, actual.parameterTypes.toList())
                            .toMethodDescriptorString()
                },
                ) { "Enrollment methods differ from captured identities" }
        }

        private fun validateStaging(
            staging: io.mazewall.platform.native.InstallationOwner,
            contexts: io.mazewall.platform.native.NativeStackContexts,
            inputs: CaptureInputs,
        ) {
            require(
                staging.capability === contexts &&
                    staging.capacity == WorkerCapacity(inputs.limits.workers) &&
                    staging.state === io.mazewall.platform.native.InstallationState.Staging,
            ) { "Reserve owner does not match the captured capability and capacity" }
        }

        private fun validateSealed(
            staging: io.mazewall.platform.native.InstallationOwner,
            enrollment: io.mazewall.platform.native.Enrollment,
            sealed: io.mazewall.platform.native.InstallationOwner,
        ) {
            require(
                (sealed.state as? io.mazewall.platform.native.InstallationState.Sealed)?.enrollment === enrollment &&
                    sealed.capability === staging.capability &&
                    sealed.process == staging.process &&
                    sealed.installation == staging.installation &&
                    sealed.capacity == staging.capacity,
            ) { "Sealed owner differs from the original installation" }
        }

        @Suppress("TooGenericExceptionCaught") // Attach the exact acquired owner before quiescence and rethrow the original failure.
        private fun captureAdmitted(
            runtime: ManagedRuntime,
            preparation: io.mazewall.enforcer.internal.execution.ScopingPreparation,
            inputs: CaptureInputs,
            contexts: io.mazewall.platform.native.NativeStackContexts,
            afterReserve: (io.mazewall.platform.native.InstallationOwner) -> Unit,
            beforePublish: (LiveSetup.Ready<io.mazewall.enforcer.api.execution.PreparedStackScoping>) -> Unit,
        ): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> {
            var owner: io.mazewall.platform.native.InstallationOwner? = null
            var published = false
            var failure: Throwable? = null
            try {
                return when (val reserved = contexts.reserve(WorkerCapacity(inputs.limits.workers))) {
                    is LiveSetup.Refused -> reserved
                    is LiveSetup.Ready -> {
                        val staging = reserved.value.also { owner = it }
                        validateStaging(staging, contexts, inputs)
                afterReserve(staging)
                runtime.attachScopingOwner(preparation, staging)
                val enrollment = when (val result = staging.capability.enroll(staging, inputs.owners, inputs.methods)) {
                    is LiveSetup.Ready -> result.value
                    is LiveSetup.Refused -> return result
                }
                validateEnrollment(enrollment, staging, inputs)
                runtime.attachScopingEnrollment(preparation, enrollment)
                val sealed = when (val result = staging.capability.seal(staging, enrollment)) {
                    is LiveSetup.Ready -> result.value
                    is LiveSetup.Refused -> return result
                }
                validateSealed(staging, enrollment, sealed)
                runtime.attachSealedScopingOwner(preparation, sealed)
                val bootstrap = OwnedStackScopingBootstrap(runtime, preparation, inputs, staging, enrollment, sealed)
                val ready = LiveSetup.Ready(
                    io.mazewall.enforcer.api.execution
                    .PreparedStackScoping(bootstrap),
                )
                val frozen = io.mazewall.enforcer.internal.execution.FrozenStackScoping
                    .Live(bootstrap)
                runtime.attachScopingCapture(preparation, sealed, frozen, ready)
                val readyState = io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready(
                    io.mazewall.enforcer.internal.execution.ScopingPreparation
                        .Retained(
                            staging,
                            enrollment,
                            sealed,
                            inputs.resources,
                            frozen,
                            ready,
                            disposition = io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned,
                        ),
                    frozen,
                    ready,
                )
                beforePublish(ready)
                if (runtime.publishScopingPreparation(preparation, readyState)) {
                    published = true
                    ready
                } else {
                    LiveSetup.Refused(
                        io.mazewall.platform.native.LiveFailure(
                            io.mazewall.platform.native.LiveFailureStatus.Stale,
                            io.mazewall.platform.native.LiveFailureReason.Shutdown,
                        ),
                    )
                }
                    }
                }
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                if (!published) {
                    owner?.let { runtime.attachScopingOwner(preparation, it) }
                    runtime.abandonScopingPreparation(preparation, failure)
                }
            }
        }

        private fun selectCheckedFailure(
            first: Throwable?,
            second: Throwable?,
            third: Throwable?,
        ): Throwable? {
            val selected = if (first == null || (first !is Error && second is Error)) second ?: first else first
            return if (selected == null || (selected !is Error && third is Error)) third ?: selected else selected
        }

        private fun captureAttachedChecked(
            runtime: ManagedRuntime,
            preparation: io.mazewall.enforcer.internal.execution.ScopingPreparation,
            inputs: CaptureInputs,
            staging: io.mazewall.platform.native.InstallationOwner,
        ): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> {
                val enrollment = when (val result = staging.capability.enroll(staging, inputs.owners, inputs.methods)) {
                    is LiveSetup.Ready -> result.value
                    is LiveSetup.Refused -> return result
                }
                validateEnrollment(enrollment, staging, inputs)
                runtime.attachScopingEnrollment(preparation, enrollment)
                val sealed = when (val result = staging.capability.seal(staging, enrollment)) {
                    is LiveSetup.Ready -> result.value
                    is LiveSetup.Refused -> return result
                }
                validateSealed(staging, enrollment, sealed)
                runtime.attachSealedScopingOwner(preparation, sealed)
                val bootstrap = OwnedStackScopingBootstrap(runtime, preparation, inputs, staging, enrollment, sealed)
                val ready = LiveSetup.Ready(
                    io.mazewall.enforcer.api.execution
                    .PreparedStackScoping(bootstrap),
                )
                val frozen = io.mazewall.enforcer.internal.execution.FrozenStackScoping
                    .Live(bootstrap)
                runtime.attachScopingCapture(preparation, sealed, frozen, ready)
                val readyState = io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready(
                    io.mazewall.enforcer.internal.execution.ScopingPreparation
                        .Retained(
                            staging,
                            enrollment,
                            sealed,
                            inputs.resources,
                            frozen,
                            ready,
                            disposition = io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned,
                        ),
                    frozen,
                    ready,
                )
                noBeforePublish(ready)
                return if (runtime.publishScopingPreparation(preparation, readyState)) {
                    ready
                } else {
                    LiveSetup.Refused(
                        io.mazewall.platform.native.LiveFailure(
                            io.mazewall.platform.native.LiveFailureStatus.Stale,
                            io.mazewall.platform.native.LiveFailureReason.Shutdown,
                        ),
                    )
                }
        }

        @Suppress("TooGenericExceptionCaught") // Retain original owner and failure before reporting uncertain cleanup.
        private fun retainUnpublishedChecked(
            runtime: ManagedRuntime,
            preparation: io.mazewall.enforcer.internal.execution.ScopingPreparation,
            owner: io.mazewall.platform.native.InstallationOwner?,
            attached: Boolean,
            attachmentAttempted: Boolean,
            failure: Throwable?,
            beforeConstructionNotification: () -> Unit,
        ): Throwable? {
            var attachmentFailure: Throwable? = null
            var abandonmentFailure: Throwable? = null
            if (!attached && !attachmentAttempted) {
                try {
                    owner?.let { runtime.attachScopingOwner(preparation, it) }
                } catch (problem: Throwable) {
                    attachmentFailure = problem
                }
            }
            try {
                checkNotNull(runtime.checkedOperations).abandon(preparation, failure ?: attachmentFailure, beforeConstructionNotification)
            } catch (problem: Throwable) {
                abandonmentFailure = problem
            }
            val selectedFailure = selectCheckedFailure(failure, attachmentFailure, abandonmentFailure)
            if (failure is InterruptedException || attachmentFailure is InterruptedException || abandonmentFailure is InterruptedException) Thread.currentThread().interrupt()
            return selectedFailure
        }

        @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally") // Retain the exact owner and preserve fatal cleanup precedence.
        private fun captureCheckedAdmitted(
            runtime: ManagedRuntime,
            preparation: io.mazewall.enforcer.internal.execution.ScopingPreparation,
            inputs: CaptureInputs,
            reserved: LiveSetup<io.mazewall.platform.native.InstallationOwner>,
            afterReserve: () -> Unit,
            beforeConstructionNotification: () -> Unit,
        ): LiveSetup<io.mazewall.enforcer.api.execution.PreparedStackScoping> {
            var owner: io.mazewall.platform.native.InstallationOwner? = null
            var attached = false
            var attachmentAttempted = false
            var published = false
            var failure: Throwable? = null
            try {
                val staging = when (reserved) {
                    is LiveSetup.Refused -> return reserved
                    is LiveSetup.Ready -> reserved.value
                }
                owner = staging
                validateStaging(staging, staging.capability, inputs)
                afterReserve()
                attachmentAttempted = true
                runtime.attachScopingOwner(preparation, staging)
                attached = true
                val result = captureAttachedChecked(runtime, preparation, inputs, staging)
                published = result is LiveSetup.Ready
                return result
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                if (!published) {
                    val selectedFailure = retainUnpublishedChecked(
                        runtime,
                        preparation,
                        owner,
                        attached,
                        attachmentAttempted,
                        failure,
                        beforeConstructionNotification,
                    )
                    if (selectedFailure != null && selectedFailure !== failure) throw selectedFailure
                }
            }
        }
    }
}
