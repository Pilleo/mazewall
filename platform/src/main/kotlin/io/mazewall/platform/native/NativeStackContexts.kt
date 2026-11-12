// Actual Executable identity is the enrollment contract, not reflective access to implementation internals.
@file:Suppress("ForbiddenImport")

package io.mazewall.platform.native

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import java.lang.reflect.Executable

private const val SEMANTIC_ID_BITS = 60
private const val MAX_WORKERS = 256
private const val MAX_CLASSES = 512
private const val MAX_METHODS = 4096
private const val MAX_FRAMES = 128

internal interface NativeStackContextProvider {
    val stackContexts: NativeStackContexts
}

internal interface WorkerRegistrationReceipt

internal interface TaskBeginReceipt

private object UnsupportedWorkerReceipt : WorkerRegistrationReceipt

private object UnsupportedTaskReceipt : TaskBeginReceipt

private val unsupportedReceiptSetup = LiveSetup.Refused(
    LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.AdapterNotImplemented),
)

internal interface NativeStackContexts {
    fun reserve(capacity: WorkerCapacity): LiveSetup<InstallationOwner>

    fun enroll(
        owner: InstallationOwner,
        owners: List<Class<*>>,
        methods: List<Executable>,
    ): LiveSetup<Enrollment>

    fun seal(
        owner: InstallationOwner,
        enrollment: Enrollment,
    ): LiveSetup<InstallationOwner>

    fun registerCurrent(owner: InstallationOwner): LiveSetup<WorkerOwner>

    fun prepareWorkerReceipt(owner: InstallationOwner): WorkerRegistrationReceipt = UnsupportedWorkerReceipt

    fun registerCurrent(
        owner: InstallationOwner,
        receipt: WorkerRegistrationReceipt,
    ): LiveSetup<WorkerOwner> = unsupportedReceiptSetup

    fun recoverWorker(receipt: WorkerRegistrationReceipt): LiveSetup<WorkerOwner> = unsupportedReceiptSetup

    fun beginCurrent(worker: WorkerOwner): LiveSetup<TaskLease>

    fun prepareTaskReceipt(worker: WorkerOwner): TaskBeginReceipt = UnsupportedTaskReceipt

    fun beginCurrent(
        worker: WorkerOwner,
        receipt: TaskBeginReceipt,
    ): LiveSetup<TaskLease> = unsupportedReceiptSetup

    fun recoverTask(receipt: TaskBeginReceipt): LiveSetup<TaskLease> = unsupportedReceiptSetup

    fun endCurrent(task: TaskLease): LiveCompletion

    fun invalidate(owner: InstallationOwner): LiveCompletion

    fun retireWorker(worker: WorkerOwner): LiveCompletion

    fun retireInstallation(owner: InstallationOwner): LiveCompletion

    fun copy(
        task: TaskLease,
        expectedInvocation: InvocationId? = null,
    ): LiveRead
}

internal object UnsupportedNativeStackContexts : NativeStackContexts {
    private val failure = LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.AdapterNotImplemented)
    private val setup = LiveSetup.Refused(failure)
    private val completion = LiveCompletion.Refused(failure)

    override fun reserve(capacity: WorkerCapacity): LiveSetup<InstallationOwner> = setup

    override fun enroll(
        owner: InstallationOwner,
        owners: List<Class<*>>,
        methods: List<Executable>,
    ): LiveSetup<Enrollment> = setup

    override fun seal(
        owner: InstallationOwner,
        enrollment: Enrollment,
    ): LiveSetup<InstallationOwner> = setup

    override fun registerCurrent(owner: InstallationOwner): LiveSetup<WorkerOwner> = setup

    override fun beginCurrent(worker: WorkerOwner): LiveSetup<TaskLease> = setup

    override fun endCurrent(task: TaskLease): LiveCompletion = completion

    override fun invalidate(owner: InstallationOwner): LiveCompletion = completion

    override fun retireWorker(worker: WorkerOwner): LiveCompletion = completion

    override fun retireInstallation(owner: InstallationOwner): LiveCompletion = completion

    override fun copy(
        task: TaskLease,
        expectedInvocation: InvocationId?,
    ): LiveRead = LiveRead.Unsupported(LiveUnsupportedReason.AdapterNotImplemented)
}

