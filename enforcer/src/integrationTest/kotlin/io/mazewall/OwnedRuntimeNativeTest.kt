package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedRuntimeNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `declared metadata uses actual restricted worker through queue nesting cancellation and restoration`() {
        val file = Files.createTempFile("owned-context-private-", ".txt")
        try {
            Files.writeString(file, "host-only")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyOwnedContext", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyOwnedContext(file: String) {
        val timeout = Duration.ofSeconds(10)
        val local = ThreadLocal<Map<String, String>>()
        val unrelated = ThreadLocal<String>()
        val caller = Thread.currentThread()
        val worker = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val applies = java.util.concurrent.atomic
            .AtomicInteger()
        val bridge = InvocationContextBridge {
            assertTrue(Thread.currentThread() === caller || Thread.currentThread() === worker.get())
            InvocationContextSnapshot(local.get() ?: emptyMap()) { metadata ->
                applies.incrementAndGet()
                val prior = local.get()
                local.set(metadata)
                AutoCloseable { if (prior == null) local.remove() else local.set(prior) }
            }
        }
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
            val domain = runtime.prepare(policy, DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            unrelated.set("host-private")
            try {
                val held = runtime.submit(
                    domain,
                    Callable {
                    worker.set(Thread.currentThread())
                    assertTrue(worker.get() !== caller && !worker.get().isVirtual)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                local.set(mapOf("name" to "cancelled"))
                val cancelled = runtime.submit(domain, runtime.captureInvocation(domain, bridge, Callable { error("queued body entered") }))
                cancelled.requestCancellation()
                assertIs<ExecutionEnd.NotStarted>(cancelled.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val metadata = mutableMapOf("name" to "A")
                local.set(metadata)
                val queued = runtime.submit(
                    domain,
                    runtime.captureInvocation(
                        domain,
                        bridge,
                        Callable {
                    assertTrue(Thread.currentThread() === worker.get())
                    assertNull(unrelated.get())
                    assertEquals("A", local.get()["name"])
                    assertFailsWith<AccessDeniedException> { Files.readString(Path.of(file)) }
                    "A"
                },
                    ),
                )
                metadata["name"] = "changed"
                assertEquals(0, applies.get())
                release.countDown()
                held.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals("A", queued.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals(1, applies.get())
                assertNull(Mazewall.callContained(runtime, domain, Callable { local.get() }))
                local.set(mapOf("name" to "B"))
                assertEquals(
                    "B",
                    Mazewall.callContained(
                        runtime,
                        domain,
                        runtime.captureInvocation(
                            domain,
                            bridge,
                            Callable {
                    val active = Thread.currentThread()
                    assertTrue(active === worker.get())
                    local.set(mapOf("name" to "inner"))
                    val inner = runtime.captureInvocation(
                        domain,
                        bridge,
                        Callable {
                        assertTrue(Thread.currentThread() === active)
                        local.get()["name"]
                    },
                    )
                    local.set(mapOf("name" to "B"))
                    assertEquals("inner", Mazewall.callContained(runtime, domain, inner))
                    assertEquals("B", local.get()["name"])
                    "B"
                },
                        ),
                    ),
                )
                assertNull(Mazewall.callContained(runtime, domain, Callable { local.get() }))
                val fatal = LinkageError("native context body fatal")
                val later = IllegalArgumentException("native later outer failure")
                val cleanup = LinkageError("native later restoration fatal")
                val restored = java.util.concurrent.atomic
                    .AtomicInteger()
                val broken = InvocationContextBridge {
                    InvocationContextSnapshot(mapOf("name" to "fatal")) { metadata ->
                    val previous = local.get()
                    local.set(metadata)
                    AutoCloseable {
                        assertTrue(Thread.currentThread() === worker.get())
                        assertEquals("fatal", local.get()["name"])
                        if (previous == null) local.remove() else local.set(previous)
                        assertTrue(local.get() === previous)
                        restored.incrementAndGet()
                        throw cleanup
                    }
                }
                }
                val failed = runtime.submit(
                    domain,
                    runtime.captureInvocation(
                        domain,
                        broken,
                        Callable<Int> {
                    assertTrue(Thread.currentThread() === worker.get())
                    val previous = local.get()
                    local.set(mapOf("name" to "inner-fatal"))
                    val inner = runtime.captureInvocation(
                        domain,
                        bridge,
                        Callable<Int> {
                        assertTrue(Thread.currentThread() === worker.get())
                        assertEquals("inner-fatal", local.get()["name"])
                        throw fatal
                    },
                    )
                    local.set(previous)
                    assertTrue(assertFailsWith<LinkageError> { Mazewall.callContained(runtime, domain, inner) } === fatal)
                    assertTrue(local.get() === previous)
                    assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === fatal)
                    throw later
                },
                    ),
                )
                assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { failed.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
                assertTrue(assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
                assertEquals(listOf(cleanup), fatal.suppressed.toList())
                assertEquals(listOf(later), cleanup.suppressed.toList())
                assertEquals(1, restored.get())
                val reuseEntries = java.util.concurrent.atomic
                    .AtomicInteger()
                val rejected = kotlin.test.assertFails { runtime.submit(domain, Callable { reuseEntries.incrementAndGet() }) }
                // Prepared Landlock retirement can reject validation before the generation admission check.
                assertTrue(rejected is java.util.concurrent.RejectedExecutionException || rejected is IllegalStateException)
                assertEquals(0, reuseEntries.get())
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertFalse(worker.get().isAlive)
                assertEquals("host-only", Files.readString(Path.of(file)))
            } finally {
                release.countDown()
                local.remove()
                unrelated.remove()
            }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `explicit process baseline and every owned worker enforce before readiness`() {
        val file = Files.createTempFile("owned-runtime-private-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyOwnedRuntime", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyOwnedRuntime(file: String) {
        val timeout = Duration.ofSeconds(10)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
            val domain = runtime.prepare(policy, DomainLimits(2, 2))
            val entered = CountDownLatch(2)
            val handles = List(2) {
                runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(entered.await(5, TimeUnit.SECONDS))
                    assertFailsWith<AccessDeniedException> { Files.readString(Path.of(file)) }
                    42
                },
                )
            }
            handles.forEach {
                assertEquals(42, it.result().toCompletableFuture().get(10, TimeUnit.SECONDS))
                assertIs<ExecutionEnd.Finished>(it.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            }
            assertFailsWith<ContainmentViolationException> {
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertFailsWith<ContainmentViolationException> {
                        Mazewall.callContained(runtime, domain, Callable { Files.readString(Path.of(file)) })
                    }
                    42
                },
                )
            }
            val denied = LinuxNative.raw.syscall(Syscall.EXECVE.numberFor(Arch.current()).toLong(), NativeArg.LongArg(0L), NativeArg.LongArg(0L), NativeArg.LongArg(0L))
            assertEquals(1, assertIs<LinuxNative.SyscallResult.Error<*>>(denied).errno)
            assertEquals("caller-readable", Files.readString(Path.of(file)))
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
        }
    }
}
