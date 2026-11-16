package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeStackContexts
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

class RealNativeStackContextsTest {
    private var calls = 0
    private var status = 0
    private var expectedRequest: MemorySegment? = null
    private var expectedOutput: MemorySegment? = null
    private val process = ProcessIdentity(Pid(41), ProcessCookie(ULong.MAX_VALUE), ProcessStartTicks(1uL shl 63))

    @Test
    fun `terminated worker task receipt cannot be recovered by another thread`() {
        val transport = Transport()
        val fatal = LinkageError("after validated begin")
        val contexts = adapter(transport, afterValidated = { if (it == 4) throw fatal })
        val receipt = AtomicReference<TaskBeginReceipt>()
        val workerFailure = AtomicReference<Throwable>()
        val worker = Thread.ofPlatform().unstarted {
            try {
                val owner = task(contexts).worker
                val retained = contexts.prepareTaskReceipt(owner)
                receipt.set(retained)
                assertSame(fatal, assertThrows<LinkageError> { contexts.beginCurrent(owner, retained) })
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertThrows<IllegalArgumentException> { contexts.recoverTask(receipt.get()) }
        assertEquals(listOf(4), transport.operations)
    }

    @Test
    fun `terminated worker receipt recovery publishes one owner and one ready across concurrent callers`() {
        repeat(100) {
            val transport = Transport()
            val fatal = LinkageError("after validated register")
            val contexts = adapter(transport, afterValidated = { if (it == 3) throw fatal })
            val retained = AtomicReference<WorkerRegistrationReceipt>()
            val workerFailure = AtomicReference<Throwable>()
            val worker = Thread.ofPlatform().unstarted {
                try {
                    val owner = task(contexts).worker.owner
                    val receipt = contexts.prepareWorkerReceipt(owner)
                    retained.set(receipt)
                    assertSame(fatal, assertThrows<LinkageError> { contexts.registerCurrent(owner, receipt) })
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                }
            }
            worker.start()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(Thread.State.TERMINATED, worker.state)
            assertNull(workerFailure.get())
            val start = java.util.concurrent.CountDownLatch(1)
            val ready = java.util.concurrent.CountDownLatch(2)
            val values = Array(2) { AtomicReference<LiveSetup<WorkerOwner>>() }
            val failures = Array(2) { AtomicReference<Throwable>() }
            val callers = (0..1).map { index ->
                Thread
                    .ofPlatform()
                    .unstarted {
                    ready.countDown()
                    try {
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        values[index].set(contexts.recoverWorker(retained.get()))
                    } catch (failure: Throwable) {
                        failures[index].set(failure)
                    }
                }.also { it.start() }
            }
            try {
                assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                start.countDown()
            }
            callers.forEach {
                it.join(5000)
                assertFalse(it.isAlive)
            }
            failures.forEach { assertNull(it.get()) }
            assertSame(values[0].get(), values[1].get())
            assertSame((values[0].get() as LiveSetup.Ready).value, (values[1].get() as LiveSetup.Ready).value)
            assertEquals(listOf(3), transport.operations)
        }
    }

    @Test
    fun `concurrent malformed attached receipts preserve each cause and invalidate once`() {
        val transport = Transport().apply {
            receiptMutation = 40L
            registerBarrier = java.util.concurrent.CyclicBarrier(2)
        }
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val results = Array(2) { AtomicReference<LiveSetup<WorkerOwner>>() }
        val failures = Array(2) { AtomicReference<Throwable>() }
        val threads = (0..1).map { index ->
            Thread
                .ofPlatform()
                .unstarted {
                try {
                    val receipt = contexts.prepareWorkerReceipt(owner)
                    results[index].set(contexts.registerCurrent(owner, receipt))
                } catch (failure: Throwable) {
                    failures[index].set(failure)
                }
            }.also { it.start() }
        }
        threads.forEach {
            it.join(5000)
            assertFalse(it.isAlive)
        }
        failures.forEach { assertNull(it.get()) }
        val first = (results[0].get() as LiveSetup.Refused).failure
        val second = (results[1].get() as LiveSetup.Refused).failure
        assertEquals(LiveFailureReason.Malformed, first.reason)
        assertEquals(LiveFailureReason.Malformed, second.reason)
        assertNotNull(first.cause)
        assertNotNull(second.cause)
        assertNotSame(first.cause, second.cause)
        assertEquals(1, transport.operations.count { it == 6 })
    }

    @Test
    fun `attached receipt validates every native receipt field for both operations`() {
        for (op in listOf(3, 4)) {
            for (offset in listOf(0L, 4L, 8L, 12L, 16L, 24L, 32L, 40L, 48L, 56L, 64L, 72L, 76L, 80L, 84L)) {
                val transport = Transport().apply { receiptMutation = offset }
                val contexts = adapter(transport)
                val worker = task(contexts).worker
                if (op == 3) {
                    val receipt = contexts.prepareWorkerReceipt(worker.owner)
                    val refused = contexts.registerCurrent(worker.owner, receipt) as LiveSetup.Refused
                    assertEquals(LiveFailureReason.Malformed, refused.failure.reason, "op=$op offset=$offset")
                    assertThrows<IllegalArgumentException> { contexts.recoverWorker(receipt) }
                } else {
                    val receipt = contexts.prepareTaskReceipt(worker)
                    val refused = contexts.beginCurrent(worker, receipt) as LiveSetup.Refused
                    assertEquals(LiveFailureReason.Malformed, refused.failure.reason, "op=$op offset=$offset")
                    assertThrows<IllegalArgumentException> { contexts.recoverTask(receipt) }
                }
                assertEquals(1, transport.operations.count { it == op })
            }
        }
    }

    @Test
    fun `mutated lifecycle request leaves attached receipt unverified and never replays`() {
        val transport = Transport().apply { mutateLifecycleRequest = true }
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        val refused = contexts.registerCurrent(owner, receipt) as LiveSetup.Refused
        assertEquals(LiveFailureReason.Malformed, refused.failure.reason)
        assertThrows<IllegalArgumentException> { contexts.recoverWorker(receipt) }
        assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, receipt) }
        assertEquals(1, transport.operations.count { it == 3 })
    }