internal fun NativeEngine.stackContextsOrUnsupported(): NativeStackContexts = (this as? NativeStackContextProvider)?.stackContexts ?: UnsupportedNativeStackContexts

// Incarnation values are native uint64 bits, not members of the semantic ID allocator.
@JvmInline
internal value class ProcessCookie(
    val value: ULong,
) {
    init {
        require(value != 0uL) { "Process cookie must be nonzero" }
    }
}

@JvmInline
internal value class ProcessStartTicks(
    val value: ULong,
) {
    init {
        require(value != 0uL) { "Process start ticks must be nonzero" }
    }
}

private fun requireSemanticId(value: Long) {
    require(value in 1..((1L shl SEMANTIC_ID_BITS) - 1)) { "Semantic ID outside native allocator bounds: $value" }
}

@JvmInline
internal value class InstallationId(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class WorkerId(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class TaskId(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class CodeGeneration(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class MethodToken(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class ClassToken(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class InvocationId(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class PublicationId(
    val value: Long,
) {
    init {
        requireSemanticId(value)
    }
}

@JvmInline
internal value class WorkerCapacity(
    val value: Int,
) {
    init {
        require(value in 1..MAX_WORKERS) { "Worker capacity outside 1..$MAX_WORKERS: $value" }
    }
}

@JvmInline
internal value class ClassCount(
    val value: Int,
) {
    init {
        require(value in 1..MAX_CLASSES) { "Class count outside 1..$MAX_CLASSES: $value" }
    }
}

@JvmInline
internal value class MethodCount(
    val value: Int,
) {
    init {
        require(value in 1..MAX_METHODS) { "Method count outside 1..$MAX_METHODS: $value" }
    }
}

@JvmInline
internal value class FrameCount(
    val value: Int,
) {
    init {
        require(value in 1..MAX_FRAMES) { "Frame count outside 1..$MAX_FRAMES: $value" }
    }
}

internal data class ProcessIdentity(
    val pid: Pid,
    val cookie: ProcessCookie,
    val startTicks: ProcessStartTicks,
) {
    init {
        require(pid.value > 0) { "Live process PID must be positive" }
    }
}

internal sealed interface InstallationState {
    data object Staging : InstallationState

    class Sealed(
        val enrollment: Enrollment,
    ) : InstallationState
}

/** Staging retains cleanup authority; sealing never mutates or replaces that owner. */
internal class InstallationOwner private constructor(
    val capability: NativeStackContexts,
    val process: ProcessIdentity,
    val installation: InstallationId,
    val capacity: WorkerCapacity,
    val state: InstallationState,
) {
    internal constructor(
        capability: NativeStackContexts,
        process: ProcessIdentity,
        installation: InstallationId,
        capacity: WorkerCapacity,
    ) : this(capability, process, installation, capacity, InstallationState.Staging)

    internal fun sealed(enrollment: Enrollment): InstallationOwner {
        require(state === InstallationState.Staging && enrollment.owner === this) { "Enrollment belongs to another staging owner" }
        return InstallationOwner(capability, process, installation, capacity, InstallationState.Sealed(enrollment))
    }
}

internal data class EnrolledClass(
    val token: ClassToken,
    val identity: Class<*>,
)

/** Metadata is projection only; the actual Executable and declaring Class carry identity. */
internal data class EnrolledMethod(
    val token: MethodToken,
    val identity: Executable,
    val name: String,
    val descriptor: String,
    val modifiers: Int,
)

internal class Enrollment(
    val owner: InstallationOwner,
    val generation: CodeGeneration,
    classes: List<EnrolledClass>,
    methods: List<EnrolledMethod>,
) {
    val classes: List<EnrolledClass> = java.util.List.copyOf(classes)
    val methods: List<EnrolledMethod> = java.util.List.copyOf(methods)
    val classCount: ClassCount = ClassCount(this.classes.size)
    val methodCount: MethodCount = MethodCount(this.methods.size)

    init {
        require(owner.state === InstallationState.Staging) { "Enrollment requires a staging owner" }
        require(
            this.classes
            .map { it.token }
            .distinct()
            .size == this.classes.size,
        ) { "Duplicate class token" }
        require(
            this.classes
            .map { it.identity }
            .distinct()
            .size == this.classes.size,
        ) { "Duplicate Class identity" }
        require(
            this.methods
            .map { it.token }
            .distinct()
            .size == this.methods.size,
        ) { "Duplicate method token" }
        require(
            this.methods
            .map { it.identity }
            .distinct()
            .size == this.methods.size,
        ) { "Duplicate Executable identity" }
        require(this.methods.all { method -> this.classes.any { it.identity === method.identity.declaringClass } }) {
            "Method declaring Class is not enrolled"
        }
    }
}

internal class WorkerOwner(
    val owner: InstallationOwner,
    val worker: WorkerId,
    val tid: Tid,
) {
    val enrollment: Enrollment

    init {
        require(tid.value > 0) { "Live worker TID must be positive" }
        val state = owner.state
        require(state is InstallationState.Sealed) { "Worker requires a sealed installation" }
        enrollment = state.enrollment
    }
}

internal class TaskLease(
    val worker: WorkerOwner,
    val task: TaskId,
)

internal data class LiveFrame(
    val method: MethodToken,
    val location: Long,
)

internal enum class LiveBinding { Open0, Openat0 }

/** Available is a complete owned copy, never a permission or containment decision. */
internal class LiveSnapshot(
    val task: TaskLease,
    val invocation: InvocationId,
    val publication: PublicationId,
    val binding: LiveBinding,
    frames: List<LiveFrame>,
) {
    val frames: List<LiveFrame> = java.util.List.copyOf(frames)
    val frameCount: FrameCount = FrameCount(this.frames.size)

    // v1 suppresses nesting rather than exposing a parent as restored authority.
    val parentInvocation: InvocationId? get() = null

    init {
        val tokens = task.worker.enrollment.methods
            .map { it.token }
            .toSet()
        require(this.frames.all { it.method in tokens }) { "Snapshot contains an unenrolled method token" }
    }
}

internal enum class LiveAbsentReason { NoTask, NoActiveInvocation }

internal enum class LiveIncompleteReason {
    CaptureFailed,
    Overflow,
    UnknownMethod,
    NestedOrCallback,
    MalformedNativeResult,
    InvalidArgument,
    IdExhausted,
    NativeFailure,
}

internal enum class LiveStaleReason { IdentityMismatch, GenerationInvalid, Shutdown, InstallationRevoked }

internal enum class LiveUnsupportedReason { MissingBinding, RuntimeMismatch, AbiMismatch, RetransformUnavailable, AdapterNotImplemented }

internal sealed interface LiveRead {
    data class Available(
        val snapshot: LiveSnapshot,
    ) : LiveRead

    data class Absent(
        val reason: LiveAbsentReason,
    ) : LiveRead

    data class Incomplete(
        val reason: LiveIncompleteReason,
    ) : LiveRead

    data object Busy : LiveRead

    data class Stale(
        val reason: LiveStaleReason,
    ) : LiveRead

    data class Unsupported(
        val reason: LiveUnsupportedReason,
    ) : LiveRead
}

internal enum class LiveFailureStatus { Busy, Stale, Unsupported, InvalidArgument, Exhausted, InternalFailure }

internal enum class LiveFailureReason {
    SlotContended,
    IdentityMismatch,
    GenerationInvalid,
    Shutdown,
    MissingBinding,
    RuntimeMismatch,
    AbiMismatch,
    Capacity,
    IdExhausted,
    Malformed,
    RegistrationMissing,
    NativeFailure,
    RetransformUnavailable,
    InstallationRevoked,
    NotPhysicallyRetired,
    CleanupPending,
    AdapterNotImplemented,
}

internal data class LiveFailure(
    val status: LiveFailureStatus,
    val reason: LiveFailureReason,
    val cause: Throwable? = null,
)

internal sealed interface LiveSetup<out T> {
    data class Ready<T>(
        val value: T,
    ) : LiveSetup<T>

    data class Refused(
        val failure: LiveFailure,
    ) : LiveSetup<Nothing>
}

internal enum class LivePendingReason { SlotContended, NotPhysicallyRetired, CleanupPending }

internal sealed interface LiveCompletion {
    data object Complete : LiveCompletion

    data class Pending(
        val reason: LivePendingReason,
    ) : LiveCompletion

    data class Refused(
        val failure: LiveFailure,
    ) : LiveCompletion
}
