package io.mazewall.platform.native

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NativeStackContextsTest {
    @Test
    fun `optional discovery touches neither unrelated getters nor global engine`() {
        val engine = object : NativeEngine {
            override val fileSystem: NativeFileSystem get() = error("unrelated getter")
            override val networking: NativeNetworking get() = error("unrelated getter")
            override val process: NativeProcess get() = error("unrelated getter")
            override val memory: NativeMemory get() = error("unrelated getter")
            override val raw: RawSyscallOperations get() = error("unrelated getter")
        }
        assertSame(UnsupportedNativeStackContexts, engine.stackContextsOrUnsupported())
        assertSame(UnsupportedNativeStackContexts, MockNativeEngine().stackContextsOrUnsupported())
        val supplied = object : NativeStackContexts by UnsupportedNativeStackContexts {}
        val provider = object : NativeEngine by engine, NativeStackContextProvider {
            override val stackContexts = supplied
        }
        assertSame(supplied, provider.stackContextsOrUnsupported())
    }

    @Test
    fun `semantic identifiers reject zero negatives and allocator overflow`() {
        val constructors: List<(Long) -> Any> = listOf(
            ::InstallationId,
            ::WorkerId,
            ::TaskId,
            ::CodeGeneration,
            ::MethodToken,
            ::ClassToken,
            ::InvocationId,
            ::PublicationId,
        )
        for (construct in constructors) {
            construct(1)
            construct((1L shl 60) - 1)
            for (invalid in listOf(0L, -1L, Long.MIN_VALUE, 1L shl 60, Long.MAX_VALUE)) {
                assertThrows<IllegalArgumentException> { construct(invalid) }
            }
        }
    }

    @Test
    fun `process incarnation preserves all nonzero uint64 bits`() {
        for (bits in listOf(1uL, (1uL shl 60), Long.MAX_VALUE.toULong(), 1uL shl 63, ULong.MAX_VALUE)) {
            assertEquals(bits, ProcessCookie(bits).value)
            assertEquals(bits, ProcessStartTicks(bits).value)
        }
        assertThrows<IllegalArgumentException> { ProcessCookie(0uL) }
        assertThrows<IllegalArgumentException> { ProcessStartTicks(0uL) }
    }

    @Test
    fun `counts enforce their own bounds`() {
        val constructors: List<Pair<Int, (Int) -> Any>> = listOf(
            256 to ::WorkerCapacity,
            512 to ::ClassCount,
            4096 to ::MethodCount,
            128 to ::FrameCount,
        )
        for ((max, construct) in constructors) {
            construct(1)
            construct(max)
            for (invalid in listOf(Int.MIN_VALUE, -1, 0, max + 1, Int.MAX_VALUE)) {
                assertThrows<IllegalArgumentException> { construct(invalid) }
            }
        }
    }

    @Test
    fun `live PID and worker TID validation does not change general wrappers`() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0)) {
            assertThrows<IllegalArgumentException> { ProcessIdentity(Pid(invalid), ProcessCookie(1uL), ProcessStartTicks(2uL)) }
            assertThrows<IllegalArgumentException> { WorkerOwner(sealedOwner(), WorkerId(4), Tid(invalid)) }
        }
        ProcessIdentity(Pid(Int.MAX_VALUE), ProcessCookie(1uL), ProcessStartTicks(2uL))
        WorkerOwner(sealedOwner(), WorkerId(4), Tid(Int.MAX_VALUE))
    }

    @Test
    fun `enrollment owns collection copies and actual identities not projection text`() {
        val staging = stagingOwner()
        val executable = String::class.java.getDeclaredMethod("length")
        val classes = mutableListOf(EnrolledClass(ClassToken(6), String::class.java))
        val methods = mutableListOf(EnrolledMethod(MethodToken(7), executable, "display only", "()I", executable.modifiers))
        val enrollment = Enrollment(staging, CodeGeneration(3), classes, methods)
        classes.clear()
        methods.clear()
        assertSame(String::class.java, enrollment.classes.single().identity)
        assertSame(executable, enrollment.methods.single().identity)
        assertEquals("display only", enrollment.methods.single().name)
        assertSame(staging, enrollment.owner)
        assertSame(staging.capability, enrollment.owner.capability)
        assertThrows<UnsupportedOperationException> { (enrollment.classes as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (enrollment.methods as MutableList).clear() }
        assertEquals(MethodToken(7), enrollment.methods.single().token)
        val sealed = staging.sealed(enrollment)
        assertNotSame(staging, sealed)
        assertSame(enrollment, (sealed.state as InstallationState.Sealed).enrollment)
        assertSame(InstallationState.Staging, staging.state)
        assertSame(staging.process, sealed.process)
        assertSame(staging.capability, sealed.capability)
        assertEquals(staging.installation, sealed.installation)
        assertEquals(staging.capacity, sealed.capacity)
        assertThrows<IllegalArgumentException> { stagingOwner().sealed(enrollment) }
    }

    @Test
    fun `enrollment refuses duplicate and mismatched actual method authority`() {
        val staging = stagingOwner()
        val string = EnrolledClass(ClassToken(6), String::class.java)
        val length = String::class.java.getDeclaredMethod("length")
        val method = EnrolledMethod(MethodToken(7), length, "length", "()I", length.modifiers)
        assertThrows<IllegalArgumentException> { Enrollment(staging, CodeGeneration(3), listOf(string, string), listOf(method)) }
        assertThrows<IllegalArgumentException> { Enrollment(staging, CodeGeneration(3), listOf(string), listOf(method, method)) }
        assertThrows<IllegalArgumentException> {
            Enrollment(staging, CodeGeneration(3), listOf(EnrolledClass(ClassToken(6), Any::class.java)), listOf(method))
        }
        assertThrows<IllegalArgumentException> { Enrollment(staging, CodeGeneration(3), emptyList(), listOf(method)) }
        assertThrows<IllegalArgumentException> { Enrollment(staging, CodeGeneration(3), listOf(string), emptyList()) }
        assertThrows<IllegalArgumentException> { WorkerOwner(staging, WorkerId(4), Tid(42)) }
    }

    @Test
    fun `snapshot copies frames and retains the complete owner join`() {
        val owner = sealedOwner()
        val worker = WorkerOwner(owner, WorkerId(4), Tid(42))
        val task = TaskLease(worker, TaskId(5))
        val frames = mutableListOf(LiveFrame(MethodToken(7), -1))
        val snapshot = LiveSnapshot(task, InvocationId(8), PublicationId(9), LiveBinding.Open0, frames)
        frames[0] = LiveFrame(MethodToken(7), Long.MAX_VALUE)
        frames.clear()
        assertEquals(-1L, snapshot.frames.single().location)
        assertThrows<UnsupportedOperationException> { (snapshot.frames as MutableList).clear() }
        assertSame(task, snapshot.task)
        assertSame(worker, snapshot.task.worker)
        assertSame(owner, snapshot.task.worker.owner)
        assertSame(owner.capability, snapshot.task.worker.owner.capability)
        assertEquals(Pid(41), snapshot.task.worker.owner.process.pid)
        assertEquals(ProcessCookie(ULong.MAX_VALUE), owner.process.cookie)
        assertEquals(ProcessStartTicks(1uL shl 63), owner.process.startTicks)
        assertEquals(InstallationId(2), owner.installation)
        assertEquals(CodeGeneration(3), worker.enrollment.generation)
        assertEquals(Tid(42), worker.tid)
        assertEquals(TaskId(5), snapshot.task.task)
        assertEquals(InvocationId(8), snapshot.invocation)
        assertEquals(PublicationId(9), snapshot.publication)
        assertEquals(LiveBinding.Open0, snapshot.binding)
        assertNull(snapshot.parentInvocation)
        assertThrows<IllegalArgumentException> { LiveSnapshot(task, InvocationId(8), PublicationId(9), LiveBinding.Open0, emptyList()) }
        assertThrows<IllegalArgumentException> {
            LiveSnapshot(task, InvocationId(8), PublicationId(9), LiveBinding.Open0, List(129) { LiveFrame(MethodToken(7), 0) })
        }
        assertThrows<IllegalArgumentException> {
            LiveSnapshot(task, InvocationId(8), PublicationId(9), LiveBinding.Open0, listOf(LiveFrame(MethodToken(99), 0)))
        }
        assertEquals(128, LiveSnapshot(task, InvocationId(8), PublicationId(9), LiveBinding.Openat0, List(128) { LiveFrame(MethodToken(7), Long.MIN_VALUE) }).frames.size)
    }

    @Test
    fun `old providers refuse attached receipt operations without invoking legacy lifecycle`() {
        val capability = UnsupportedNativeStackContexts
        val owner = sealedOwner()
        val worker = WorkerOwner(owner, WorkerId(4), Tid(42))
        val workerReceipt = capability.prepareWorkerReceipt(owner)
        val taskReceipt = capability.prepareTaskReceipt(worker)
        listOf(
            capability.registerCurrent(owner, workerReceipt),
            capability.recoverWorker(workerReceipt),
            capability.beginCurrent(worker, taskReceipt),
            capability.recoverTask(taskReceipt),
        ).forEach { assertEquals(LiveFailureStatus.Unsupported, (it as LiveSetup.Refused).failure.status) }
    }

    @Test
    fun `unsupported capability refuses every operation including cleanup`() {
        val capability = UnsupportedNativeStackContexts
        val owner = sealedOwner()
        val worker = WorkerOwner(owner, WorkerId(4), Tid(42))
        val task = TaskLease(worker, TaskId(5))
        val enrollment = worker.enrollment
        val setups = listOf(
            capability.reserve(WorkerCapacity(1)),
            capability.enroll(owner, emptyList(), emptyList()),
            capability.seal(owner, enrollment),
            capability.registerCurrent(owner),
            capability.beginCurrent(worker),
        )
        setups.forEach { assertEquals(LiveFailureStatus.Unsupported, (it as LiveSetup.Refused).failure.status) }
        listOf(capability.endCurrent(task), capability.invalidate(owner), capability.retireWorker(worker), capability.retireInstallation(owner))
            .forEach { assertEquals(LiveFailureStatus.Unsupported, (it as LiveCompletion.Refused).failure.status) }
        assertEquals(LiveRead.Unsupported(LiveUnsupportedReason.AdapterNotImplemented), capability.copy(task))
        assertEquals(capability.copy(task), capability.copy(task, InvocationId(8)))
    }

    @Test
    fun `failure preserves cause and pending is not completed cleanup`() {
        val cause = IllegalStateException("native setup failed")
        val failure = LiveFailure(LiveFailureStatus.InternalFailure, LiveFailureReason.NativeFailure, cause)
        assertSame(cause, LiveSetup.Refused(failure).failure.cause)
        assertSame(cause, LiveCompletion.Refused(failure).failure.cause)
        val pending: LiveCompletion = LiveCompletion.Pending(LivePendingReason.CleanupPending)
        assertFalse(pending is LiveCompletion.Complete)
        val malformed: LiveRead = LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult)
        assertFalse(malformed is LiveRead.Available)
    }

    private fun stagingOwner(): InstallationOwner =
        InstallationOwner(
        object : NativeStackContexts by UnsupportedNativeStackContexts {},
        ProcessIdentity(Pid(41), ProcessCookie(ULong.MAX_VALUE), ProcessStartTicks(1uL shl 63)),
        InstallationId(2),
            WorkerCapacity(1),
    )

    private fun sealedOwner(): InstallationOwner {
        val staging = stagingOwner()
        val method = String::class.java.getDeclaredMethod("length")
        return staging.sealed(
            Enrollment(
                staging,
                CodeGeneration(3),
                listOf(EnrolledClass(ClassToken(6), String::class.java)),
                listOf(EnrolledMethod(MethodToken(7), method, "length", "()I", method.modifiers)),
            ),
        )
    }
}
