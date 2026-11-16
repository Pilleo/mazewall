// Actual Executable objects are required by JNI enrollment, not reflective implementation access.
@file:Suppress("ForbiddenImport")

package io.mazewall.ffi.internal

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.ClassToken
import io.mazewall.platform.native.CodeGeneration
import io.mazewall.platform.native.EnrolledClass
import io.mazewall.platform.native.EnrolledMethod
import io.mazewall.platform.native.Enrollment
import io.mazewall.platform.native.InstallationId
import io.mazewall.platform.native.InstallationOwner
import io.mazewall.platform.native.InstallationState
import io.mazewall.platform.native.InvocationId
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.LiveAbsentReason
import io.mazewall.platform.native.LiveBinding
import io.mazewall.platform.native.LiveCompletion
import io.mazewall.platform.native.LiveFailure
import io.mazewall.platform.native.LiveFailureReason
import io.mazewall.platform.native.LiveFailureStatus
import io.mazewall.platform.native.LiveFrame
import io.mazewall.platform.native.LiveIncompleteReason
import io.mazewall.platform.native.LivePendingReason
import io.mazewall.platform.native.LiveRead
import io.mazewall.platform.native.LiveSetup
import io.mazewall.platform.native.LiveSnapshot
import io.mazewall.platform.native.LiveStaleReason
import io.mazewall.platform.native.LiveUnsupportedReason
import io.mazewall.platform.native.MethodToken
import io.mazewall.platform.native.NativeStackContexts
import io.mazewall.platform.native.ProcessCookie
import io.mazewall.platform.native.ProcessIdentity
import io.mazewall.platform.native.ProcessStartTicks
import io.mazewall.platform.native.PublicationId
import io.mazewall.platform.native.RuntimeSnapshot
import io.mazewall.platform.native.TaskBeginReceipt
import io.mazewall.platform.native.TaskId
import io.mazewall.platform.native.TaskLease
import io.mazewall.platform.native.WorkerCapacity
import io.mazewall.platform.native.WorkerId
import io.mazewall.platform.native.WorkerOwner
import io.mazewall.platform.native.WorkerRegistrationReceipt
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.lang.ref.Reference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val LIVE_ABI_VERSION = 1
private const val LIVE_MAX_WORKERS = 256
private const val LIVE_MAX_CLASSES = 512
private const val LIVE_MAX_METHODS = 4096
private const val LIVE_MAX_FRAMES = 128
private const val LIVE_MAX_ID = 1152921504606846975L
private const val LIVE_ARCH_X86_64 = 1
private const val OPEN_ROUTE = 2
private const val OPEN0_BINDING = 1
private const val OPENAT0_BINDING = 2
private const val ALL_BINDINGS = 3
private const val PROCESS_TUPLE_SIZE = 3
private const val COOKIE_INDEX = 0
private const val START_TICKS_INDEX = 1
private const val PID_INDEX = 2
private const val GENERATION_INDEX = 0
private const val CLASS_COUNT_INDEX = 1
private const val METHOD_COUNT_INDEX = 2
private const val ENROLLMENT_TOKEN_START = 3

private const val STATUS_OK = 0
private const val STATUS_ABSENT = 1
private const val STATUS_INCOMPLETE = 2
private const val STATUS_BUSY = 3
private const val STATUS_STALE = 4
private const val STATUS_UNSUPPORTED = 5
private const val STATUS_INVALID_ARGUMENT = 6
private const val STATUS_EXHAUSTED = 7
private const val STATUS_INTERNAL_FAILURE = 8

private const val REASON_NONE = 0
private const val REASON_NO_TASK = 1
private const val REASON_NO_ACTIVE_INVOCATION = 2
private const val REASON_CAPTURE_FAILED = 3
private const val REASON_OVERFLOW = 4
private const val REASON_UNKNOWN_METHOD = 5
private const val REASON_NESTED_OR_CALLBACK = 6
private const val REASON_SLOT_CONTENDED = 7
private const val REASON_IDENTITY_MISMATCH = 8
private const val REASON_GENERATION_INVALID = 9
private const val REASON_SHUTDOWN = 10
private const val REASON_MISSING_BINDING = 11
private const val REASON_RUNTIME_MISMATCH = 12
private const val REASON_ABI_MISMATCH = 13
private const val REASON_CAPACITY = 14
private const val REASON_ID_EXHAUSTED = 15
private const val REASON_MALFORMED = 16
private const val REASON_NATIVE_FAILURE = 18
private const val REASON_RETRANSFORM_UNAVAILABLE = 19
private const val REASON_INSTALLATION_REVOKED = 20
private const val REASON_NOT_PHYSICALLY_RETIRED = 21
private const val REASON_CLEANUP_PENDING = 22

