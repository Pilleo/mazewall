package demo.vulnapp.config

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedServiceOperationTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `required resources fail before entry while prepared reads preserve real denial cause`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "resourceBody")
    }

    @Suppress("unused")
    fun resourceBody() {
        val root = Files.createTempDirectory("owned-web-resource-")
        val allowed = Files.createDirectory(root.resolve("allowed"))
        val configuration = Files.writeString(allowed.resolve("configuration"), "reviewed")
        val secret = Files.writeString(root.resolve("sibling"), "outside contract")
        val missing = root.resolve("missing")
        val owner = ServiceExecutorOwner(Duration.ofSeconds(5))
        var entries = 0
        try {
            val missingFailure = assertFailsWith<NoSuchFileException> {
                owner
                    .prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(missing.toString()).build())
                    .call {
                        entries++
                        "must not enter"
                    }
            }
            assertEquals(missing.toString(), missingFailure.file)
            assertEquals(0, entries)
            val operation = owner.prepare(
                WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowFsRead(allowed.toString())
                    .allowJvmClasspath()
                    .build(),
            )
            assertEquals("reviewed", operation.call { Files.readString(configuration) })
            val failure = assertFailsWith<ContainmentViolationException> { operation.call { Files.readString(secret) } }
            val nativeCause = assertIs<AccessDeniedException>(failure.cause)
            assertEquals(secret.toString(), nativeCause.file)
            assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(failure)?.evidence)
            assertEquals("outside contract", Files.readString(secret), "host remains outside the worker filesystem boundary")
        } finally {
            assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `caller timeout and cancellation retain physical execution and drain owner`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "retainedBody")
    }

    @Suppress("unused")
    fun retainedBody() {
        val owner = ServiceExecutorOwner(Duration.ofMillis(40))
        val operation = owner.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val invocation = operation.submit(
            Callable {
                worker.set(Thread.currentThread())
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Physical completion must await the explicit release.
                    }
                }
                "finished"
            },
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { invocation.result().toCompletableFuture().get(20, TimeUnit.MILLISECONDS) }
            invocation.requestCancellation()
            assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
            assertIs<ExecutorDrain.Incomplete.Runtime>(owner.stopAdmissionAndDrain())
            assertFailsWith<IllegalStateException> { owner.close() }
            assertTrue(worker.get().isAlive)
            assertFailsWith<RejectedExecutionException> { operation.call { "late" } }
            release.countDown()
            assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            worker.get().join(5_000)
            assertFalse(worker.get().isAlive)
            assertTrue(ServiceExecutorOwner.drainRetained().isEmpty())
            assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
        } finally {
            release.countDown()
            worker.get()?.join(5_000)
            owner.stopAdmissionAndDrain()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `service delivery preserves original business and fatal identity`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "identityBody")
    }

    @Suppress("unused")
    fun identityBody() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(5))
        try {
            val operation = owner.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
            assertEquals("materialized", operation.call { "materialized" })
            val failure = java.io.IOException("business failure")
            assertSame(failure, assertFailsWith<java.io.IOException> { operation.call { throw failure } })
            val fatal = LinkageError("fatal worker")
            assertSame(fatal, assertFailsWith<LinkageError> { operation.call { throw fatal } })
            assertFailsWith<RejectedExecutionException> { operation.call { "generation must stay failed" } }
        } finally {
            assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
        }
    }
}
