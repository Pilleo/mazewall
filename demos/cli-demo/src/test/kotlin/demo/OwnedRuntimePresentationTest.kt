package demo

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationContextBridge
import io.mazewall.enforcer.api.execution.InvocationContextSnapshot
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("03 — Own the caller, context, admission and physical lifetime")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OwnedRuntimePresentationTest {
    @Test
    @Order(1)
    @DisplayName("03.1 — A virtual caller hands declared context to an owned platform worker")
    fun callerAndContext() = isolated("contextBody")

    @Test
    @Order(2)
    @DisplayName("03.2 — Full admission rejects work; cancellation still requires physical drain")
    fun admissionAndRetirement() = isolated("retirementBody")

    private fun isolated(body: String) {
        check(Platform.isSupported()) { "The owned runtime showcase requires native Linux support" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
    }

    @Suppress("unused")
    fun contextBody() {
        val declared = ThreadLocal<String>()
        val worker = AtomicReference<Thread>()
        val observed = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
            val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), DomainLimits(1, 1))
            val caller = Thread.ofVirtual().name("presentation-caller").start {
                try {
                    assertTrue(Thread.currentThread().isVirtual)
                    declared.set("request-42")
                    val contexts = InvocationContextBridge {
                        InvocationContextSnapshot(mapOf("request" to declared.get())) { metadata ->
                            val previous = declared.get()
                            declared.set(metadata.getValue("request"))
                            AutoCloseable { if (previous == null) declared.remove() else declared.set(previous) }
                        }
                    }
                    val captured = runtime.captureInvocation(domain, contexts, Callable {
                        worker.set(Thread.currentThread())
                        assertFalse(Thread.currentThread().isVirtual)
                        declared.get()
                    })
                    declared.set("caller-after-capture")
                    val handle = runtime.submit(domain, captured)
                    observed.set(OwnedDemoRuntime.await(handle))
                    assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    assertEquals("caller-after-capture", declared.get())
                } catch (caught: Throwable) {
                    failure.set(caught)
                } finally {
                    declared.remove()
                }
            }
            caller.join(10_000)
            assertFalse(caller.isAlive, "actual virtual caller must finish")
            failure.get()?.let { throw it }
            assertEquals("request-42", observed.get())
            assertEquals(null, OwnedDemoRuntime.await(runtime.submit(domain, Callable { declared.get() })))
            println("RESULT: virtual caller → platform worker; request-42 captured before caller mutation; worker context restored")
        }
        worker.get().join(5_000)
        assertFalse(worker.get().isAlive)
        println("CLEANUP: actual worker retired\nNEXT: bound how much work the domain accepts and retain it through cancellation.")
    }

    @Suppress("unused")
    fun retirementBody() {
        val entered = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val secondEntries = AtomicInteger()
        val worker = AtomicReference<Thread>()
        OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
            val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), DomainLimits(1, 1))
            try {
                val handle = runtime.submit(domain, Callable {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    try {
                        check(neverReleased.await(10, TimeUnit.SECONDS)) { "Cancellation never reached the running body" }
                    } finally {
                        Thread.interrupted()
                        cleaning.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Presenter did not release owned cleanup" }
                    }
                })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { secondEntries.incrementAndGet() }) }
                assertEquals(0, secondEntries.get())
                handle.requestCancellation()
                assertTrue(cleaning.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertTrue(worker.get().isAlive)
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(20)))
                println("RESULT: second admission rejected with zero body entry; caller cancelled; cleanup live; drain INCOMPLETE")
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(DrainReport.Complete, runtime.drain(Duration.ofSeconds(5)))
                worker.get().join(5_000)
                assertFalse(worker.get().isAlive)
                println("RESULT: cleanup released → executionEnd → drain COMPLETE → actual worker dead")
            } finally {
                release.countDown()
                neverReleased.countDown()
            }
        }
        println("NEXT: when a legitimate tool needs a dangerous syscall, authorize its exact call path; never erase the process baseline.")
    }
}