internal object LiveStackLayouts {
    val request: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_bytes"),
        JAVA_INT.withName("operation"),
        JAVA_INT.withName("reserved0"),
        JAVA_LONG.withName("process_cookie"),
        JAVA_LONG.withName("process_start_ticks"),
        JAVA_LONG.withName("installation_id"),
        JAVA_LONG.withName("worker_id"),
        JAVA_LONG.withName("task_id"),
        JAVA_LONG.withName("code_generation"),
        JAVA_LONG.withName("invocation_id"),
        JAVA_INT.withName("tgid"),
        JAVA_INT.withName("tid"),
        JAVA_INT.withName("worker_capacity"),
        JAVA_INT.withName("reserved1"),
    )
    val frame: StructLayout = MemoryLayout.structLayout(JAVA_LONG.withName("method_token"), JAVA_LONG.withName("location"))
    val snapshot: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_bytes"),
        JAVA_INT.withName("status"),
        JAVA_INT.withName("reason"),
        JAVA_LONG.withName("process_cookie"),
        JAVA_LONG.withName("process_start_ticks"),
        JAVA_LONG.withName("installation_id"),
        JAVA_LONG.withName("worker_id"),
        JAVA_LONG.withName("task_id"),
        JAVA_LONG.withName("code_generation"),
        JAVA_LONG.withName("invocation_id"),
        JAVA_LONG.withName("parent_invocation_id"),
        JAVA_LONG.withName("publication_id"),
        JAVA_INT.withName("tgid"),
        JAVA_INT.withName("tid"),
        JAVA_INT.withName("binding"),
        JAVA_INT.withName("frame_count"),
        MemoryLayout.sequenceLayout(LIVE_MAX_FRAMES.toLong(), frame).withName("frames"),
    )
    val receipt: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_bytes"),
        JAVA_INT.withName("status"),
        JAVA_INT.withName("reason"),
        JAVA_LONG.withName("process_cookie"),
        JAVA_LONG.withName("process_start_ticks"),
        JAVA_LONG.withName("installation_id"),
        JAVA_LONG.withName("worker_id"),
        JAVA_LONG.withName("task_id"),
        JAVA_LONG.withName("code_generation"),
        JAVA_LONG.withName("invocation_id"),
        JAVA_INT.withName("tgid"),
        JAVA_INT.withName("tid"),
        JAVA_INT.withName("worker_capacity"),
        JAVA_INT.withName("allocated_slots"),
    )
    val info: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_bytes"),
        JAVA_INT.withName("status"),
        JAVA_INT.withName("reason"),
        JAVA_LONG.withName("process_cookie"),
        JAVA_LONG.withName("process_start_ticks"),
        JAVA_INT.withName("tgid"),
        JAVA_INT.withName("arch"),
        JAVA_INT.withName("max_workers"),
        JAVA_INT.withName("max_classes"),
        JAVA_INT.withName("max_methods"),
        JAVA_INT.withName("max_frames"),
        JAVA_INT.withName("binding_mask"),
        JAVA_INT.withName("reserved"),
    )

    val requestBytes = request.byteSize().toInt()
    val snapshotBytes = snapshot.byteSize().toInt()
    val receiptBytes = receipt.byteSize().toInt()
    val infoBytes = info.byteSize().toInt()
    val alignment = info.byteAlignment()
    val versionOffset = info.byteOffset(groupElement("abi_version"))
    val bytesOffset = info.byteOffset(groupElement("struct_bytes"))
    val statusOffset = info.byteOffset(groupElement("status"))
    val reasonOffset = info.byteOffset(groupElement("reason"))
    val payloadOffset = info.byteOffset(groupElement("process_cookie"))

    val requestVersion = request.byteOffset(groupElement("abi_version"))
    val requestSize = request.byteOffset(groupElement("struct_bytes"))
    val requestOperation = request.byteOffset(groupElement("operation"))
    val requestCookie = request.byteOffset(groupElement("process_cookie"))
    val requestStartTicks = request.byteOffset(groupElement("process_start_ticks"))
    val requestInstallation = request.byteOffset(groupElement("installation_id"))
    val requestWorker = request.byteOffset(groupElement("worker_id"))
    val requestTask = request.byteOffset(groupElement("task_id"))
    val requestGeneration = request.byteOffset(groupElement("code_generation"))
    val requestInvocation = request.byteOffset(groupElement("invocation_id"))
    val requestPid = request.byteOffset(groupElement("tgid"))
    val requestTid = request.byteOffset(groupElement("tid"))
    val requestCapacity = request.byteOffset(groupElement("worker_capacity"))

    val snapshotCookie = snapshot.byteOffset(groupElement("process_cookie"))
    val snapshotStartTicks = snapshot.byteOffset(groupElement("process_start_ticks"))
    val snapshotInstallation = snapshot.byteOffset(groupElement("installation_id"))
    val snapshotWorker = snapshot.byteOffset(groupElement("worker_id"))
    val snapshotTask = snapshot.byteOffset(groupElement("task_id"))
    val snapshotGeneration = snapshot.byteOffset(groupElement("code_generation"))
    val snapshotInvocation = snapshot.byteOffset(groupElement("invocation_id"))
    val snapshotParent = snapshot.byteOffset(groupElement("parent_invocation_id"))
    val snapshotPublication = snapshot.byteOffset(groupElement("publication_id"))
    val snapshotPid = snapshot.byteOffset(groupElement("tgid"))
    val snapshotTid = snapshot.byteOffset(groupElement("tid"))
    val snapshotBinding = snapshot.byteOffset(groupElement("binding"))
    val snapshotCount = snapshot.byteOffset(groupElement("frame_count"))
    val snapshotFrames = snapshot.byteOffset(groupElement("frames"))
    val frameStride = frame.byteSize()
    val frameToken = frame.byteOffset(groupElement("method_token"))
    val frameLocation = frame.byteOffset(groupElement("location"))

    val receiptCookie = receipt.byteOffset(groupElement("process_cookie"))
    val receiptStartTicks = receipt.byteOffset(groupElement("process_start_ticks"))
    val receiptInstallation = receipt.byteOffset(groupElement("installation_id"))
    val receiptWorker = receipt.byteOffset(groupElement("worker_id"))
    val receiptTask = receipt.byteOffset(groupElement("task_id"))
    val receiptGeneration = receipt.byteOffset(groupElement("code_generation"))
    val receiptInvocation = receipt.byteOffset(groupElement("invocation_id"))
    val receiptPid = receipt.byteOffset(groupElement("tgid"))
    val receiptTid = receipt.byteOffset(groupElement("tid"))
    val receiptCapacity = receipt.byteOffset(groupElement("worker_capacity"))
    val receiptSlots = receipt.byteOffset(groupElement("allocated_slots"))

    val infoCookie = info.byteOffset(groupElement("process_cookie"))
    val infoStartTicks = info.byteOffset(groupElement("process_start_ticks"))
    val infoPid = info.byteOffset(groupElement("tgid"))
    val infoArch = info.byteOffset(groupElement("arch"))
    val infoWorkers = info.byteOffset(groupElement("max_workers"))
    val infoClasses = info.byteOffset(groupElement("max_classes"))
    val infoMethods = info.byteOffset(groupElement("max_methods"))
    val infoFrames = info.byteOffset(groupElement("max_frames"))
    val infoBindings = info.byteOffset(groupElement("binding_mask"))
    val infoReserved = info.byteOffset(groupElement("reserved"))
}

internal interface LiveStackBridge {
    fun enroll(
        installation: Long,
        owners: Array<Class<*>>,
        methods: Array<Executable>,
    ): LongArray

    fun checkSeal(
        installation: Long,
        generation: Long,
    )

    fun prepareWorker()
}

