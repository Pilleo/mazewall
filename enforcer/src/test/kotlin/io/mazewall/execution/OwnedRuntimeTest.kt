package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedRuntimeTest {
    private val timeout = Duration.ofSeconds(5)
    private lateinit var engine: MockNativeEngine

    @BeforeEach
    fun prepareNative() {
        engine = MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        }
        LinuxNative.setEngine(engine)
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
    }

    @AfterEach
    fun resetNative() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    private fun open(): ManagedRuntime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)

    private fun policy(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build()

    @Test
    fun `diagnostics retain physical lifetime after cancellation and never advance retirement`() {
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val initial = domain.diagnostics()
            assertEquals(ExecutionDiagnosticState.READY, initial.state)
            assertEquals(domain.generation, assertIs<ExecutionGeneration.InProcess>(initial.generation).generation)
            assertEquals(0L, initial.invocations.admitted)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        cleanupEntered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                },
                )
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("capacity rejection entered") }) }
                assertEquals(CancellationDisposition.REQUESTED, handle.requestCancellation())
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(20)))
                val held = domain.diagnostics()
                assertEquals(ExecutionDiagnosticState.DRAINING, held.state)
                assertEquals(1, held.unfinishedInvocations)
                assertTrue(held.liveWorkers > 0)
                assertFalse(held.retirementComplete)
                assertEquals(1L, held.invocations.admitted)
                assertEquals(1L, held.invocations.rejected)
                assertEquals(1L, held.invocations.cancellationRequests)
                assertEquals(1L, held.invocations.delivered)
                assertEquals(0L, held.invocations.ended)
                assertEquals(held, domain.diagnostics(), "polling must not drain or advance the owner")
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("draining owner admitted") }) }
                release.countDown()
                worker.get().join(5_000)
                assertFalse(worker.get().isAlive)
                assertFalse(domain.diagnostics().retirementComplete, "only the existing drain boundary may publish complete retirement")
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                val ended = domain.diagnostics()
                assertEquals(ExecutionDiagnosticState.CLOSED, ended.state)
                assertTrue(ended.retirementComplete)
                assertEquals(0, ended.liveWorkers)
                assertEquals(0, ended.unfinishedInvocations)
                assertEquals(2L, ended.invocations.rejected)
                assertEquals(1L, ended.invocations.ended)
                assertEquals(0L, initial.invocations.admitted, "captured snapshots must remain immutable")
                assertFalse(held.retirementComplete, "later retirement cannot upgrade a captured pending snapshot")
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `diagnostics retain failed generation identity after fatal delivery`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("diagnostic failed-generation control")
            val handle = runtime.submit(domain, Callable<Int> { throw fatal })
            assertSame(
                fatal,
                assertFailsWith<java.util.concurrent.ExecutionException> {
                handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }.cause,
            )
            assertEquals(ExecutionDiagnosticState.FAILED, domain.diagnostics().state)
            assertEquals(domain.generation, assertIs<ExecutionGeneration.InProcess>(domain.diagnostics().generation).generation)
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            val snapshot = domain.diagnostics()
            assertEquals(ExecutionDiagnosticState.FAILED, snapshot.state)
            assertTrue(snapshot.retirementComplete)
            assertEquals(1L, snapshot.invocations.delivered)
            assertEquals(1L, snapshot.invocations.ended)
        }
    }

    private val declared = ThreadLocal<Map<String, String>>()

    @Test
    fun `custom scoping captures once on caller before readiness and freezes handler membership`() {
        open().use { runtime ->
            val caller = Thread.currentThread()
            val reads = AtomicInteger()
            val handlers = mutableMapOf<Syscall, ScopingHandler>()
            val scoping = object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler>
                    get() {
                        assertTrue(Thread.currentThread() === caller)
                        reads.incrementAndGet()
                        return handlers
                    }
            }
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) handlers[Syscall.GETPID] = { _, _, _ -> true }
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            val domain = runtime.prepare(policy(), DomainLimits(1, 1), scoping)
            assertEquals(1, reads.get())
            assertTrue(handlers.isNotEmpty())
            val handle = runtime.submit(domain, Callable { Thread.currentThread() })
            val worker = handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertTrue(worker !== caller)
            assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun `custom capture failure preserves original ordinary and fatal identity with zero entry`() {
        open().use { runtime ->
            val entries = AtomicInteger()
            for (failure in listOf(IllegalArgumentException("capture failure"), LinkageError("capture fatal"))) {
                val scoping = object : StacktraceScopingPolicy {
                    override val handlers: Map<Syscall, ScopingHandler>
                        get() = throw failure
                }
                val observed = assertFailsWith<Throwable> {
                    val domain = runtime.prepare(policy(), DomainLimits(1, 1), scoping)
                    runtime.submit(domain, Callable { entries.incrementAndGet() })
                }
                assertTrue(observed === failure)
            }
            assertEquals(0, entries.get())
            assertEquals(42, Mazewall.callContained(runtime, runtime.prepare(policy(), DomainLimits(1, 1)), Callable { 42 }))
        }
    }

    @Test
    fun `required custom supervision without bootstrap prevents body entry and retires generation`() {
        open().use { runtime ->
            val entries = AtomicInteger()
            val scoping = object : StacktraceScopingPolicy {
                override val handlers = mapOf<Syscall, ScopingHandler>(Syscall.CONNECT to { _, _, _ -> true })
            }
            val failure = assertFailsWith<IllegalStateException> {
                val domain = runtime.prepare(policy(), DomainLimits(1, 1), scoping)
                runtime.submit(domain, Callable { entries.incrementAndGet() })
            }
            assertTrue(failure.message.orEmpty().contains("Cannot spawn SupervisorDaemon"))
            assertEquals(0, entries.get())
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
        }
    }

    private fun bridge(
        applied: AtomicInteger = AtomicInteger(),
        restore: () -> Unit = {},
    ): InvocationContextBridge =
        InvocationContextBridge {
        val caller = Thread.currentThread()
        check(!caller.name.startsWith("mazewall-manager-"))
        InvocationContextSnapshot(declared.get() ?: emptyMap()) { metadata ->
            applied.incrementAndGet()
            val previous = declared.get()
            declared.set(metadata)
            AutoCloseable {
                restore()
                if (previous == null) declared.remove() else declared.set(previous)
            }
        }
    }

    @Test
    fun `captured immutable metadata alternates on exact worker and restores nested one permit`() {
        val unrelated = ThreadLocal<String>()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val caller = Thread.currentThread()
            val worker = java.util.concurrent.atomic
                .AtomicReference<Thread>()
            unrelated.set("never-transfer")
            try {
                for (name in listOf("A", "B", "A")) {
                    val mutable = mutableMapOf("name" to name)
                    declared.set(mutable)
                    val captured = runtime.captureInvocation(
                        domain,
                        bridge(),
                        Callable {
                        val current = Thread.currentThread()
                        assertTrue(current !== caller && !current.isVirtual)
                        assertTrue(worker.compareAndSet(null, current) || worker.get() === current)
                        assertNull(unrelated.get())
                        assertEquals(name, declared.get()["name"])
                        assertFailsWith<UnsupportedOperationException> { (declared.get() as MutableMap)["name"] = "mutation" }
                        declared.set(mapOf("name" to "inner"))
                        val inner = runtime.captureInvocation(
                            domain,
                            bridge(),
                            Callable {
                            assertTrue(Thread.currentThread() === current)
                            assertEquals("inner", declared.get()["name"])
                            42
                        },
                        )
                        declared.set(mapOf("name" to name))
                        assertEquals(42, Mazewall.callContained(runtime, domain, inner))
                        assertEquals(name, declared.get()["name"])
                        name
                    },
                    )
                    mutable["name"] = "caller-change"
                    val invocation = runtime.submit(domain, captured)
                    assertEquals(name, invocation.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    assertNull(assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                    assertEquals("caller-change", declared.get()["name"])
                    val clean = runtime.submit(domain, Callable { declared.get() })
                    assertNull(clean.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    assertNull(assertIs<ExecutionEnd.Finished>(clean.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                }
            } finally {
                unrelated.remove()
                declared.remove()
            }
        }
    }

    @Test
    fun `queued capture precedes caller changes and queued cancellation never applies context`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val applied = AtomicInteger()
            val calls = AtomicInteger()
            try {
                val held = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                declared.set(mapOf("name" to "queued"))
                val queued = runtime.submit(domain, runtime.captureInvocation(domain, bridge(applied), Callable { calls.incrementAndGet() }))
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = runtime.submit(domain, runtime.captureInvocation(domain, bridge(applied), Callable { declared.get()["name"] }))
                declared.set(mapOf("name" to "changed"))
                assertEquals(0, applied.get())
                release.countDown()
                held.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals("queued", next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                next.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals(1, applied.get())
                assertEquals(0, calls.get())
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
            } finally {
                release.countDown()
                declared.remove()
            }
        }
    }

    @Test
    fun `held restoration keeps physical end and admission after cooperative cancellation`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val restoring = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                declared.set(mapOf("name" to "cancelled"))
                val captured = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    restoring.countDown()
                    while (release.count > 0) {
                        try {
                            check(release.await(5, TimeUnit.SECONDS))
                        } catch (_: InterruptedException) {
                            // restoration still owns context
                        }
                    }
                }),
                    Callable {
                    assertEquals("cancelled", declared.get()["name"])
                    entered.countDown()
                    CountDownLatch(1).await()
                },
                )
                val handle = runtime.submit(domain, captured)
                check(entered.await(5, TimeUnit.SECONDS))
                handle.requestCancellation()
                check(restoring.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
            } finally {
                release.countDown()
                declared.remove()
            }
        }
    }

    @Test
    fun `restoration failure quarantines generation and preserves fatal primary identity`() {
        for (fatal in listOf(false, true)) {
            open().use { runtime ->
                val domain = runtime.prepare(policy(), DomainLimits(1, 1))
                val primary: Throwable = if (fatal) LinkageError("body fatal") else IllegalArgumentException("body failure")
                val cleanup = IllegalStateException("restore unproved")
                val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable<Int> { throw primary }))
                val delivered = assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause
                assertTrue(delivered === primary)
                assertTrue(primary.suppressed.any { it === cleanup })
                assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === primary)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("unsafe reuse") }) }
            }
        }
    }

    @Test
    fun `restoration quarantine preserves canonical containment delivery and original denied cause`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val denied = AccessDeniedException("declared body denial")
            val cleanup = IllegalStateException("unproved restore")
            val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable<Int> { throw denied }))
            val delivered = assertIs<ContainmentViolationException>(
                assertFailsWith<java.util.concurrent.ExecutionException> {
                handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }.cause,
            )
            assertTrue(delivered.cause === denied)
            assertTrue(denied.suppressed.any { it === cleanup })
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === delivered)
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `caught nested restoration failure cannot become successful outer evidence`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val cleanup = IllegalStateException("nested restore")
            val handle = runtime.submit(
                domain,
                Callable {
                val nested = runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable { 1 })
                assertTrue(assertFailsWith<IllegalStateException> { Mazewall.callContained(runtime, domain, nested) } === cleanup)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === cleanup)
                42
            },
            )
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === cleanup)
            handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `caught nested fatal with clean restoration still retires generation and retains original evidence`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("nested fatal")
            val restored = AtomicInteger()
            val handle = runtime.submit(
                domain,
                Callable {
                val nested = runtime.captureInvocation(domain, bridge(restore = { restored.incrementAndGet() }), Callable<Int> { throw fatal })
                assertTrue(assertFailsWith<LinkageError> { Mazewall.callContained(runtime, domain, nested) } === fatal)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === fatal)
                42
            },
            )
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
            assertEquals(1, restored.get())
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `recorded nested fatal remains terminal when plain or captured outer throws a later failure`() {
        for (capturedOuter in listOf(false, true)) {
            for (outerFailure in listOf(IllegalArgumentException("later business"), LinkageError("later fatal"))) {
                assertNestedFatalTerminal(capturedOuter, outerFailure)
            }
        }
    }

    @Test
    fun `recorded nested fatal remains terminal through captured outer business failure and cleanup fatal`() {
        assertNestedFatalTerminal(true, IllegalArgumentException("later business"), LinkageError("later cleanup fatal"))
    }

    private fun assertNestedFatalTerminal(
        capturedOuter: Boolean,
        outerFailure: Throwable,
        cleanupFatal: Error? = null,
    ) {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("original nested fatal")
            val innerRestored = AtomicInteger()
            val outerRestored = AtomicInteger()
            val caller = Thread.currentThread()
            val outer = Callable<Int> {
                val worker = Thread.currentThread()
                assertTrue(worker !== caller && !worker.isVirtual)
                if (!capturedOuter) declared.set(mapOf("name" to "outer"))
                val previous = declared.get()
                assertEquals("outer", previous["name"])
                declared.set(mapOf("name" to "inner"))
                val inner = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    assertEquals("inner", declared.get()["name"])
                    innerRestored.incrementAndGet()
                }),
                    Callable<Int> {
                    assertTrue(Thread.currentThread() === worker)
                    assertEquals("inner", declared.get()["name"])
                    throw fatal
                },
                )
                declared.set(previous)
                assertTrue(assertFailsWith<LinkageError> { Mazewall.callContained(runtime, domain, inner) } === fatal)
                assertTrue(declared.get() === previous)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === fatal)
                throw outerFailure
            }
            val contexts = InvocationContextBridge {
                InvocationContextSnapshot(mapOf("name" to "outer")) { metadata ->
                    val previous = declared.get()
                    declared.set(metadata)
                    AutoCloseable {
                        assertEquals("outer", declared.get()["name"])
                        if (previous == null) declared.remove() else declared.set(previous)
                        assertTrue(declared.get() === previous)
                        outerRestored.incrementAndGet()
                        cleanupFatal?.let { throw it }
                    }
                }
            }
            val handle = runtime.submit(domain, if (capturedOuter) runtime.captureInvocation(domain, contexts, outer) else outer)
            val delivered = assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause
            assertTrue(delivered === fatal)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
            assertEquals(1, innerRestored.get())
            assertEquals(if (capturedOuter) 1 else 0, outerRestored.get())
            if (cleanupFatal == null) {
                assertEquals(listOf(outerFailure), fatal.suppressed.toList())
            } else {
                assertEquals(listOf(cleanupFatal), fatal.suppressed.toList())
                assertEquals(listOf(outerFailure), cleanupFatal.suppressed.toList())
            }
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `ordinary body failure restores and capture failure consumes no admission`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val caller = Thread.currentThread()
            val captureFailure = IllegalArgumentException("caller capture")
            val failing = InvocationContextBridge {
                assertTrue(Thread.currentThread() === caller)
                throw captureFailure
            }
            assertTrue(assertFailsWith<IllegalArgumentException> { runtime.captureInvocation(domain, failing, Callable { 0 }) } === captureFailure)
            declared.set(mapOf("name" to "business"))
            try {
                val business = IllegalArgumentException("business")
                val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(), Callable<Int> { throw business }))
                assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === business)
                handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
                val captured = runtime.captureInvocation(domain, bridge(), Callable { 1 })
                assertFailsWith<IllegalStateException> { captured.call() }
                assertEquals("business", declared.get()["name"])
            } finally {
                declared.remove()
            }
        }
    }

    @Test
    fun `application failure has zero body entry and quarantines unproved worker context`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val calls = AtomicInteger()
            val applicationFailure = IllegalStateException("partially applied")
            val context = InvocationContextBridge { InvocationContextSnapshot(emptyMap()) { throw applicationFailure } }
            val handle = runtime.submit(domain, runtime.captureInvocation(domain, context, Callable { calls.incrementAndGet() }))
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === applicationFailure)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === applicationFailure)
            assertEquals(0, calls.get())
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `fatal restoration promotes over business failure and clean restoration preserves body fatal`() {
        for (restoreFatal in listOf(false, true)) {
            open().use { runtime ->
                val domain = runtime.prepare(policy(), DomainLimits(1, 1))
                val fatal = LinkageError("original fatal")
                val business = IllegalArgumentException("original business")
                val restored = CountDownLatch(1)
                val captured = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    restored.countDown()
                    if (restoreFatal) throw fatal
                }),
                    Callable<Int> { if (restoreFatal) throw business else throw fatal },
                )
                val handle = runtime.submit(domain, captured)
                assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
                assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
                assertEquals(0L, restored.count)
                if (restoreFatal) assertTrue(fatal.suppressed.any { it === business })
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
            }
        }
    }

    @Test
    fun `missing required read resource rejects preparation before any domain can admit`() {
        val root = java.nio.file.Files
            .createTempDirectory("missing-prepared-resource-")
        try {
            open().use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root.resolve("missing")).build()
                assertFailsWith<java.nio.file.NoSuchFileException> { runtime.prepare(policy, DomainLimits(1, 1)) }
            }
        } finally {
            java.nio.file.Files
                .delete(root)
        }
    }

    @Test
    fun `missing writable leaf never implicitly grants its parent`() {
        val root = java.nio.file.Files
            .createTempDirectory("missing-prepared-output-")
        try {
            open().use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsReadWrite(root.resolve("future-output")).build()
                assertFailsWith<java.nio.file.NoSuchFileException> { runtime.prepare(policy, DomainLimits(1, 1)) }
            }
        } finally {
            java.nio.file.Files
                .delete(root)
        }
    }

    @Test
    fun `reviewed operation prepares its exact policy and retains configured binding`() {
        open().use { runtime ->
            val binding = OperationBinding("owned-operation", OperationAdapter.CALLABLE, "a".repeat(64), "resources-1", mapOf("entryPoint" to "call()I"))
            val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy()), emptySet(), emptyMap())
            val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), binding)
            val domain = runtime.prepare(reviewed, DomainLimits(1, 1))
            assertEquals("owned-operation", domain.operationBinding?.operationId)
            assertEquals(42, Mazewall.callContained(runtime, domain, Callable { 42 }))
        }
    }

    @Test
    fun `canonical synchronous entry nests inline on the exact active worker`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            assertEquals(
                42,
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                val worker = Thread.currentThread()
                checkNotNull(Mazewall.currentInvocationEvidence())
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertTrue(Thread.currentThread() === worker)
                    42
                },
                )
            },
                ),
            )
            assertNull(Mazewall.currentInvocationEvidence())
        }
    }

    @Test
    fun `caught nested containment failure remains unsuccessful and evidence is not reused`() {
        open().use { runtime ->
            // Result delivery precedes admission release; queue the follow-up on the same worker.
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            assertFailsWith<ContainmentViolationException> {
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertFailsWith<ContainmentViolationException> {
                        Mazewall.callContained(runtime, domain, Callable<Int> { throw AccessDeniedException("private-resource") })
                    }
                    assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure is ContainmentViolationException)
                    42
                },
                )
            }
            assertEquals(
                43,
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                assertNull(Mazewall.currentInvocationEvidence()?.observedFailure)
                43
            },
                ),
            )
        }
    }

    @Test
    fun `different prepared domain cannot run inline or widen a caught transition`() {
        open().use { runtime ->
            val first = runtime.prepare(policy(), DomainLimits(1, 1))
            val second = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = AtomicInteger()
            assertFailsWith<ExecutionTransitionException> {
                Mazewall.callContained(
                    runtime,
                    first,
                    Callable {
                    assertFailsWith<ExecutionTransitionException> {
                        Mazewall.callContained(runtime, second, Callable { entered.incrementAndGet() })
                    }
                    42
                },
                )
            }
            assertEquals(0, entered.get())
        }
    }

    @Test
    fun `declared runtime cannot misreport incompatible process mapping floor`() {
        var opened: ManagedRuntime? = null
        try {
            assertFailsWith<IllegalArgumentException> {
                ManagedRuntime.open(RuntimeProfile.NATIVE_IMAGE, Policy.NO_EXEC_HOTSPOT, timeout, timeout).also { opened = it }
            }
        } finally {
            opened?.close()
        }
    }

    @Test
    fun `worker startup preserves original fatal identity and retires all owned threads`() {
        open().use { runtime ->
            val fatal = AssertionError("native startup fatal")
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) throw fatal
                LinuxNative.SyscallResult.Success(2L)
            }
            val failure = assertFailsWith<AssertionError> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertTrue(failure === fatal)
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }

    @Test
    fun `foreign domains and unsupported nested dispatch cannot enter application work`() {
        open().use { first ->
            open().use { second ->
                val domain = first.prepare(policy(), DomainLimits(1, 1))
                val calls = AtomicInteger()
                assertFailsWith<IllegalArgumentException> { second.submit(domain, Callable { calls.incrementAndGet() }) }
                assertEquals(
                    42,
                    first
                        .submit(
                        domain,
                        Callable {
                    assertFailsWith<RejectedExecutionException> { first.submit(domain, Callable { calls.incrementAndGet() }) }
                    assertFailsWith<IllegalStateException> { first.prepare(policy(), DomainLimits(1, 1)) }
                    42
                },
                    ).result()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS),
                )
                assertEquals(0, calls.get())
            }
        }
    }

    @Test
    fun `ordinary business failure preserves a healthy installed worker`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val business = IllegalArgumentException("business failure")
            val failed = runtime.submit(domain, Callable<Int> { throw business })
            val ended = assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertTrue(ended.failure === business)
            assertEquals(
                42,
                runtime
                .submit(domain, Callable { 42 })
                .result()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun `preparation timeout cancels bootstrap without hiding its live workers`() {
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, Duration.ofSeconds(1), timeout).use { runtime ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs && Thread.currentThread().name.startsWith("mazewall-domain-")) {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // native startup deliberately remains live
                        }
                    }
                }
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            try {
                assertFailsWith<TimeoutException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                check(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                release.countDown()
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed live generation cannot be hidden by preparing replacement capacity`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(2, 2))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val survivor = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val failed = runtime.submit(domain, Callable<Int> { throw AssertionError("generation failure") })
                assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertFalse(survivor.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                release.countDown()
                survivor.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `caller future mutation cannot retire physical work or cancel internal delivery`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                handle.result().toCompletableFuture().cancel(true)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `owned workers retain prepared native authority and refuse caller execution on overload`() {
        open().use { runtime ->
            val foreignCalls = AtomicInteger()
            LinuxNative.setEngine(
                MockNativeEngine().apply {
                process.onPrctl = {
                    foreignCalls.incrementAndGet()
                    LinuxNative.SyscallResult.Error(1, -1L)
                }
            },
            )
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Thread.currentThread()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    assertFalse(Thread.currentThread().isVirtual)
                    assertTrue(Thread.currentThread() !== caller)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val second = runtime.submit(domain, Callable { 43 })
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("must not run on caller") }) }
                release.countDown()
                assertEquals(42, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(43, second.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, foreignCalls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `successful result delivery can retain admission until execution end`() {
        val releaseBody = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val secondEntries = AtomicInteger()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                        worker.set(Thread.currentThread())
                        check(releaseBody.await(5, TimeUnit.SECONDS))
                        42
                    },
                )
                val callback = first.result().whenComplete { value, failure ->
                    assertSame(worker.get(), Thread.currentThread())
                    assertNull(failure)
                    assertEquals(42, value)
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                }
                releaseBody.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                assertEquals(42, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertFalse(first.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> {
                    runtime.submit(domain, Callable { secondEntries.incrementAndGet() })
                }
                assertEquals(0, secondEntries.get())

                releaseCallback.countDown()
                assertEquals(42, callback.toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                val second = runtime.submit(
                    domain,
                    Callable {
                        secondEntries.incrementAndGet()
                        43
                    },
                )
                assertEquals(43, second.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(assertIs<ExecutionEnd.Finished>(second.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                assertEquals(1, secondEntries.get())
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertFalse(checkNotNull(worker.get()).isAlive)
            } finally {
                releaseBody.countDown()
                releaseCallback.countDown()
            }
        }
    }

    @Test
    fun `running cancellation retains admission until physical completion`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.REQUESTED, handle.requestCancellation())
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.ALREADY_ENDED, handle.requestCancellation())
                assertFalse(
                    runtime
                    .submit(domain, Callable { Thread.currentThread().isInterrupted })
                    .result()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS),
                )
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `queued cancellation never enters the body and frees bounded admission`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val queued = runtime.submit(domain, Callable { calls.incrementAndGet() })
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = runtime.submit(domain, Callable { 3 })
                release.countDown()
                assertEquals(1, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(3, next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `drain reports live execution and stops admission until actual retirement`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed worker preparation retires its generation before any application entry`() {
        open().use { runtime ->
            engine.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
            assertFailsWith<PartialInstallationException> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }

    @Test
    fun `failed generation does not await workers that were never started`() {
        val domain = io.mazewall.enforcer.internal.execution.OwnedDomain(
            1,
            DomainLimits(2, 2),
            policy().definition,
            InstallationEnvironment.capture(),
            null,
            null,
            "unstarted-preparation-",
            DefaultStacktraceScopingPolicy,
        )
        val failure = IllegalStateException("startup already failed")
        domain.fail(failure)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val actual = assertFailsWith<IllegalStateException> {
                domain.prepare(
                    io.mazewall.enforcer.internal.execution
                    .Deadline(Duration.ofSeconds(30)),
                )
            }
            assertSame(failure, actual)
        }
        assertTrue(domain.drainSnapshot().retired)
    }
}