    @Test
    fun `worker preparation fatal remains unknown and never starts or retries lifecycle`() {
        val fatal = LinkageError("JVMTI preparation")
        val transport = Transport().apply { prepareWorkerFailure = fatal }
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        assertSame(fatal, assertThrows<LinkageError> { contexts.registerCurrent(owner, receipt) })
        assertThrows<IllegalArgumentException> { contexts.recoverWorker(receipt) }
        assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, receipt) }
        assertTrue(transport.operations.isEmpty())
        assertEquals(listOf("prepare"), transport.bridgeCalls)
    }

    @Test
    fun `downcall fatal leaves attached owner unknown and never replays`() {
        val transport = Transport()
        val fatal = LinkageError("lost lifecycle receipt")
        transport.lifecycleFailure = fatal
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        assertSame(fatal, assertThrows<LinkageError> { contexts.registerCurrent(owner, receipt) })
        assertThrows<IllegalArgumentException> { contexts.recoverWorker(receipt) }
        assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, receipt) }
        assertEquals(listOf(3), transport.operations)
    }

    @Test
    fun `forged attached carrier key cannot access capability or issue an operation`() {
        val transport = Transport()
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val type = RealNativeStackContexts::class.java.declaredClasses.single { it.simpleName == "AttachedReceipt" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }

        fun field(name: String): Any =
            RealNativeStackContexts::class.java
                .getDeclaredField(name)
            .apply { isAccessible = true }
                .get(contexts)
        val forged = constructor.newInstance(
            contexts,
            Any(),
            field("binding"),
            field("runtime"),
            field("bridge"),
            field("corruption"),
            owner,
            null,
            { _: Int -> },
        ) as WorkerRegistrationReceipt
        assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, forged) }
        assertThrows<IllegalArgumentException> { contexts.recoverWorker(forged) }
        assertTrue(transport.operations.isEmpty())
        assertTrue(transport.bridgeCalls.isEmpty())
    }

    @Test
    fun `attached task rejects an actual different native thread before Begin`() {
        val transport = Transport()
        val engine = MockNativeEngine().apply { process.tid = Tid(43) }
        val contexts = adapter(transport, engine)
        val worker = task(contexts).worker
        assertThrows<IllegalArgumentException> { contexts.prepareTaskReceipt(worker) }
        assertTrue(transport.operations.isEmpty())
    }

    @Test
    fun `attached malformed and refused responses never recover a token or replay`() {
        for (malformed in listOf(false, true)) {
            val transport = Transport()
            val contexts = adapter(transport)
            val owner = task(contexts).worker.owner
            val receipt = contexts.prepareWorkerReceipt(owner)
            if (malformed) {
                transport.receiptMutation = 40L
            } else {
                transport.lifecycleStatus = 3
                transport.lifecycleReason = 7
            }
            assertTrue(contexts.registerCurrent(owner, receipt) is LiveSetup.Refused)
            assertThrows<IllegalArgumentException> { contexts.recoverWorker(receipt) }
            assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, receipt) }
            assertEquals(1, transport.operations.count { it == 3 })
        }
    }

    @Test
    fun `attached receipt rejects foreign capability and original caller cannot replay`() {
        val transport = Transport()
        val contexts = adapter(transport)
        val other = adapter(Transport())
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        assertThrows<IllegalArgumentException> { other.registerCurrent(owner, receipt) }
        assertTrue(transport.operations.isEmpty())
        assertTrue(contexts.registerCurrent(owner, receipt) is LiveSetup.Ready)
        assertThrows<IllegalArgumentException> { contexts.registerCurrent(owner, receipt) }
        assertEquals(listOf(3), transport.operations)
    }

    @Test
    fun `validated worker receipt survives exact fatal before wrapper allocation`() {
        val transport = Transport()
        val fatal = LinkageError("after validated register")
        val contexts = adapter(transport, afterValidated = { if (it == 3) throw fatal })
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        assertSame(fatal, assertThrows<LinkageError> { contexts.registerCurrent(owner, receipt) })
        val recovered = (contexts.recoverWorker(receipt) as LiveSetup.Ready).value
        assertSame(owner, recovered.owner)
        assertSame(recovered, (contexts.recoverWorker(receipt) as LiveSetup.Ready).value)
        assertEquals(listOf(3), transport.operations)
    }

    @Test
    fun `validated task receipt survives exact fatal before wrapper allocation`() {
        val transport = Transport()
        val fatal = LinkageError("after validated begin")
        val contexts = adapter(transport, afterValidated = { if (it == 4) throw fatal })
        val worker = task(contexts).worker
        val receipt = contexts.prepareTaskReceipt(worker)
        assertSame(fatal, assertThrows<LinkageError> { contexts.beginCurrent(worker, receipt) })
        val recovered = (contexts.recoverTask(receipt) as LiveSetup.Ready).value
        assertSame(worker, recovered.worker)
        assertSame(recovered, (contexts.recoverTask(receipt) as LiveSetup.Ready).value)
        assertEquals(LiveCompletion.Complete, contexts.endCurrent(recovered))
        assertEquals(listOf(4, 5), transport.operations)
    }

    @Test
    fun `attached worker and task receipts recover the exact successful owners without replay`() {
        val transport = Transport()
        val contexts = adapter(transport)
        val owner = task(contexts).worker.owner
        val receipt = contexts.prepareWorkerReceipt(owner)
        val worker = (contexts.registerCurrent(owner, receipt) as LiveSetup.Ready).value
        assertSame(worker, (contexts.recoverWorker(receipt) as LiveSetup.Ready).value)
        val taskReceipt = contexts.prepareTaskReceipt(worker)
        val lease = (contexts.beginCurrent(worker, taskReceipt) as LiveSetup.Ready).value
        assertSame(lease, (contexts.recoverTask(taskReceipt) as LiveSetup.Ready).value)
        assertEquals(listOf(3, 4), transport.operations)
    }

    @Test
    fun `each operation encodes only canonical required fields and zeroes every unused byte`() {
        for (op in 0..8) {
            val fixture = Transport()
            val contexts = adapter(fixture)
            operation(op, contexts, task(contexts))
            val request = if (op == 0) fixture.copyRequest!! else fixture.requests.first()
            val expected = ByteArray(88)
            val bytes = MemorySegment.ofArray(expected)
            for ((offset, value) in listOf(0L to 1, 4L to 88, 8L to op, 72L to 41)) bytes.set(JAVA_INT.withByteAlignment(1), offset, value)
            bytes.set(JAVA_LONG.withByteAlignment(1), 16, -1)
            bytes.set(JAVA_LONG.withByteAlignment(1), 24, Long.MIN_VALUE)
            if (op != 1) bytes.set(JAVA_LONG.withByteAlignment(1), 32, 2)
            if (op in listOf(0, 4, 5, 7)) bytes.set(JAVA_LONG.withByteAlignment(1), 40, 4)
            if (op in listOf(0, 5)) bytes.set(JAVA_LONG.withByteAlignment(1), 48, 5)
            if (op in listOf(0, 2, 3, 4, 5, 7)) bytes.set(JAVA_LONG.withByteAlignment(1), 56, 3)
            if (op in listOf(0, 3, 4, 5, 7)) bytes.set(JAVA_INT.withByteAlignment(1), 76, 42)
            if (op == 1) bytes.set(JAVA_INT.withByteAlignment(1), 80, 1)
            assertArrayEquals(expected, request, "op=$op")
        }
    }

    @Test
    fun `binding is accessible on an actual platform worker and not closed at retirement`() {
        val fixture = Transport()
        val contexts = adapter(fixture)
        val lease = task(contexts)
        val binding = contexts.javaClass
            .getDeclaredField("binding")
            .apply { isAccessible = true }
            .get(contexts)
        val result = AtomicReference<LiveRead>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread.ofPlatform().unstarted {
            try {
                result.set(contexts.copy(lease))
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        thread.start()
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        assertTrue(result.get() is LiveRead.Available)
        assertEquals(LiveCompletion.Complete, contexts.retireInstallation(lease.worker.owner))
        assertSame(
            binding,
            contexts.javaClass
            .getDeclaredField("binding")
            .apply { isAccessible = true }
            .get(contexts),
        )
        val arena = binding.javaClass
            .getDeclaredField("arena")
            .apply { isAccessible = true }
            .get(binding) as Arena
        assertTrue(arena.scope().isAlive)
    }

    @Test
    fun `minted IDs cannot alias retained installation generation worker task or enrollment tokens`() {
        for (id in listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L)) {
            val fixture = Transport()
            MemorySegment.ofArray(fixture.record).set(JAVA_LONG.withByteAlignment(1), 80, id)
            val contexts = adapter(fixture)
            assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), contexts.copy(task(contexts)), "publication=$id")
        }
        for ((op, offset, ids) in listOf(Triple(3, 40L, listOf(2L, 3L, 6L, 7L)), Triple(4, 48L, listOf(2L, 3L, 4L, 6L, 7L)))) {
            for (id in ids) {
                val fixture = Transport()
                fixture.receiptOverride = offset to id
                val contexts = adapter(fixture)
                assertEquals(LiveFailureReason.Malformed, (operation(op, contexts, task(contexts)) as LiveSetup.Refused).failure.reason)
            }
        }
    }

    @Test
    fun `JNI deployment tuple preserves uint64 bits and rejects malformed process identities`() {
        val method = RealNativeStackContexts.Companion.javaClass.declaredMethods
            .single { it.name == "deploymentProcess" }
            .apply { isAccessible = true }
        assertEquals(process, method.invoke(RealNativeStackContexts.Companion, longArrayOf(-1, Long.MIN_VALUE, 41)))
        for (tuple in listOf(
            longArrayOf(),
                longArrayOf(1, 2),
                longArrayOf(1, 2, 41, 4),
                longArrayOf(0, 2, 41),
                longArrayOf(1, 0, 41),
                longArrayOf(1, 2, 0),
                longArrayOf(1, 2, -1),
            longArrayOf(
                1,
                2,
            Int.MAX_VALUE.toLong() + 1,
            ),
        )) {
            assertThrows<InvocationTargetException> { method.invoke(RealNativeStackContexts.Companion, tuple) }
        }
    }

    @Test
    fun `info validates exact process readiness limits unused fields and per operation pairs`() {
        val readInfo = RealNativeStackContexts.Companion.javaClass.declaredMethods
            .single { it.name == "readInfo" }
            .apply { isAccessible = true }

        fun check(fixture: Transport) {
            val contexts = adapter(fixture)
            val binding = contexts.javaClass
                .getDeclaredField("binding")
                .apply { isAccessible = true }
                .get(contexts)
            readInfo.invoke(RealNativeStackContexts.Companion, binding, process, 3)
        }
        check(Transport())
        for (offset in listOf(0L, 4L, 16L, 24L, 32L, 36L, 40L, 44L, 48L, 52L, 56L, 60L)) {
            val fixture = Transport()
            val bytes = MemorySegment.ofArray(fixture.infoRecord)
            if (offset == 16L || offset == 24L) {
                bytes.set(JAVA_LONG.withByteAlignment(1), offset, 0)
            } else {
                bytes.set(JAVA_INT.withByteAlignment(1), offset, if (offset == 60L) 1 else 0)
            }
            assertThrows<InvocationTargetException>("info offset=$offset") { check(fixture) }
        }
        val legal = setOf(0 to 0, 5 to 11, 5 to 12, 5 to 13, 5 to 19, 4 to 10, 7 to 15, 6 to 16, 8 to 18)
        for (status in (0..8).toList() + listOf(Int.MIN_VALUE)) {
            for (reason in 0..23) {
            val fixture = Transport()
            fixture.infoStatus = status
            fixture.infoReason = reason
            if (status == 0 && reason == 0) {
                check(fixture)
            } else {
                val thrown = assertThrows<InvocationTargetException> { check(fixture) }.cause!!
                assertEquals((status to reason) in legal, thrown.javaClass.simpleName == "NativeRefusal", "$status/$reason")
            }
        }
        }
    }

    @Test
    fun `bounded buffers reject short long and misaligned views without reading guards`() {
        val header = RealNativeStackContexts.Companion.javaClass.declaredMethods
            .single { it.name == "header" }
            .apply { isAccessible = true }
        Arena.ofConfined().use { arena ->
            val guarded = arena.allocate(2168, 8).fill(0x5a)
            val view = guarded.asSlice(8, 2152)
            view.fill(0)
            view.set(JAVA_INT, 0, 1)
            view.set(JAVA_INT, 4, 2152)
            assertEquals(0, header.invoke(RealNativeStackContexts.Companion, view, 2152, 0))
            for (invalid in listOf(guarded.asSlice(8, 2151), guarded.asSlice(8, 2153), guarded.asSlice(9, 2152))) {
                assertThrows<InvocationTargetException> { header.invoke(RealNativeStackContexts.Companion, invalid, 2152, 0) }
            }
            for (offset in (0L until 8) + (2160L until 2168)) assertEquals(0x5a.toByte(), guarded.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset))
        }
    }

    @Test
    fun `enrollment tuple corruption retains original cleanup owner and never yields partial enrollment`() {
        val invalid =
            listOf(
                longArrayOf(),
                longArrayOf(3, 1, 1),
                longArrayOf(3, 2, 1, 6, 7),
                longArrayOf(0, 1, 1, 6, 7),
                longArrayOf(3, 1, 1, 0, 7),
                longArrayOf(3, 1, 1, 6, 6),
                longArrayOf(
                    3,
                    1,
                    1,
                    6,
            1L shl 60,
                ),
            )
        for (tuple in invalid) {
            val fixture = Transport()
            fixture.enrollmentTuple = tuple
            val contexts = adapter(fixture)
            val owner = task(contexts).worker.enrollment.owner
            val result = contexts.enroll(owner, listOf(String::class.java), listOf(String::class.java.getDeclaredMethod("length"))) as LiveSetup.Refused
            assertEquals(LiveFailureReason.Malformed, result.failure.reason)
            assertEquals(listOf(6), fixture.operations)
            assertEquals(LiveCompletion.Complete, contexts.retireInstallation(owner))
            assertEquals(2L, fixture.requests.last().getLong(32))
        }
    }

    @Test
    fun `JNI exceptions keep exact cause and fatal errors escape without lifecycle admission`() {
        for (failure in listOf(IllegalStateException("pending JNI"), IllegalArgumentException("bad JNI"), OutOfMemoryError("fatal JNI"))) {
            val fixture = Transport()
            fixture.bridgeFailure = failure
            val contexts = adapter(fixture)
            val owner = task(contexts).worker.enrollment.owner
            if (failure is Error) {
                assertSame(failure, assertThrows<OutOfMemoryError> { contexts.enroll(owner, listOf(String::class.java), listOf(String::class.java.getDeclaredMethod("length"))) })
            } else {
                val refused = contexts.enroll(owner, listOf(String::class.java), listOf(String::class.java.getDeclaredMethod("length"))) as LiveSetup.Refused
                assertSame(failure, refused.failure.cause)
                assertEquals(LiveCompletion.Complete, contexts.retireInstallation(owner))
            }
            assertFalse(2 in fixture.operations)
        }
    }

    @Test
    fun `all receipt fields are checked and malformed reserve invents no cleanup owner`() {
        for (op in 1..8) {
            for (offset in listOf(0L, 4L, 8L, 12L, 16L, 24L, 32L, 40L, 48L, 56L, 64L, 72L, 76L, 80L, 84L)) {
            val fixture = Transport()
            fixture.receiptMutation = offset
            val contexts = adapter(fixture)
            val result = operation(op, contexts, task(contexts))
            val failure = when (result) {
                is LiveSetup.Refused -> result.failure
                is LiveCompletion.Refused -> result.failure
                else -> error("accepted receipt op=$op offset=$offset")
            }
            assertEquals(LiveFailureReason.Malformed, failure.reason)
            if (op == 1) assertEquals(listOf(1), fixture.operations)
        }
        }
    }

    @Test
    fun `malformed cleanup keeps distinct causes and first owner through pending and complete retry`() {
        val fixture = Transport()
        fixture.record[100] = 0
        fixture.receiptMutation = 4L
        val contexts = adapter(fixture)
        val lease = task(contexts)
        val owner = lease.worker.owner
        assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), contexts.copy(lease))
        val latch = contexts.javaClass
            .getDeclaredField("corruption")
            .apply { isAccessible = true }
            .get(contexts) as AtomicReference<*>
        val retained = checkNotNull(latch.get())
        val primary = retained.javaClass
            .getDeclaredField("failure")
            .apply { isAccessible = true }
            .get(retained) as LiveFailure
        val firstCause = checkNotNull(primary.cause)
        val invalidation = retained.javaClass
            .getDeclaredField("invalidation")
            .apply { isAccessible = true }
            .get(retained) as AtomicReference<*>
        val automatic = invalidation.get() as LiveCompletion.Refused
        val invalidationCause = checkNotNull(automatic.failure.cause)
        assertNotSame(firstCause, invalidationCause, "Malformed invalidation B must not replay snapshot failure A")
        assertTrue(firstCause.stackTrace.any { it.methodName == "decodeFrames" })
        assertTrue(invalidationCause.stackTrace.any { it.methodName == "header" })

        fixture.receiptMutation = 12L
        val retirement = contexts.retireInstallation(owner) as LiveCompletion.Refused
        val retirementCause = checkNotNull(retirement.failure.cause)
        assertNotSame(firstCause, retirementCause)
        assertNotSame(invalidationCause, retirementCause)
        assertEquals(LiveFailureReason.Malformed, retirement.failure.reason)
        assertFalse(retirementCause.stackTrace.any { it.methodName == "header" })

        fixture.receiptMutation = null
        fixture.lifecycleStatus = 3
        fixture.lifecycleReason = 22
        assertEquals(LiveCompletion.Pending(LivePendingReason.CleanupPending), contexts.retireInstallation(owner))
        fixture.lifecycleStatus = 0
        fixture.lifecycleReason = 0
        assertEquals(LiveCompletion.Complete, contexts.retireInstallation(owner))
        assertSame(retained, latch.get())
        assertSame(
            owner,
            retained.javaClass
            .getDeclaredField("owner")
            .apply { isAccessible = true }
            .get(retained),
        )
        assertSame(
            primary,
            retained.javaClass
            .getDeclaredField("failure")
            .apply { isAccessible = true }
            .get(retained),
        )
        assertSame(firstCause, primary.cause)
        assertSame(automatic, invalidation.get())
        assertSame(invalidationCause, (invalidation.get() as LiveCompletion.Refused).failure.cause)
        assertSame(primary, (contexts.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure)
        assertEquals(listOf(6, 8, 8, 8), fixture.operations)
        fixture.requests.forEach { assertEquals(owner.installation.value, it.getLong(32)) }
    }

    @Test
    fun `structural failure retains known owner failed invalidation and binding through retry`() {
        val fixture = Transport()
        fixture.record[100] = 0
        fixture.lifecycleStatus = 4
        fixture.lifecycleReason = 8
        val contexts = adapter(fixture)
        val lease = task(contexts)
        val binding = contexts.javaClass
            .getDeclaredField("binding")
            .apply { isAccessible = true }
            .get(contexts)
        assertTrue(contexts.copy(lease) is LiveRead.Incomplete)
        val latch = contexts.javaClass
            .getDeclaredField("corruption")
            .apply { isAccessible = true }
            .get(contexts) as AtomicReference<*>
        val retained = latch.get()!!
        assertSame(
            lease.worker.owner,
            retained.javaClass
            .getDeclaredField("owner")
            .apply { isAccessible = true }
            .get(retained),
        )
        val invalidation = retained.javaClass
            .getDeclaredField("invalidation")
            .apply { isAccessible = true }
            .get(retained) as AtomicReference<*>
        assertTrue(invalidation.get() is LiveCompletion.Refused)
        fixture.lifecycleStatus = 3
        fixture.lifecycleReason = 22
        assertEquals(LiveCompletion.Pending(LivePendingReason.CleanupPending), contexts.retireInstallation(lease.worker.owner))
        assertSame(
            binding,
            contexts.javaClass
            .getDeclaredField("binding")
            .apply { isAccessible = true }
            .get(contexts),
        )
        fixture.lifecycleStatus = 0
        fixture.lifecycleReason = 0
        assertEquals(LiveCompletion.Complete, contexts.retireInstallation(lease.worker.owner))
        assertEquals(listOf(6, 8, 8), fixture.operations)
    }

    @Test
    fun `every operation accepts only its canonical status reason pairs`() {
        val common = setOf(0 to 0, 6 to 16, 5 to 13, 8 to 18)
        val readiness = setOf(5 to 11, 5 to 12, 5 to 19)
        val stale = setOf(4 to 8, 4 to 9, 4 to 10, 4 to 20)
        val rows = listOf(
            common + readiness + stale + setOf(3 to 7, 7 to 15, 1 to 1, 1 to 2, 2 to 3, 2 to 4, 2 to 5, 2 to 6),
            common + readiness + setOf(3 to 7, 4 to 10, 7 to 14, 7 to 15),
            common + readiness + stale + setOf(3 to 7, 7 to 15),
            common + readiness + stale + setOf(3 to 7, 7 to 14, 7 to 15),
            common + stale + setOf(3 to 7, 7 to 15),
            common + setOf(4 to 8, 3 to 7),
            common + setOf(4 to 8),
            common + setOf(3 to 7, 4 to 8, 3 to 21),
            common + setOf(3 to 7, 4 to 8, 3 to 22),
        )
        for (op in 0..8) {
            for (status in (0..8).toList() + listOf(99, Int.MIN_VALUE)) {
                for (reason in 0..23) {
            val transport = Transport()
            transport.lifecycleStatus = status
            transport.lifecycleReason = reason
            transport.copyStatus = status
            transport.copyReason = reason
            val adapter = adapter(transport)
            val result = operation(op, adapter, task(adapter))
            val malformed = when (result) {
                is LiveRead.Incomplete -> result.reason == LiveIncompleteReason.MalformedNativeResult
                is LiveSetup.Refused -> result.failure.reason == LiveFailureReason.Malformed && result.failure.status == LiveFailureStatus.InternalFailure
                is LiveCompletion.Refused -> result.failure.reason == LiveFailureReason.Malformed && result.failure.status == LiveFailureStatus.InternalFailure
                else -> false
            }
            assertEquals((status to reason) !in rows[op], malformed, "op=$op status=$status reason=$reason result=$result")
        }
            }
        }
    }

    @Test
    fun `all snapshot structural families fail closed and invalidate captured identity`() {
        val mutations = listOf<Pair<Long, Long>>(
            0L to 0,
            4L to 2151,
            4L to 2153,
            8L to 99,
            12L to 1,
            16L to 0,
            16L to 1,
            24L to 0,
            24L to 1,
            32L to 0,
            32L to (1L shl 60),
            40L to 5,
            48L to 6,
            56L to 4,
            64L to 0,
            64L to (1L shl 60),
            64L to Long.MAX_VALUE,
            72L to 1,
            80L to 0,
            80L to (1L shl 60),
            88L to 0,
            88L to -1,
            92L to 0,
            92L to -1,
            96L to 0,
            96L to 3,
            100L to 0,
            100L to 129,
            104L to 0,
            104L to 99,
            104L to (1L shl 60),
            120L to 1,
            128L to 1,
            2144L to 1,
        )
        val ints = setOf(0L, 4L, 8L, 12L, 88L, 92L, 96L, 100L)
        for ((offset, value) in mutations) {
            val fixture = Transport()
            val bytes = MemorySegment.ofArray(fixture.record)
            if (offset in ints) bytes.set(JAVA_INT.withByteAlignment(1), offset, value.toInt()) else bytes.set(JAVA_LONG.withByteAlignment(1), offset, value)
            val adapter = adapter(fixture)
            assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), adapter.copy(task(adapter)), "$offset=$value")
            assertEquals(listOf(6), fixture.operations)
            assertEquals(-1L, fixture.requests.single().getLong(16))
            assertEquals(2L, fixture.requests.single().getLong(32))
        }
    }

    @Test
    fun `expected invocation binding mask and all 128 signed frames are checked`() {
        val fixture = Transport()
        val adapter = adapter(fixture)
        assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), adapter.copy(task(adapter), InvocationId(10)))
        val full = Transport()
        val bytes = MemorySegment.ofArray(full.record)
        bytes.set(JAVA_INT.withByteAlignment(1), 100, 128)
        for (index in 0 until 128) {
            bytes.set(JAVA_LONG.withByteAlignment(1), 104L + index * 16, 7)
            bytes.set(JAVA_LONG.withByteAlignment(1), 112L + index * 16, Long.MIN_VALUE + index)
        }
        val contexts = adapter(full)
        val snapshot = (contexts.copy(task(contexts), InvocationId(8)) as LiveRead.Available).snapshot
        assertEquals(128, snapshot.frames.size)
        assertEquals(Long.MIN_VALUE + 127, snapshot.frames.last().location)
        val excluded = adapter(Transport(), mask = 2)
        assertTrue(excluded.copy(task(excluded)) is LiveRead.Incomplete)
    }

    @Test
    fun `returned header must agree and unsuccessful payload must be entirely zero`() {
        for (offset in listOf(16L, 32L, 84L, 104L, 2151L)) {
            val fixture = Transport()
            fixture.copyStatus = 3
            fixture.copyReason = 7
            fixture.failedPayloadOffset = offset
            val adapter = adapter(fixture)
            assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), adapter.copy(task(adapter)))
        }
        val fixture = Transport()
        fixture.returnStatus = Int.MIN_VALUE
        val adapter = adapter(fixture)
        assertTrue(adapter.copy(task(adapter)) is LiveRead.Incomplete)
    }

    @Test
    fun `native input mutation is structural and original request buffers close`() {
        val fixture = Transport()
        fixture.mutateRequest = true
        val adapter = adapter(fixture)
        assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), adapter.copy(task(adapter)))
        assertFalse(fixture.lastRequest!!.scope().isAlive)
    }

    @Test
    fun `explicit mock provider is opt in and captured runtime survives a global swap`() {
        val fixture = Transport()
        val captured = MockNativeEngine().apply { process.tid = Tid(42) }
        val adapter = adapter(fixture, captured)
        assertSame(UnsupportedNativeStackContexts, MockNativeEngine().stackContextsOrUnsupported())
        assertSame(adapter, MockNativeStackContexts(adapter).stackContextsOrUnsupported())
        val previous = LinuxNative.engineIdentity as NativeEngine
        try {
            LinuxNative.setEngine(MockNativeEngine().apply { process.tid = Tid(999) })
            val worker = (adapter.registerCurrent(task(adapter).worker.owner) as LiveSetup.Ready).value
            assertEquals(Tid(42), worker.tid)
            assertSame(adapter, worker.owner.capability)
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    private fun operation(
        op: Int,
        adapter: NativeStackContexts,
        task: TaskLease,
    ): Any =
        when (op) {
        0 -> adapter.copy(task)
        1 -> adapter.reserve(WorkerCapacity(1))
        2 -> adapter.seal(task.worker.enrollment.owner, task.worker.enrollment)
        3 -> adapter.registerCurrent(task.worker.owner)
        4 -> adapter.beginCurrent(task.worker)
        5 -> adapter.endCurrent(task)
        6 -> adapter.invalidate(task.worker.owner)
        7 -> adapter.retireWorker(task.worker)
        8 -> adapter.retireInstallation(task.worker.owner)
        else -> error("invalid test operation")
    }

    private fun ByteArray.getLong(offset: Long): Long = MemorySegment.ofArray(this).get(JAVA_LONG.withByteAlignment(1), offset)

    @Test
    fun `captured non-real runtime refuses before JNI or path lookup`() {
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "not-qualified",
            emptyList(),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
        val previous = LinuxNative.engineIdentity as NativeEngine
        try {
            LinuxNative.setEngine(MockNativeEngine())
            val captured = LinuxNative.snapshot()
            LinuxNative.setEngine(RealNativeEngine)
            val contexts = RealNativeStackContexts.bootstrap(Path.of("/no-such-live-agent.so"), identity, captured)
            val failure = (contexts.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure
            assertEquals(LiveFailureStatus.Unsupported, failure.status)
            assertEquals(LiveFailureReason.RuntimeMismatch, failure.reason)
            val typed = RealNativeStackContexts.bootstrapChecked(Path.of("/no-such-live-agent.so"), identity, captured)
            assertTrue(typed is RealNativeStackContexts.BootstrapResult.Refused)
            typed as RealNativeStackContexts.BootstrapResult.Refused
            assertEquals(LiveFailureStatus.Unsupported, typed.failure.status)
            assertEquals(LiveFailureReason.RuntimeMismatch, typed.failure.reason)
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `captured real runtime ignores ambient mock`() {
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "not-qualified",
            emptyList(),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
        val previous = LinuxNative.engineIdentity as NativeEngine
        try {
            LinuxNative.setEngine(RealNativeEngine)
            val captured = LinuxNative.snapshot()
            LinuxNative.setEngine(MockNativeEngine())
            val contexts = RealNativeStackContexts.bootstrap(Path.of("/no-such-live-agent.so"), identity, captured)
            val failure = (contexts.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure
            assertEquals(LiveFailureStatus.Unsupported, failure.status)
            assertEquals(LiveFailureReason.MissingBinding, failure.reason)
            assertTrue(failure.cause is UnsatisfiedLinkError)
            val typed = RealNativeStackContexts.bootstrapChecked(Path.of("/no-such-live-agent.so"), identity, captured)
            assertTrue(typed is RealNativeStackContexts.BootstrapResult.Refused)
            typed as RealNativeStackContexts.BootstrapResult.Refused
            assertEquals(LiveFailureStatus.Unsupported, typed.failure.status)
            assertEquals(LiveFailureReason.MissingBinding, typed.failure.reason)
            assertTrue(typed.failure.cause is UnsatisfiedLinkError)
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `old bootstrap descriptor remains callable and uses ambient snapshot`() {
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "not-qualified",
            emptyList(),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
        val previous = LinuxNative.engineIdentity as NativeEngine
        try {
            LinuxNative.setEngine(MockNativeEngine())
            val method = RealNativeStackContexts.Companion.javaClass.getDeclaredMethod(
                "bootstrap",
                Path::class.java,
                LiveStackBootstrap.LiveDeploymentIdentity::class.java,
            )
            assertEquals(NativeStackContexts::class.java, method.returnType)
            val contexts = method.invoke(RealNativeStackContexts.Companion, Path.of("/no-such-live-agent.so"), identity) as NativeStackContexts
            val failure = (contexts.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure
            assertEquals(LiveFailureStatus.Unsupported, failure.status)
            assertEquals(LiveFailureReason.RuntimeMismatch, failure.reason)
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `missing startup JNI refuses with retained cause before nonexistent artifact lookup`() {
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "not-qualified",
            emptyList(),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
        val previous = LinuxNative.engineIdentity as NativeEngine
        try {
            LinuxNative.setEngine(RealNativeEngine)
            val contexts = RealNativeStackContexts.bootstrap(Path.of("/no-such-live-agent.so"), identity)
            val failure = (contexts.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure
            assertEquals(LiveFailureStatus.Unsupported, failure.status)
            assertEquals(LiveFailureReason.MissingBinding, failure.reason)
            assertTrue(failure.cause is UnsatisfiedLinkError)
            assertEquals(LiveRead.Unsupported(LiveUnsupportedReason.MissingBinding), contexts.copy(task(contexts)))
            assertSame(UnsupportedNativeStackContexts, RealNativeEngine.stackContexts)
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `abiOracle matches actual header every member alignment and frame stride`() {
        val process = ProcessBuilder(Path.of("build", "abi-oracle", "layout-oracle").toAbsolutePath().toString()).start()
        val values = process.inputStream
            .bufferedReader()
            .readLines()
            .associate { line -> line.substringBefore('=') to line.substringAfter('=').toLong() }
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        for ((name, layout) in mapOf(
            "request" to LiveStackLayouts.request,
                "frame" to LiveStackLayouts.frame,
                "snapshot" to LiveStackLayouts.snapshot,
                "receipt" to LiveStackLayouts.receipt,
            "info" to LiveStackLayouts.info,
        )) {
            val type = "mw_live_${name}_v1"
            assertEquals(layout.byteSize(), values["size.$type"], type)
            assertEquals(layout.byteAlignment(), values["align.$type"], type)
            for (member in (layout as StructLayout).memberLayouts()) {
                val field = member.name().orElseThrow()
                assertEquals(
                    layout.byteOffset(
                        java.lang.foreign.MemoryLayout.PathElement
                    .groupElement(field),
                    ),
                        values["offset.$type.$field"],
                    "$type.$field",
                )
            }
        }
        assertEquals(16L, values["stride.mw_live_snapshot_v1.frames"])
    }

    @Test
    fun `setup keeps staging cleanup owner and seals actual input identities before registering`() {
        val fixture = Transport()
        val adapter = adapter(fixture)
        val staging = (adapter.reserve(WorkerCapacity(1)) as LiveSetup.Ready).value
        val method = String::class.java.getDeclaredMethod("length")
        val classes = mutableListOf(String::class.java)
        val methods = mutableListOf<Executable>(method)
        val enrollment = (adapter.enroll(staging, classes, methods) as LiveSetup.Ready).value
        classes.clear()
        methods.clear()
        assertSame(staging, enrollment.owner)
        assertSame(method, enrollment.methods.single().identity)
        assertEquals("()I", enrollment.methods.single().descriptor)
        val sealed = (adapter.seal(staging, enrollment) as LiveSetup.Ready).value
        assertNotSame(staging, sealed)
        val worker = (adapter.registerCurrent(sealed) as LiveSetup.Ready).value
        assertSame(sealed, worker.owner)
        val lease = (adapter.beginCurrent(worker) as LiveSetup.Ready).value
        assertEquals(TaskId(5), lease.task)
        assertEquals(listOf(1, 2, 3, 4), fixture.operations)
        assertEquals(listOf("enroll", "seal", "prepare"), fixture.bridgeCalls)
    }

    @Test
    fun `foreign owners refuse before transport or JNI`() {
        val fixture = Transport()
        val first = adapter(fixture)
        val second = adapter(fixture)
        val task = task(first)
        assertTrue(second.beginCurrent(task.worker) is LiveSetup.Refused)
        assertTrue(second.endCurrent(task) is LiveCompletion.Refused)
        assertTrue(second.copy(task) is LiveRead.Stale)
        assertTrue(second.enroll(task.worker.enrollment.owner, listOf(String::class.java), listOf(String::class.java.getDeclaredMethod("length"))) is LiveSetup.Refused)
        assertTrue(fixture.operations.isEmpty())
        assertTrue(fixture.bridgeCalls.isEmpty())
    }

    @Test
    fun `literal complete snapshot becomes an owned copy with signed locations`() {
        val fixture = Transport()
        val adapter = adapter(fixture)
        val task = task(adapter)
        val result = adapter.copy(task) as LiveRead.Available
        assertSame(task, result.snapshot.task)
        assertEquals(listOf(LiveFrame(MethodToken(7), -1)), result.snapshot.frames)
        assertEquals(InvocationId(8), result.snapshot.invocation)
        assertEquals(PublicationId(9), result.snapshot.publication)
        assertFalse(fixture.lastOutput!!.scope().isAlive)
        fixture.record.fill(0)
        assertEquals(
            -1L,
            result.snapshot.frames
            .single()
            .location,
        )
    }

    @Test
    fun `malformed copy latches fresh authority and invalidates only captured known owner`() {
        val fixture = Transport()
        fixture.record[100] = 0
        val adapter = adapter(fixture)
        val task = task(adapter)
        assertEquals(LiveRead.Incomplete(LiveIncompleteReason.MalformedNativeResult), adapter.copy(task))
        assertEquals(listOf(6), fixture.operations)
        assertEquals(LiveFailureReason.Malformed, (adapter.reserve(WorkerCapacity(1)) as LiveSetup.Refused).failure.reason)
        assertEquals(LiveCompletion.Complete, adapter.retireInstallation(task.worker.owner))
        assertEquals(listOf(6, 8), fixture.operations)
    }

    @Test
    fun `busy cleanup keeps original owner and forwards next Begin to native authority without retry`() {
        val fixture = Transport()
        val adapter = adapter(fixture)
        val task = task(adapter)
        fixture.lifecycleStatus = 3
        fixture.lifecycleReason = 7
        assertEquals(LiveCompletion.Pending(LivePendingReason.SlotContended), adapter.endCurrent(task))
        assertEquals(LiveFailureStatus.Busy, (adapter.beginCurrent(task.worker) as LiveSetup.Refused).failure.status)
        fixture.lifecycleStatus = 0
        fixture.lifecycleReason = 0
        assertEquals(LiveCompletion.Complete, adapter.endCurrent(task))
        assertEquals(listOf(5, 4, 5), fixture.operations)
        assertSame(adapter, task.worker.owner.capability)
    }

    private fun task(capability: NativeStackContexts): TaskLease {
        val staging = InstallationOwner(capability, process, InstallationId(2), WorkerCapacity(1))
        val method = String::class.java.getDeclaredMethod("length")
        val enrolled =
            Enrollment(staging, CodeGeneration(3), listOf(EnrolledClass(ClassToken(6), String::class.java)), listOf(EnrolledMethod(MethodToken(7), method, "length", "()I", method.modifiers)))
        return TaskLease(WorkerOwner(staging.sealed(enrolled), WorkerId(4), Tid(42)), TaskId(5))
    }

    // This constructs only a host transport fixture, never a bootstrap/deployment positive.
    private fun adapter(
        transport: Transport,
        runtime: MockNativeEngine = MockNativeEngine().apply { process.tid = Tid(42) },
        mask: Int = 3,
        afterValidated: ((Int) -> Unit)? = null,
    ): NativeStackContexts {
        val bindingClass = RealNativeStackContexts::class.java.declaredClasses.single { it.simpleName == "Binding" }
        val bindingConstructor = bindingClass.declaredConstructors.single().apply { isAccessible = true }
        val lookup = MethodHandles.lookup()
        val info = lookup.findVirtual(Transport::class.java, "info", MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType)).bindTo(transport)
        val type = MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType)
        val lifecycle = lookup.findVirtual(Transport::class.java, "lifecycle", type).bindTo(transport)
        val copy = lookup.findVirtual(Transport::class.java, "copy", type).bindTo(transport)
        val binding = bindingConstructor.newInstance(Arena.ofAuto(), SymbolLookup { Optional.empty() }, info, lifecycle, copy)
        val bridge = object : LiveStackBridge {
            override fun enroll(
                installation: Long,
                owners: Array<Class<*>>,
                methods: Array<Executable>,
            ): LongArray {
                transport.bridgeCalls.add("enroll")
                transport.bridgeFailure?.let { throw it }
                return transport.enrollmentTuple
            }

            override fun checkSeal(
                installation: Long,
                generation: Long,
            ) {
                transport.bridgeCalls.add("seal")
            }

            override fun prepareWorker() {
                transport.bridgeCalls.add("prepare")
                transport.prepareWorkerFailure?.let { throw it }
            }
        }
        val constructor = RealNativeStackContexts::class.java.declaredConstructors
            .single { !it.isSynthetic && it.parameterCount == if (afterValidated == null) 5 else 6 }
            .apply { isAccessible = true }
        return if (afterValidated == null) {
            constructor.newInstance(binding, RuntimeSnapshot(runtime), process, bridge, mask) as NativeStackContexts
        } else {
            constructor.newInstance(binding, RuntimeSnapshot(runtime), process, bridge, mask, afterValidated) as NativeStackContexts
        }
    }

    class Transport {
        val operations = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val bridgeCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var registerBarrier: java.util.concurrent.CyclicBarrier? = null
        var prepareWorkerFailure: Throwable? = null
        var mutateLifecycleRequest = false
        var lifecycleFailure: Throwable? = null
        var lifecycleStatus = 0
        var lifecycleReason = 0
        var enrollmentTuple = longArrayOf(3, 1, 1, 6, 7)
        var bridgeFailure: Throwable? = null
        var receiptMutation: Long? = null
        var receiptOverride: Pair<Long, Long>? = null
        var infoStatus = 0
        var infoReason = 0
        var copyStatus = 0
        var copyReason = 0
        var returnStatus: Int? = null
        var failedPayloadOffset: Long? = null
        var mutateRequest = false
        var lastRequest: MemorySegment? = null
        var copyRequest: ByteArray? = null
        val requests = mutableListOf<ByteArray>()
        val infoRecord = ByteArray(64).also { bytes ->
            val segment = MemorySegment.ofArray(bytes)
            for ((offset, value) in listOf(0L to 1, 4L to 64, 32L to 41, 36L to 1, 40L to 256, 44L to 512, 48L to 4096, 52L to 128, 56L to 3)) segment.set(JAVA_INT.withByteAlignment(1), offset, value)
            segment.set(JAVA_LONG.withByteAlignment(1), 16, -1)
            segment.set(JAVA_LONG.withByteAlignment(1), 24, Long.MIN_VALUE)
        }
        var lastOutput: MemorySegment? = null
        val record = ByteArray(2152).also { bytes ->
            val segment = MemorySegment.ofArray(bytes)
            for ((offset, value) in listOf(0L to 1, 4L to 2152, 88L to 41, 92L to 42, 96L to 1, 100L to 1)) segment.set(JAVA_INT.withByteAlignment(1), offset, value)
            for ((offset, value) in listOf(
                    16L to -1L,
                    24L to Long.MIN_VALUE,
                    32L to 2L,
                    40L to 4L,
                    48L to 5L,
                    56L to 3L,
                    64L to 8L,
                    80L to 9L,
                    104L to 7L,
                    112L to -1L,
                )) {
                    segment.set(JAVA_LONG.withByteAlignment(1), offset, value)
                }
        }

        fun info(
            out: MemorySegment,
            bytes: Int,
        ): Int {
            assertEquals(64, bytes)
            if (infoStatus == 0) {
                MemorySegment.copy(MemorySegment.ofArray(infoRecord), 0, out, 0, 64)
            } else {
                out.fill(0)
                out.set(JAVA_INT, 0, 1)
                out.set(JAVA_INT, 4, 64)
            }
            out.set(JAVA_INT, 8, infoStatus)
            out.set(JAVA_INT, 12, infoReason)
            return infoStatus
        }

        fun copy(
            request: MemorySegment,
            requestBytes: Int,
            out: MemorySegment,
            outBytes: Int,
        ): Int {
            assertEquals(88, requestBytes)
            assertEquals(2152, outBytes)
            assertEquals(0, request.get(JAVA_INT, 8))
            lastOutput = out
            lastRequest = request
            copyRequest = request.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)
            if (mutateRequest) request.set(JAVA_INT, 12, 1)
            if (copyStatus == 0) {
                MemorySegment.copy(MemorySegment.ofArray(record), 0, out, 0, 2152)
            } else {
                out.fill(0)
                out.set(JAVA_INT, 0, 1)
                out.set(JAVA_INT, 4, 2152)
                failedPayloadOffset?.let { out.set(java.lang.foreign.ValueLayout.JAVA_BYTE, it, 1) }
            }
            if (copyStatus != 0 || copyReason != 0) {
                out.set(JAVA_INT, 8, copyStatus)
                out.set(JAVA_INT, 12, copyReason)
            }
            return returnStatus ?: copyStatus
        }

        fun lifecycle(
            request: MemorySegment,
            requestBytes: Int,
            out: MemorySegment,
            outBytes: Int,
        ): Int {
            assertEquals(88, requestBytes)
            assertEquals(88, outBytes)
            val op = request.get(JAVA_INT, 8)
            operations.add(op)
            if (op == 3) registerBarrier?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            lifecycleFailure?.let { throw it }
            synchronized(requests) { requests.add(request.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)) }
            out.fill(0)
            out.set(JAVA_INT, 0, 1)
            out.set(JAVA_INT, 4, 88)
            out.set(JAVA_INT, 8, lifecycleStatus)
            out.set(JAVA_INT, 12, lifecycleReason)
            if (lifecycleStatus == 0) {
                // Literal oracle records, deliberately independent of request encoding.
                out.set(JAVA_LONG, 16, -1)
                out.set(JAVA_LONG, 24, Long.MIN_VALUE)
                out.set(JAVA_LONG, 32, 2)
                out.set(JAVA_INT, 72, 41)
                if (op in listOf(3, 4, 5, 7)) out.set(JAVA_LONG, 40, 4)
                if (op in listOf(4, 5)) out.set(JAVA_LONG, 48, 5)
                if (op in listOf(2, 3, 4, 5, 7)) out.set(JAVA_LONG, 56, 3)
                if (op in listOf(3, 4, 5, 7)) out.set(JAVA_INT, 76, 42)
                if (op in listOf(1, 2, 3, 7)) {
                    out.set(JAVA_INT, 80, 1)
                    out.set(JAVA_INT, 84, 1)
                }
            }
            receiptMutation?.let { offset ->
                if (op == 1 && offset == 32L || op == 3 && offset == 40L || op == 4 && offset == 48L) {
                    out.set(JAVA_LONG, offset, 0)
                } else {
                    out.set(JAVA_INT, offset, out.get(JAVA_INT, offset) xor 1)
                }
            }
            receiptOverride?.let { (offset, value) -> out.set(JAVA_LONG, offset, value) }
            if (mutateLifecycleRequest && op == 3) request.set(JAVA_INT, 12, 1)
            return lifecycleStatus
        }
    }

    @Test
    fun `status helpers forward exact arguments once and preserve unsigned status bits`() {
        val lookup = MethodHandles.lookup()
        val info = lookup.findVirtual(javaClass, "info", MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType)).bindTo(this)
        val buffer = lookup
            .findVirtual(
            javaClass,
            "buffer",
            MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType),
        ).bindTo(this)
        Arena.ofConfined().use { arena ->
            val request = arena.allocate(88, 8)
            val out = arena.allocate(2152, 8)
            expectedRequest = request
            expectedOutput = out
            for (bits in (0..8).toList() + listOf(99, Int.MIN_VALUE, -1)) {
                status = bits
                val before = calls
                assertEquals(bits, SyscallInvoker.liveInfoStatus(info, out, 2152))
                assertEquals(bits, SyscallInvoker.liveBufferStatus(buffer, request, 88, out, 2152))
                assertEquals(before + 2, calls)
            }
        }
    }

    fun info(
        out: MemorySegment,
        bytes: Int,
    ): Int {
        assertSame(expectedOutput, out)
        assertEquals(2152L, out.byteSize())
        assertEquals(2152, bytes)
        calls++
        return status
    }

    fun buffer(
        request: MemorySegment,
        requestBytes: Int,
        out: MemorySegment,
        outBytes: Int,
    ): Int {
        assertSame(expectedRequest, request)
        assertEquals(88L, request.byteSize())
        assertEquals(88, requestBytes)
        return info(out, outBytes)
    }
}