internal class RealNativeStackContexts private constructor(
    private val binding: Binding,
    private val runtime: RuntimeSnapshot,
    private val process: ProcessIdentity,
    private val bridge: LiveStackBridge,
    private val bindingMask: Int,
    private val afterValidated: (Int) -> Unit,
) : NativeStackContexts {
    private constructor(binding: Binding, runtime: RuntimeSnapshot, process: ProcessIdentity, bridge: LiveStackBridge, bindingMask: Int) :
        this(binding, runtime, process, bridge, bindingMask, noAfterValidated)

    internal sealed interface BootstrapResult {
        sealed interface Checked : BootstrapResult {
            fun contextsFor(
                identity: LiveStackBootstrap.LiveDeploymentIdentity,
                runtime: RuntimeSnapshot,
            ): NativeStackContexts

            fun revalidate(
                identity: LiveStackBootstrap.LiveDeploymentIdentity,
                runtime: RuntimeSnapshot,
            ): BootstrapResult
        }

        data class Refused(
            val failure: LiveFailure,
        ) : BootstrapResult
    }

    private enum class Operation(
        val wire: Int,
    ) {
        Copy(0),
        Reserve(1),
        Seal(2),
        Register(3),
        Begin(4),
        End(5),
        Invalidate(6),
        RetireWorker(7),
        RetireInstallation(8),
    }

    private class MalformedResult : IllegalStateException("Malformed live native result")

    private class Corruption(
        val failure: LiveFailure,
        val owner: InstallationOwner?,
    ) {
        val invalidation = AtomicReference<LiveCompletion?>()
    }

    private val corruption = AtomicReference<Corruption?>()
    private val receiptKey = Any()

    internal fun matchesReceiptKey(candidate: Any): Boolean = candidate === receiptKey

    private class Binding(
        val arena: Arena,
        val lookup: SymbolLookup,
        val info: MethodHandle,
        val lifecycle: MethodHandle,
        val copy: MethodHandle,
    )

    private data class Receipt(
        val installation: Long,
        val worker: Long,
        val task: Long,
    )

    private enum class ReceiptPhase { NEW, PREPARING, ATTEMPTING, UNVERIFIED, VALIDATED, ATTACHED, TERMINAL }

    private class AttachedReceipt(
        private val adapter: RealNativeStackContexts,
        private val key: Any,
        private val retained: Binding,
        private val runtime: RuntimeSnapshot,
        private val bridge: LiveStackBridge,
        private val corruption: AtomicReference<Corruption?>,
        private val owner: InstallationOwner,
        private val worker: WorkerOwner?,
        private val afterValidated: (Int) -> Unit,
    ) : WorkerRegistrationReceipt,
        TaskBeginReceipt {
        private val thread = Thread.currentThread()
        private val process = owner.process
        private val enrollment = (owner.state as InstallationState.Sealed).enrollment
        private var phase = ReceiptPhase.NEW
        private var id = 0L
        private var rawStatus = 0
        private var rawId = 0L
        private var tid: Tid? = null
        private var workerResult: LiveSetup.Ready<WorkerOwner>? = null
        private var taskResult: LiveSetup.Ready<TaskLease>? = null
        private var attachedWorker: WorkerOwner? = null
        private var attachedTask: TaskLease? = null

        private fun validAdapter(): Boolean =
            adapter.matchesReceiptKey(key) &&
                owner.capability === adapter &&
                owner.process == process &&
                (owner.state as? InstallationState.Sealed)?.enrollment === enrollment &&
                (worker == null || worker.owner === owner && worker.enrollment === enrollment)

        fun matches(
            expectedAdapter: RealNativeStackContexts,
            expectedOwner: InstallationOwner,
            expectedWorker: WorkerOwner?,
        ): Boolean = adapter === expectedAdapter && owner === expectedOwner && worker === expectedWorker && validAdapter()

        fun matchesAdapter(expectedAdapter: RealNativeStackContexts): Boolean = adapter === expectedAdapter && validAdapter()

        fun register(): LiveSetup<WorkerOwner> {
            require(worker == null && validAdapter() && Thread.currentThread() === thread && !thread.isVirtual && phase == ReceiptPhase.NEW)
            return perform(Operation.Register) { recoverWorker() }
        }

        fun begin(): LiveSetup<TaskLease> {
            val originalWorker = requireNotNull(worker)
            require(validAdapter() && Thread.currentThread() === thread && !thread.isVirtual && phase == ReceiptPhase.NEW)
            require(runtime.process.gettid() == originalWorker.tid) { "Task worker TID differs from current thread" }
            return perform(Operation.Begin) { recoverTask() }
        }

        @Suppress("TooGenericExceptionCaught") // Native exceptions are retained; Errors preserve exact identity and carrier state.
        private fun <T> perform(operation: Operation, finish: () -> LiveSetup<T>): LiveSetup<T> {
            corruption.get()?.let { return LiveSetup.Refused(it.failure) }
            return try {
                if (operation == Operation.Register) {
                    phase = ReceiptPhase.PREPARING
                    bridge.prepareWorker()
                    tid = runtime.process.gettid().also { require(it.value > 0) }
                } else {
                    tid = checkNotNull(worker).tid
                }
                val currentTid = checkNotNull(tid)
                Arena.ofConfined().use { arena ->
                    val request = LifecycleCodec.request(process, arena, operation, owner, worker, generation = enrollment.generation, tid = currentTid)
                    val input = request.toArray(JAVA_INT)
                    val output = arena.allocate(LiveStackLayouts.receipt)
                    phase = ReceiptPhase.ATTEMPTING
                    val raw = SyscallInvoker.liveBufferStatus(retained.lifecycle, request, LiveStackLayouts.requestBytes, output, LiveStackLayouts.receiptBytes)
                    rawStatus = raw
                    rawId = output.get(JAVA_LONG, if (operation == Operation.Register) LiveStackLayouts.receiptWorker else LiveStackLayouts.receiptTask)
                    phase = ReceiptPhase.UNVERIFIED
                    nativeCheck(input.contentEquals(request.toArray(JAVA_INT)))
                    val reason = header(output, LiveStackLayouts.receiptBytes, raw)
                    nativeCheck(legalPair(operation, raw, reason))
                    if (raw != STATUS_OK) {
                        zeroPayload(output)
                        phase = ReceiptPhase.TERMINAL
                        throw NativeRefusal(failure(raw, reason))
                    }
                    LifecycleCodec.validate(output, operation, process, owner, worker, null, null, enrollment.generation, currentTid)
                    id = rawId
                    phase = ReceiptPhase.VALIDATED
                    afterValidated(operation.wire)
                    finish()
                }
            } catch (refused: NativeRefusal) {
                LiveSetup.Refused(refused.failure)
            } catch (malformed: MalformedResult) {
                val failed = Corruption(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.Malformed, malformed), owner)
                if (corruption.compareAndSet(null, failed)) failed.invalidation.set(owner.capability.invalidate(owner))
                LiveSetup.Refused(failed.failure)
            } catch (invalid: IllegalArgumentException) {
                LiveSetup.Refused(LiveFailure(LiveFailureStatus.InvalidArgument, LiveFailureReason.Malformed, invalid))
            } catch (exception: Exception) {
                LiveSetup.Refused(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.NativeFailure, exception))
            } finally {
                Reference.reachabilityFence(retained)
            }
        }

        @Synchronized
        fun recoverWorker(): LiveSetup<WorkerOwner> {
            require(worker == null && validAdapter() && (Thread.currentThread() === thread || thread.state == Thread.State.TERMINATED && !thread.isAlive))
            require(phase == ReceiptPhase.VALIDATED || phase == ReceiptPhase.ATTACHED)
            workerResult?.let { return it }
            val value = attachedWorker ?: WorkerOwner(owner, WorkerId(id), checkNotNull(tid)).also { attachedWorker = it }
            return LiveSetup.Ready(value).also {
                workerResult = it
                phase = ReceiptPhase.ATTACHED
            }
        }

        fun recoverTask(): LiveSetup<TaskLease> {
            require(worker != null && validAdapter() && Thread.currentThread() === thread)
            require(phase == ReceiptPhase.VALIDATED || phase == ReceiptPhase.ATTACHED)
            taskResult?.let { return it }
            val value = attachedTask ?: TaskLease(worker, TaskId(id)).also { attachedTask = it }
            return LiveSetup.Ready(value).also {
                taskResult = it
                phase = ReceiptPhase.ATTACHED
            }
        }
    }

    override fun prepareWorkerReceipt(owner: InstallationOwner): WorkerRegistrationReceipt {
        require(owns(owner) && owner.state is InstallationState.Sealed && !Thread.currentThread().isVirtual)
        return AttachedReceipt(this, receiptKey, binding, runtime, bridge, corruption, owner, null, afterValidated)
    }

    override fun registerCurrent(
        owner: InstallationOwner,
        receipt: WorkerRegistrationReceipt,
    ): LiveSetup<WorkerOwner> {
        require(receipt is AttachedReceipt && receipt.matches(this, owner, null))
        return receipt.register()
    }

    override fun recoverWorker(receipt: WorkerRegistrationReceipt): LiveSetup<WorkerOwner> {
        require(receipt is AttachedReceipt && receipt.matchesAdapter(this))
        return receipt.recoverWorker()
    }

    override fun prepareTaskReceipt(worker: WorkerOwner): TaskBeginReceipt {
        require(owns(worker.owner) && !Thread.currentThread().isVirtual && runtime.process.gettid() == worker.tid)
        return AttachedReceipt(this, receiptKey, binding, runtime, bridge, corruption, worker.owner, worker, afterValidated)
    }

    override fun beginCurrent(
        worker: WorkerOwner,
        receipt: TaskBeginReceipt,
    ): LiveSetup<TaskLease> {
        require(receipt is AttachedReceipt && receipt.matches(this, worker.owner, worker))
        return receipt.begin()
    }

    override fun recoverTask(receipt: TaskBeginReceipt): LiveSetup<TaskLease> {
        require(receipt is AttachedReceipt && receipt.matchesAdapter(this))
        return receipt.recoverTask()
    }

    private object LifecycleCodec {
        fun request(
            process: ProcessIdentity,
            arena: Arena,
            operation: Operation,
            owner: InstallationOwner?,
            worker: WorkerOwner? = null,
            task: TaskLease? = null,
            capacity: WorkerCapacity? = null,
            generation: CodeGeneration? = null,
            tid: Tid? = null,
            invocation: InvocationId? = null,
        ): MemorySegment =
        arena.allocate(LiveStackLayouts.request).also { request ->
        request.fill(0)
        request.set(JAVA_INT, LiveStackLayouts.requestVersion, LIVE_ABI_VERSION)
        request.set(JAVA_INT, LiveStackLayouts.requestSize, LiveStackLayouts.requestBytes)
        request.set(JAVA_INT, LiveStackLayouts.requestOperation, operation.wire)
        request.set(JAVA_LONG, LiveStackLayouts.requestCookie, process.cookie.value.toLong())
        request.set(JAVA_LONG, LiveStackLayouts.requestStartTicks, process.startTicks.value.toLong())
        request.set(JAVA_LONG, LiveStackLayouts.requestInstallation, owner?.installation?.value ?: 0)
        request.set(JAVA_LONG, LiveStackLayouts.requestWorker, worker?.worker?.value ?: 0)
        request.set(JAVA_LONG, LiveStackLayouts.requestTask, task?.task?.value ?: 0)
        request.set(JAVA_LONG, LiveStackLayouts.requestGeneration, generation?.value ?: worker?.enrollment?.generation?.value ?: 0)
        request.set(JAVA_LONG, LiveStackLayouts.requestInvocation, invocation?.value ?: 0)
        request.set(JAVA_INT, LiveStackLayouts.requestPid, process.pid.value)
        request.set(JAVA_INT, LiveStackLayouts.requestTid, tid?.value ?: worker?.tid?.value ?: 0)
        request.set(JAVA_INT, LiveStackLayouts.requestCapacity, capacity?.value ?: 0)
    }

        fun validate(
            output: MemorySegment,
            operation: Operation,
            process: ProcessIdentity,
            owner: InstallationOwner?,
            worker: WorkerOwner?,
            task: TaskLease?,
            capacity: WorkerCapacity?,
            generation: CodeGeneration?,
            tid: Tid?,
        ) {
            nativeCheck(
                output.get(
                    JAVA_LONG,
                    LiveStackLayouts.receiptCookie,
                ) == process.cookie.value.toLong() &&
                    output.get(JAVA_LONG, LiveStackLayouts.receiptStartTicks) == process.startTicks.value.toLong(),
            )
            nativeCheck(output.get(JAVA_INT, LiveStackLayouts.receiptPid) == process.pid.value)
            validateIds(output, operation, owner, worker, task)
            val expectedGeneration = generation?.value ?: worker?.enrollment?.generation?.value ?: 0
            nativeCheck(output.get(JAVA_LONG, LiveStackLayouts.receiptGeneration) == expectedGeneration && output.get(JAVA_LONG, LiveStackLayouts.receiptInvocation) == 0L)
            nativeCheck(output.get(JAVA_INT, LiveStackLayouts.receiptTid) == (tid?.value ?: worker?.tid?.value ?: 0))
            val retainedCapacity = when (operation) {
                Operation.Reserve -> checkNotNull(capacity).value
                Operation.Seal, Operation.Register, Operation.RetireWorker -> checkNotNull(owner).capacity.value
                Operation.Copy, Operation.Begin, Operation.End, Operation.Invalidate, Operation.RetireInstallation -> 0
            }
            nativeCheck(output.get(JAVA_INT, LiveStackLayouts.receiptCapacity) == retainedCapacity && output.get(JAVA_INT, LiveStackLayouts.receiptSlots) == retainedCapacity)
        }

        private fun validateIds(
            output: MemorySegment,
            operation: Operation,
            owner: InstallationOwner?,
            worker: WorkerOwner?,
            task: TaskLease?,
        ) {
            val installation = output.get(JAVA_LONG, LiveStackLayouts.receiptInstallation)
            val workerId = output.get(JAVA_LONG, LiveStackLayouts.receiptWorker)
            val taskId = output.get(JAVA_LONG, LiveStackLayouts.receiptTask)
            nativeCheck(if (operation == Operation.Reserve) semanticId(installation) else installation == checkNotNull(owner).installation.value)
            nativeCheck(if (operation == Operation.Register) semanticId(workerId) else workerId == (worker?.worker?.value ?: 0))
            nativeCheck(if (operation == Operation.Begin) semanticId(taskId) else taskId == (task?.task?.value ?: 0))
            if (operation == Operation.Register) nativeCheck(workerId !in knownIds(checkNotNull(owner), null, null))
            if (operation == Operation.Begin) nativeCheck(taskId !in knownIds(checkNotNull(owner), worker, null))
        }

        fun knownIds(
            owner: InstallationOwner,
            worker: WorkerOwner?,
            task: TaskLease?,
        ): Set<Long> {
            val enrollment = when (val state = owner.state) {
                InstallationState.Staging -> null
                is InstallationState.Sealed -> state.enrollment
            }
            return buildSet {
                add(owner.installation.value)
                enrollment?.let {
                    add(it.generation.value)
                    addAll(it.classes.map { entry -> entry.token.value })
                    addAll(it.methods.map { entry -> entry.token.value })
                }
                worker?.let { add(it.worker.value) }
                task?.let { add(it.task.value) }
            }
        }
    }

    override fun reserve(capacity: WorkerCapacity): LiveSetup<InstallationOwner> =
        setup(null) {
            val receipt = lifecycle(Operation.Reserve, capacity = capacity)
            InstallationOwner(this, process, InstallationId(receipt.installation), capacity)
        }

    override fun enroll(
        owner: InstallationOwner,
        owners: List<Class<*>>,
        methods: List<Executable>,
    ): LiveSetup<Enrollment> =
        setup(owner) {
            require(owner.state === InstallationState.Staging) { "Enrollment needs its staging owner" }
            val classes = owners.toTypedArray()
            val executables = methods.toTypedArray()
            require(classes.size in 1..LIVE_MAX_CLASSES && executables.size in 1..LIVE_MAX_METHODS)
            require(classes.distinct().size == classes.size && executables.distinct().size == executables.size)
            require(executables.all { method -> classes.any { it === method.declaringClass } })
            val tuple = bridge.enroll(owner.installation.value, classes.copyOf(), executables.copyOf()).copyOf()
            nativeCheck(tuple.size == ENROLLMENT_TOKEN_START + classes.size + executables.size)
            nativeCheck(tuple[CLASS_COUNT_INDEX] == classes.size.toLong() && tuple[METHOD_COUNT_INDEX] == executables.size.toLong())
            nativeCheck(tuple.all { semanticId(it) })
            nativeCheck(tuple[GENERATION_INDEX] != owner.installation.value && tuple.drop(ENROLLMENT_TOKEN_START).none { it == owner.installation.value })
            val classTokens = tuple.slice(ENROLLMENT_TOKEN_START until ENROLLMENT_TOKEN_START + classes.size)
            val methodTokens = tuple.slice(ENROLLMENT_TOKEN_START + classes.size until tuple.size)
            nativeCheck(classTokens.distinct().size == classes.size && methodTokens.distinct().size == executables.size)
            nativeCheck((classTokens + methodTokens + tuple[GENERATION_INDEX]).distinct().size == classes.size + executables.size + 1)
            Enrollment(
                owner,
                CodeGeneration(tuple[GENERATION_INDEX]),
                classes.mapIndexed { index, type -> EnrolledClass(ClassToken(classTokens[index]), type) },
                executables.mapIndexed { index, method ->
                    val returnType = if (method is Method) method.returnType else Void.TYPE
                    EnrolledMethod(
                        MethodToken(methodTokens[index]),
                        method,
                        if (method is Method) method.name else "<init>",
                        MethodType.methodType(returnType, method.parameterTypes.toList()).descriptorString(),
                        method.modifiers,
                    )
                },
            )
        }

    override fun seal(
        owner: InstallationOwner,
        enrollment: Enrollment,
    ): LiveSetup<InstallationOwner> =
        setup(owner) {
            require(enrollment.owner === owner && owner.state === InstallationState.Staging)
            bridge.checkSeal(owner.installation.value, enrollment.generation.value)
            lifecycle(Operation.Seal, owner, generation = enrollment.generation)
            owner.sealed(enrollment)
        }

    override fun registerCurrent(owner: InstallationOwner): LiveSetup<WorkerOwner> =
        setup(owner) {
            val state = owner.state
            require(state is InstallationState.Sealed)
            require(!Thread.currentThread().isVirtual) { "Live worker must be a platform thread" }
            bridge.prepareWorker()
            val tid = runtime.process.gettid()
            require(tid.value > 0)
            val receipt = lifecycle(Operation.Register, owner, generation = state.enrollment.generation, tid = tid)
            WorkerOwner(owner, WorkerId(receipt.worker), tid)
        }

    override fun beginCurrent(worker: WorkerOwner): LiveSetup<TaskLease> =
        setup(worker.owner) {
            val receipt = lifecycle(Operation.Begin, worker.owner, worker)
            TaskLease(worker, TaskId(receipt.task))
        }

    override fun endCurrent(task: TaskLease): LiveCompletion =
        completion(task.worker.owner) {
        lifecycle(Operation.End, task.worker.owner, task.worker, task)
    }

    override fun invalidate(owner: InstallationOwner): LiveCompletion = completion(owner) { lifecycle(Operation.Invalidate, owner) }

    override fun retireWorker(worker: WorkerOwner): LiveCompletion =
        completion(worker.owner) {
        lifecycle(Operation.RetireWorker, worker.owner, worker)
    }

    override fun retireInstallation(owner: InstallationOwner): LiveCompletion = completion(owner) { lifecycle(Operation.RetireInstallation, owner) }

    override fun copy(
        task: TaskLease,
        expectedInvocation: InvocationId?,
    ): LiveRead {
        if (!owns(task.worker.owner)) return LiveRead.Stale(LiveStaleReason.IdentityMismatch)
        if (corruption.get() != null) return LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult)
        val retained = binding
        return try {
            Arena.ofConfined().use { arena ->
                val request = request(arena, Operation.Copy, task.worker.owner, task.worker, task, invocation = expectedInvocation)
                val input = request.toArray(JAVA_INT)
                val output = arena.allocate(LiveStackLayouts.snapshot)
                val raw = SyscallInvoker.liveBufferStatus(retained.copy, request, LiveStackLayouts.requestBytes, output, LiveStackLayouts.snapshotBytes)
                nativeCheck(input.contentEquals(request.toArray(JAVA_INT)))
                val reason = header(output, LiveStackLayouts.snapshotBytes, raw)
                nativeCheck(legalPair(Operation.Copy, raw, reason))
                if (raw != STATUS_OK) {
                    zeroPayload(output)
                    readFailure(raw, reason)
                } else {
                    LiveRead.Available(decodeSnapshot(output, task, expectedInvocation))
                }
            }
        } catch (malformed: MalformedResult) {
            poison(task.worker.owner, malformed)
            LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult)
        } finally {
            Reference.reachabilityFence(retained)
        }
    }

    private fun checkSnapshotIdentity(
        output: MemorySegment,
        task: TaskLease,
    ) {
        nativeCheck(
            output.get(JAVA_LONG, LiveStackLayouts.snapshotCookie) == process.cookie.value.toLong() && output.get(JAVA_LONG, LiveStackLayouts.snapshotStartTicks) == process.startTicks.value.toLong(),
        )
        nativeCheck(
            output.get(JAVA_LONG, LiveStackLayouts.snapshotInstallation) == task.worker.owner.installation.value && output.get(JAVA_LONG, LiveStackLayouts.snapshotWorker) == task.worker.worker.value,
        )
        nativeCheck(output.get(JAVA_LONG, LiveStackLayouts.snapshotTask) == task.task.value && output.get(JAVA_LONG, LiveStackLayouts.snapshotGeneration) == task.worker.enrollment.generation.value)
        nativeCheck(output.get(JAVA_INT, LiveStackLayouts.snapshotPid) == process.pid.value && output.get(JAVA_INT, LiveStackLayouts.snapshotTid) == task.worker.tid.value)
    }

    private fun decodeSnapshot(
        output: MemorySegment,
        task: TaskLease,
        expectedInvocation: InvocationId?,
    ): LiveSnapshot {
        checkSnapshotIdentity(output, task)
        val invocation = output.get(JAVA_LONG, LiveStackLayouts.snapshotInvocation)
        val publication = output.get(JAVA_LONG, LiveStackLayouts.snapshotPublication)
        nativeCheck(semanticId(invocation) && semanticId(publication) && output.get(JAVA_LONG, LiveStackLayouts.snapshotParent) == 0L)
        val retainedIds = knownIds(task.worker.owner, task.worker, task)
        nativeCheck(invocation !in retainedIds && publication !in retainedIds && publication != invocation)
        nativeCheck(expectedInvocation == null || invocation == expectedInvocation.value)
        val selected = output.get(JAVA_INT, LiveStackLayouts.snapshotBinding)
        nativeCheck(selected in OPEN0_BINDING..OPENAT0_BINDING && (bindingMask and (1 shl (selected - 1))) != 0)
        val frames = decodeFrames(output, task.worker.enrollment)
        return LiveSnapshot(task, InvocationId(invocation), PublicationId(publication), if (selected == OPEN0_BINDING) LiveBinding.Open0 else LiveBinding.Openat0, frames)
    }

    private fun decodeFrames(
        output: MemorySegment,
        enrollment: Enrollment,
    ): List<LiveFrame> {
        val count = output.get(JAVA_INT, LiveStackLayouts.snapshotCount)
        nativeCheck(count in 1..LIVE_MAX_FRAMES)
        val tokens = enrollment.methods.map { it.token.value }.toSet()
        val frames = (0 until count).map { index ->
            val offset = LiveStackLayouts.snapshotFrames + index * LiveStackLayouts.frameStride
            val token = output.get(JAVA_LONG, offset + LiveStackLayouts.frameToken)
            nativeCheck(semanticId(token) && token in tokens)
            LiveFrame(MethodToken(token), output.get(JAVA_LONG, offset + LiveStackLayouts.frameLocation))
        }
        val unusedStart = LiveStackLayouts.snapshotFrames + count * LiveStackLayouts.frameStride
        for (offset in unusedStart until LiveStackLayouts.snapshotBytes.toLong()) nativeCheck(output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset) == 0.toByte())
        return frames
    }

    private fun owns(owner: InstallationOwner): Boolean = owner.capability === this && owner.process == process

    private fun knownIds(
        owner: InstallationOwner,
        worker: WorkerOwner? = null,
        task: TaskLease? = null,
    ): Set<Long> = LifecycleCodec.knownIds(owner, worker, task)

    // JNI may throw any application exception; preserve it as the explicit setup cause, never catch Error.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> setup(
        owner: InstallationOwner?,
        action: () -> T,
    ): LiveSetup<T> {
        if (owner != null && !owns(owner)) return LiveSetup.Refused(identityFailure)
        corruption.get()?.let { return LiveSetup.Refused(it.failure) }
        return try {
            LiveSetup.Ready(action())
        } catch (failure: NativeRefusal) {
            LiveSetup.Refused(failure.failure)
        } catch (malformed: MalformedResult) {
            LiveSetup.Refused(poison(owner, malformed).failure)
        } catch (invalid: IllegalArgumentException) {
            LiveSetup.Refused(LiveFailure(LiveFailureStatus.InvalidArgument, LiveFailureReason.Malformed, invalid))
        } catch (failure: Exception) {
            LiveSetup.Refused(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.NativeFailure, failure))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun completion(
        owner: InstallationOwner,
        action: () -> Unit,
    ): LiveCompletion {
        if (!owns(owner)) return LiveCompletion.Refused(identityFailure)
        return try {
            action()
            LiveCompletion.Complete
        } catch (refused: NativeRefusal) {
            val failure = refused.failure
            if (failure.status == LiveFailureStatus.Busy) {
                LiveCompletion.Pending(
                    when (failure.reason) {
                    LiveFailureReason.SlotContended -> LivePendingReason.SlotContended
                    LiveFailureReason.NotPhysicallyRetired -> LivePendingReason.NotPhysicallyRetired
                    LiveFailureReason.CleanupPending -> LivePendingReason.CleanupPending
                    LiveFailureReason.IdentityMismatch, LiveFailureReason.GenerationInvalid, LiveFailureReason.Shutdown,
                    LiveFailureReason.MissingBinding, LiveFailureReason.RuntimeMismatch, LiveFailureReason.AbiMismatch,
                    LiveFailureReason.Capacity, LiveFailureReason.IdExhausted, LiveFailureReason.Malformed,
                    LiveFailureReason.RegistrationMissing, LiveFailureReason.NativeFailure, LiveFailureReason.RetransformUnavailable,
                    LiveFailureReason.InstallationRevoked, LiveFailureReason.AdapterNotImplemented,
                    -> throw MalformedResult()
                },
                )
            } else {
                LiveCompletion.Refused(failure)
            }
        } catch (malformed: MalformedResult) {
            LiveCompletion.Refused(poison(owner, malformed).failure)
        } catch (failure: Exception) {
            LiveCompletion.Refused(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.NativeFailure, failure))
        }
    }

    private fun poison(
        owner: InstallationOwner?,
        cause: Throwable,
    ): Corruption {
        val failed = Corruption(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.Malformed, cause), owner)
        if (corruption.compareAndSet(null, failed) && owner != null) {
            // Retain the real cleanup owner and outcome; corrupt payload never mints authority.
            failed.invalidation.set(completion(owner) { lifecycle(Operation.Invalidate, owner) })
        }
        // The first latch owns invalidation; each caller must retain its own failure cause.
        return failed
    }

    private fun lifecycle(
        operation: Operation,
        owner: InstallationOwner? = null,
        worker: WorkerOwner? = null,
        task: TaskLease? = null,
        capacity: WorkerCapacity? = null,
        generation: CodeGeneration? = null,
        tid: Tid? = null,
    ): Receipt {
        val retained = binding
        return try {
            Arena.ofConfined().use { arena ->
                val request = request(arena, operation, owner, worker, task, capacity, generation, tid)
                val input = request.toArray(JAVA_INT)
                val output = arena.allocate(LiveStackLayouts.receipt)
                val raw = SyscallInvoker.liveBufferStatus(retained.lifecycle, request, LiveStackLayouts.requestBytes, output, LiveStackLayouts.receiptBytes)
                nativeCheck(input.contentEquals(request.toArray(JAVA_INT)))
                val reason = header(output, LiveStackLayouts.receiptBytes, raw)
                nativeCheck(legalPair(operation, raw, reason))
                if (raw != STATUS_OK) {
                    zeroPayload(output)
                    throw NativeRefusal(failure(raw, reason))
                }
                decodeReceipt(output, operation, owner, worker, task, capacity, generation, tid)
            }
        } finally {
            Reference.reachabilityFence(retained)
        }
    }

    private fun decodeReceipt(
        output: MemorySegment,
        operation: Operation,
        owner: InstallationOwner?,
        worker: WorkerOwner?,
        task: TaskLease?,
        capacity: WorkerCapacity?,
        generation: CodeGeneration?,
        tid: Tid?,
    ): Receipt {
        LifecycleCodec.validate(output, operation, process, owner, worker, task, capacity, generation, tid)
        return Receipt(
            output.get(JAVA_LONG, LiveStackLayouts.receiptInstallation),
            output.get(JAVA_LONG, LiveStackLayouts.receiptWorker),
            output.get(JAVA_LONG, LiveStackLayouts.receiptTask),
        )
    }

    private fun request(
        arena: Arena,
        operation: Operation,
        owner: InstallationOwner?,
        worker: WorkerOwner? = null,
        task: TaskLease? = null,
        capacity: WorkerCapacity? = null,
        generation: CodeGeneration? = null,
        tid: Tid? = null,
        invocation: InvocationId? = null,
    ): MemorySegment = LifecycleCodec.request(process, arena, operation, owner, worker, task, capacity, generation, tid, invocation)

    companion object {
        private val noAfterValidated: (Int) -> Unit = {}

        fun bootstrap(
            agentArtifact: Path,
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
        ): NativeStackContexts = bootstrap(agentArtifact, identity, LinuxNative.snapshot())

        internal fun bootstrap(
            agentArtifact: Path,
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
            runtime: RuntimeSnapshot,
        ): NativeStackContexts =
            when (val result = bootstrapChecked(agentArtifact, identity, runtime)) {
            is BootstrapResult.Checked -> result.contextsFor(identity, runtime)
            is BootstrapResult.Refused -> RefusingContexts(result.failure)
        }

        private class CheckedImpl(
            agentArtifact: Path,
            private val identity: LiveStackBootstrap.LiveDeploymentIdentity,
            private val runtime: RuntimeSnapshot,
        ) : BootstrapResult.Checked {
            private val adapter = checkedAdapter(agentArtifact, identity, runtime)

            override fun contextsFor(
                identity: LiveStackBootstrap.LiveDeploymentIdentity,
                runtime: RuntimeSnapshot,
            ): NativeStackContexts {
                require(identity === this.identity && runtime === this.runtime) { "Checked bootstrap association mismatch" }
                return adapter
            }

            @Suppress("TooGenericExceptionCaught")
            override fun revalidate(
                identity: LiveStackBootstrap.LiveDeploymentIdentity,
                runtime: RuntimeSnapshot,
            ): BootstrapResult {
                if (identity !== this.identity || runtime !== this.runtime) {
                    return BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch))
                }
                return try {
                    val current = deploymentProcess(LiveStackBootstrap.checkDeployment(this.identity))
                    if (current == adapter.process) {
                        this
                    } else {
                        BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch))
                    }
                } catch (missing: UnsatisfiedLinkError) {
                    BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.MissingBinding, missing))
                } catch (refused: NativeRefusal) {
                    BootstrapResult.Refused(refused.failure)
                } catch (malformed: MalformedResult) {
                    BootstrapResult.Refused(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.Malformed, malformed))
                } catch (failure: Exception) {
                    BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch, failure))
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun bootstrapChecked(
            agentArtifact: Path,
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
            runtime: RuntimeSnapshot,
        ): BootstrapResult {
            return try {
                CheckedImpl(agentArtifact, identity, runtime)
            } catch (missing: UnsatisfiedLinkError) {
                BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.MissingBinding, missing))
            } catch (refused: NativeRefusal) {
                BootstrapResult.Refused(refused.failure)
            } catch (malformed: MalformedResult) {
                BootstrapResult.Refused(LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.Malformed, malformed))
            } catch (failure: Exception) {
                BootstrapResult.Refused(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch, failure))
            }
        }

        private fun checkedAdapter(
            agentArtifact: Path,
            identity: LiveStackBootstrap.LiveDeploymentIdentity,
            runtime: RuntimeSnapshot,
        ): RealNativeStackContexts {
            if (!runtime.isRealEngine) {
                throw NativeRefusal(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch))
            }
            // This JNI proof must precede even libraryLookup: lookup can dlopen a new object.
            val process = deploymentProcess(LiveStackBootstrap.checkDeployment(identity))
            val checkedAgent = identity.artifacts().single { it.role() == "agent" }
            val checkedPath = Path.of(checkedAgent.canonicalPath())
            if (!agentArtifact.isAbsolute || agentArtifact != checkedPath || agentArtifact.toRealPath() != checkedPath) {
                throw NativeRefusal(LiveFailure(LiveFailureStatus.Unsupported, LiveFailureReason.RuntimeMismatch))
            }
            val arena = Arena.ofAuto()
            val lookup = SymbolLookup.libraryLookup(agentArtifact, arena)
            val linker = Linker.nativeLinker()
            val info = linker.downcallHandle(lookup.find("mazewall_live_info_v1").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
            val descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
            val lifecycle = linker.downcallHandle(lookup.find("mazewall_live_lifecycle_v1").orElseThrow(), descriptor)
            val copy = linker.downcallHandle(lookup.find("mazewall_live_copy_v1").orElseThrow(), descriptor)
            val binding = Binding(arena, lookup, info, lifecycle, copy)
            val mask = identity.routeSubset().fold(0) { bits, route -> bits or if (route == OPEN_ROUTE) OPEN0_BINDING else OPENAT0_BINDING }
            readInfo(binding, process, mask)
            return RealNativeStackContexts(binding, runtime, process, JniBridge, mask)
        }

        private object JniBridge : LiveStackBridge {
            override fun enroll(
                installation: Long,
                owners: Array<Class<*>>,
                methods: Array<Executable>,
            ): LongArray = LiveStackBootstrap.enroll(installation, owners, methods)

            override fun checkSeal(
                installation: Long,
                generation: Long,
            ) = LiveStackBootstrap.checkSeal(installation, generation)

            override fun prepareWorker() = LiveStackBootstrap.prepareWorker()
        }

        private class RefusingContexts(
            private val failure: LiveFailure,
        ) : NativeStackContexts {
            override fun reserve(capacity: WorkerCapacity): LiveSetup<InstallationOwner> = LiveSetup.Refused(failure)

            override fun enroll(
                owner: InstallationOwner,
                owners: List<Class<*>>,
                methods: List<Executable>,
            ): LiveSetup<Enrollment> = LiveSetup.Refused(failure)

            override fun seal(
                owner: InstallationOwner,
                enrollment: Enrollment,
            ): LiveSetup<InstallationOwner> = LiveSetup.Refused(failure)

            override fun registerCurrent(owner: InstallationOwner): LiveSetup<WorkerOwner> = LiveSetup.Refused(failure)

            override fun beginCurrent(worker: WorkerOwner): LiveSetup<TaskLease> = LiveSetup.Refused(failure)

            override fun endCurrent(task: TaskLease): LiveCompletion = LiveCompletion.Refused(failure)

            override fun invalidate(owner: InstallationOwner): LiveCompletion = LiveCompletion.Refused(failure)

            override fun retireWorker(worker: WorkerOwner): LiveCompletion = LiveCompletion.Refused(failure)

            override fun retireInstallation(owner: InstallationOwner): LiveCompletion = LiveCompletion.Refused(failure)

            override fun copy(
                task: TaskLease,
                expectedInvocation: InvocationId?,
            ): LiveRead =
                when (failure.reason) {
                LiveFailureReason.MissingBinding -> LiveRead.Unsupported(LiveUnsupportedReason.MissingBinding)
                LiveFailureReason.RuntimeMismatch -> LiveRead.Unsupported(LiveUnsupportedReason.RuntimeMismatch)
                LiveFailureReason.AbiMismatch -> LiveRead.Unsupported(LiveUnsupportedReason.AbiMismatch)
                LiveFailureReason.RetransformUnavailable -> LiveRead.Unsupported(LiveUnsupportedReason.RetransformUnavailable)
                LiveFailureReason.AdapterNotImplemented -> LiveRead.Unsupported(LiveUnsupportedReason.AdapterNotImplemented)
                LiveFailureReason.IdentityMismatch -> LiveRead.Stale(LiveStaleReason.IdentityMismatch)
                LiveFailureReason.GenerationInvalid -> LiveRead.Stale(LiveStaleReason.GenerationInvalid)
                LiveFailureReason.Shutdown -> LiveRead.Stale(LiveStaleReason.Shutdown)
                LiveFailureReason.InstallationRevoked -> LiveRead.Stale(LiveStaleReason.InstallationRevoked)
                LiveFailureReason.SlotContended -> LiveRead.Busy
                LiveFailureReason.Malformed -> LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult)
                LiveFailureReason.IdExhausted -> LiveRead.Incomplete(LiveIncompleteReason.IdExhausted)
                LiveFailureReason.Capacity, LiveFailureReason.RegistrationMissing, LiveFailureReason.NativeFailure,
                LiveFailureReason.NotPhysicallyRetired, LiveFailureReason.CleanupPending,
                -> LiveRead.Incomplete(LiveIncompleteReason.NativeFailure)
            }
        }

        private fun deploymentProcess(tuple: LongArray): ProcessIdentity {
            nativeCheck(tuple.size == PROCESS_TUPLE_SIZE && tuple[COOKIE_INDEX] != 0L && tuple[START_TICKS_INDEX] != 0L && tuple[PID_INDEX] in 1..Int.MAX_VALUE.toLong())
            return ProcessIdentity(Pid(tuple[PID_INDEX].toInt()), ProcessCookie(tuple[COOKIE_INDEX].toULong()), ProcessStartTicks(tuple[START_TICKS_INDEX].toULong()))
        }

        private fun readInfo(
            binding: Binding,
            process: ProcessIdentity,
            mask: Int,
        ) {
            try {
                Arena.ofConfined().use { arena ->
                    val output = arena.allocate(LiveStackLayouts.info)
                    val raw = SyscallInvoker.liveInfoStatus(binding.info, output, LiveStackLayouts.infoBytes)
                    val reason = header(output, LiveStackLayouts.infoBytes, raw)
                    nativeCheck(raw to reason in infoPairs)
                    if (raw != STATUS_OK) {
                        zeroPayload(output)
                        throw NativeRefusal(failure(raw, reason))
                    }
                    nativeCheck(
                        output.get(JAVA_LONG, LiveStackLayouts.infoCookie) == process.cookie.value.toLong() &&
                            output.get(JAVA_LONG, LiveStackLayouts.infoStartTicks) == process.startTicks.value.toLong() &&
                            output.get(JAVA_INT, LiveStackLayouts.infoPid) == process.pid.value,
                    )
                    nativeCheck(
                        output.get(JAVA_INT, LiveStackLayouts.infoArch) == LIVE_ARCH_X86_64 &&
                            output.get(JAVA_INT, LiveStackLayouts.infoWorkers) == LIVE_MAX_WORKERS &&
                            output.get(JAVA_INT, LiveStackLayouts.infoClasses) == LIVE_MAX_CLASSES &&
                            output.get(JAVA_INT, LiveStackLayouts.infoMethods) == LIVE_MAX_METHODS &&
                            output.get(JAVA_INT, LiveStackLayouts.infoFrames) == LIVE_MAX_FRAMES,
                    )
                    nativeCheck(mask in 1..ALL_BINDINGS && output.get(JAVA_INT, LiveStackLayouts.infoBindings) == mask && output.get(JAVA_INT, LiveStackLayouts.infoReserved) == 0)
                }
            } finally {
                Reference.reachabilityFence(binding)
            }
        }

        private val identityFailure = LiveFailure(LiveFailureStatus.Stale, LiveFailureReason.IdentityMismatch)

        private fun semanticId(value: Long): Boolean = value in 1..LIVE_MAX_ID

        private fun nativeCheck(valid: Boolean) {
            if (!valid) throw MalformedResult()
        }

        private fun header(
            output: MemorySegment,
            bytes: Int,
            raw: Int,
        ): Int {
            nativeCheck(output.byteSize() == bytes.toLong() && output.address() % LiveStackLayouts.alignment == 0L)
            nativeCheck(
                output.get(JAVA_INT, LiveStackLayouts.versionOffset) == LIVE_ABI_VERSION &&
                    output.get(JAVA_INT, LiveStackLayouts.bytesOffset) == bytes &&
                    output.get(JAVA_INT, LiveStackLayouts.statusOffset) == raw,
            )
            return output.get(JAVA_INT, LiveStackLayouts.reasonOffset)
        }

        private fun zeroPayload(output: MemorySegment) {
            for (offset in LiveStackLayouts.payloadOffset until output.byteSize()) nativeCheck(output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset) == 0.toByte())
        }

        private val commonPairs = setOf(
            STATUS_OK to REASON_NONE,
            STATUS_INVALID_ARGUMENT to REASON_MALFORMED,
            STATUS_UNSUPPORTED to REASON_ABI_MISMATCH,
            STATUS_INTERNAL_FAILURE to REASON_NATIVE_FAILURE,
        )
        private val readinessPairs = setOf(
            STATUS_UNSUPPORTED to REASON_MISSING_BINDING,
            STATUS_UNSUPPORTED to REASON_RUNTIME_MISMATCH,
            STATUS_UNSUPPORTED to REASON_RETRANSFORM_UNAVAILABLE,
        )
        private val stalePairs = setOf(
            STATUS_STALE to REASON_IDENTITY_MISMATCH,
            STATUS_STALE to REASON_GENERATION_INVALID,
            STATUS_STALE to REASON_SHUTDOWN,
            STATUS_STALE to REASON_INSTALLATION_REVOKED,
        )
        private val busyPair = STATUS_BUSY to REASON_SLOT_CONTENDED
        private val identityPair = STATUS_STALE to REASON_IDENTITY_MISMATCH
        private val shutdownPair = STATUS_STALE to REASON_SHUTDOWN
        private val exhaustedPair = STATUS_EXHAUSTED to REASON_ID_EXHAUSTED
        private val capacityPair = STATUS_EXHAUSTED to REASON_CAPACITY
        private val infoPairs = commonPairs + readinessPairs + shutdownPair + exhaustedPair
        private val operationPairs = Operation.entries.associateWith { operation ->
            commonPairs +
                when (operation) {
                Operation.Copy ->
                    readinessPairs +
                    stalePairs +
                    setOf(
                    busyPair,
                        exhaustedPair,
                    STATUS_ABSENT to REASON_NO_TASK,
                        STATUS_ABSENT to REASON_NO_ACTIVE_INVOCATION,
                    STATUS_INCOMPLETE to REASON_CAPTURE_FAILED,
                        STATUS_INCOMPLETE to REASON_OVERFLOW,
                    STATUS_INCOMPLETE to REASON_UNKNOWN_METHOD,
                        STATUS_INCOMPLETE to REASON_NESTED_OR_CALLBACK,
                )
                Operation.Reserve -> readinessPairs + setOf(busyPair, shutdownPair, capacityPair, exhaustedPair)
                Operation.Seal -> readinessPairs + stalePairs + setOf(busyPair, exhaustedPair)
                Operation.Register -> readinessPairs + stalePairs + setOf(busyPair, capacityPair, exhaustedPair)
                Operation.Begin -> stalePairs + setOf(busyPair, exhaustedPair)
                Operation.End -> setOf(identityPair, busyPair)
                Operation.Invalidate -> setOf(identityPair)
                Operation.RetireWorker -> setOf(busyPair, identityPair, STATUS_BUSY to REASON_NOT_PHYSICALLY_RETIRED)
                Operation.RetireInstallation -> setOf(busyPair, identityPair, STATUS_BUSY to REASON_CLEANUP_PENDING)
            }
        }

        private fun legalPair(
            operation: Operation,
            status: Int,
            reason: Int,
        ): Boolean = status to reason in operationPairs.getValue(operation)

        private class NativeRefusal(
            val failure: LiveFailure,
        ) : RuntimeException()

        private val failureStatuses = mapOf(
            STATUS_BUSY to LiveFailureStatus.Busy,
            STATUS_STALE to LiveFailureStatus.Stale,
            STATUS_UNSUPPORTED to LiveFailureStatus.Unsupported,
            STATUS_INVALID_ARGUMENT to LiveFailureStatus.InvalidArgument,
            STATUS_EXHAUSTED to LiveFailureStatus.Exhausted,
            STATUS_INTERNAL_FAILURE to LiveFailureStatus.InternalFailure,
        )
        private val failureReasons = mapOf(
            REASON_SLOT_CONTENDED to LiveFailureReason.SlotContended,
            REASON_IDENTITY_MISMATCH to LiveFailureReason.IdentityMismatch,
            REASON_GENERATION_INVALID to LiveFailureReason.GenerationInvalid,
            REASON_SHUTDOWN to LiveFailureReason.Shutdown,
            REASON_MISSING_BINDING to LiveFailureReason.MissingBinding,
            REASON_RUNTIME_MISMATCH to LiveFailureReason.RuntimeMismatch,
            REASON_ABI_MISMATCH to LiveFailureReason.AbiMismatch,
            REASON_CAPACITY to LiveFailureReason.Capacity,
            REASON_ID_EXHAUSTED to LiveFailureReason.IdExhausted,
            REASON_MALFORMED to LiveFailureReason.Malformed,
            REASON_NATIVE_FAILURE to LiveFailureReason.NativeFailure,
            REASON_RETRANSFORM_UNAVAILABLE to LiveFailureReason.RetransformUnavailable,
            REASON_INSTALLATION_REVOKED to LiveFailureReason.InstallationRevoked,
            REASON_NOT_PHYSICALLY_RETIRED to LiveFailureReason.NotPhysicallyRetired,
            REASON_CLEANUP_PENDING to LiveFailureReason.CleanupPending,
        )

        private fun failure(
            status: Int,
            reason: Int,
        ): LiveFailure =
            LiveFailure(
            failureStatuses[status] ?: throw MalformedResult(),
            failureReasons[reason] ?: throw MalformedResult(),
        )

        private val incompleteReasons = mapOf(
            REASON_CAPTURE_FAILED to LiveIncompleteReason.CaptureFailed,
            REASON_OVERFLOW to LiveIncompleteReason.Overflow,
            REASON_UNKNOWN_METHOD to LiveIncompleteReason.UnknownMethod,
            REASON_NESTED_OR_CALLBACK to LiveIncompleteReason.NestedOrCallback,
        )
        private val staleReasons = mapOf(
            REASON_IDENTITY_MISMATCH to LiveStaleReason.IdentityMismatch,
            REASON_GENERATION_INVALID to LiveStaleReason.GenerationInvalid,
            REASON_SHUTDOWN to LiveStaleReason.Shutdown,
            REASON_INSTALLATION_REVOKED to LiveStaleReason.InstallationRevoked,
        )
        private val unsupportedReasons = mapOf(
            REASON_MISSING_BINDING to LiveUnsupportedReason.MissingBinding,
            REASON_RUNTIME_MISMATCH to LiveUnsupportedReason.RuntimeMismatch,
            REASON_ABI_MISMATCH to LiveUnsupportedReason.AbiMismatch,
            REASON_RETRANSFORM_UNAVAILABLE to LiveUnsupportedReason.RetransformUnavailable,
        )

        private fun readFailure(
            status: Int,
            reason: Int,
        ): LiveRead =
            when (status) {
            STATUS_ABSENT -> LiveRead.Absent(if (reason == REASON_NO_TASK) LiveAbsentReason.NoTask else LiveAbsentReason.NoActiveInvocation)
            STATUS_INCOMPLETE -> LiveRead.Incomplete(incompleteReasons[reason] ?: throw MalformedResult())
            STATUS_BUSY -> LiveRead.Busy
            STATUS_STALE -> LiveRead.Stale(staleReasons[reason] ?: throw MalformedResult())
            STATUS_UNSUPPORTED -> LiveRead.Unsupported(unsupportedReasons[reason] ?: throw MalformedResult())
            STATUS_INVALID_ARGUMENT -> LiveRead.Incomplete(LiveIncompleteReason.InvalidArgument)
            STATUS_EXHAUSTED -> LiveRead.Incomplete(LiveIncompleteReason.IdExhausted)
            STATUS_INTERNAL_FAILURE -> LiveRead.Incomplete(LiveIncompleteReason.NativeFailure)
            else -> throw MalformedResult()
        }
    }
}
