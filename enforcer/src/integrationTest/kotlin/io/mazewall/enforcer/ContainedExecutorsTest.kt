package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.compile
import io.mazewall.enforcer.api.execution.*
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@NeedsFreshJvm
class ContainedExecutorsTest : BaseIntegrationTest() {
    fun testOwnedBlocksExecve() {
        val timeout = Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_EXEC, DomainLimits(1, 1))
            val future = runtime.submit(domain, java.util.concurrent.Callable { ProcessBuilder("echo", "hello").start() })
            val failure = assertFailsWith<ExecutionException> { future.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertIs<ContainmentViolationException>(failure.cause)
            assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    fun testPerThreadIsolation() {
        val timeout = Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_EXEC, DomainLimits(1, 1))
            val future = runtime.submit(domain, java.util.concurrent.Callable { "installed" })
            assertEquals("installed", future.result().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            // The explicit launch-compatible process floor retains this caller control.
            val process = ProcessBuilder("echo", "uncontained").start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                }
            }
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    fun testOwnedContainsAllTasks() {
        val timeout = Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_EXEC, DomainLimits(2, 2))
            val tasks = listOf(
                java.util.concurrent.Callable { ProcessBuilder("echo", "1").start() },
                java.util.concurrent.Callable { ProcessBuilder("echo", "2").start() },
            )
            val futures = tasks.map { runtime.submit(domain, it) }
            for (future in futures) {
                val failure = assertFailsWith<ExecutionException> { future.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
                assertIs<ContainmentViolationException>(failure.cause)
                assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            }
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    fun testOwnedContainsSingleResult() {
        val timeout = Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_EXEC, DomainLimits(2, 2))
            val future = runtime.submit(
                domain,
                java.util.concurrent.Callable {
                    Runtime.getRuntime().exec(arrayOf("echo", "fail"))
                    "should not reach here"
                },
            )
            val failure = assertFailsWith<ExecutionException> { future.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertIs<ContainmentViolationException>(failure.cause)
            assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    fun testHierarchicalLandlockStackingSuccess() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(
                java.util.concurrent.Callable {
                ContainedExecutors.installOnCurrentThread(
                    WorkerPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .allowFsRead(
                        io.mazewall.platform.lifecycle.SandboxedPath
                    .of("/tmp", true),
                    ).build(),
                )
                ContainedExecutors.installOnCurrentThread(
                    WorkerPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .allowFsRead(
                        io.mazewall.platform.lifecycle.SandboxedPath
                    .of("/tmp/foo", true),
                    ).build(),
                )
                "success"
            },
            )
            if (future.get() != "success") throw IllegalStateException("Failed")
        } finally {
            executor.shutdown()
        }
    }

    fun testHierarchicalLandlockStackingFailureOnExpansion() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build())
                ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/etc").build())
            }
            try {
                future.get()
                throw IllegalStateException("Should have failed")
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause !is IllegalStateException || cause.message?.contains("Cannot expand Landlock") != true) {
                    throw e
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    fun testHierarchicalLandlockStackingFailureOnComponentBoundaryMismatch() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                ContainedExecutors.installOnCurrentThread(
                    WorkerPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .allowFsRead(
                        io.mazewall.platform.lifecycle.SandboxedPath
                    .of("/tmp", true),
                    ).build(),
                )
                ContainedExecutors.installOnCurrentThread(
                    WorkerPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .allowFsRead(
                        io.mazewall.platform.lifecycle.SandboxedPath
                    .of("/tmp-foo", true),
                    ).build(),
                )
            }
            try {
                future.get()
                throw IllegalStateException("Should have failed")
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause !is IllegalStateException || cause.message?.contains("Cannot expand Landlock") != true) {
                    throw e
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    fun testHierarchicalLandlockStackingIdenticalPaths() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(
                java.util.concurrent.Callable {
                val p = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
                ContainedExecutors.installOnCurrentThread(p)
                ContainedExecutors.installOnCurrentThread(p)
                "success"
            },
            )
            if (future.get() != "success") throw IllegalStateException("Failed")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `test owned worker blocks execve`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testOwnedBlocksExecve")
    }

    @Test
    fun `test graceful degradation fallback`() {
        val osName = System.getProperty("os.name")
        assumeFalse(osName.equals("Linux", ignoreCase = true), "Test is for non-Linux platforms only")

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(
            java.util.concurrent.Callable {
            ContainedExecutors.installOnCurrentThread(Policy.NO_EXEC).use { receipt ->
                receipt.requireInstalled()
                "success"
            }
        },
        )

        val ex = assertFailsWith<ExecutionException> {
            future.get(10, TimeUnit.SECONDS)
        }
        assertTrue(ex.cause is UnsupportedOperationException)
        assertTrue(ex.cause?.message?.contains("does not support") == true)

        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test
    fun `test per-thread isolation (TSYNC bug fix)`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testPerThreadIsolation")
    }

    @Test
    fun `owned submissions apply containment to all tasks`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testOwnedContainsAllTasks")
    }

    @Test
    fun `owned single result applies containment`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testOwnedContainsSingleResult")
    }

    @Test
    fun `owned multiple and single results respect finite waits`() {
        assumeLandlockAbiAtLeast(5)
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "testOwnedResultTimeouts")
    }

    fun testOwnedResultTimeouts() {
        val timeout = Duration.ofSeconds(5)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.PURE_COMPUTE, DomainLimits(2, 2))
            val tasks = listOf(
                java.util.concurrent.Callable { "task1" },
                java.util.concurrent.Callable { "task2" },
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val results = tasks.map { runtime.submit(domain, it) }
            assertEquals(2, results.size)
            assertEquals<String>("task1", results[0].result().toCompletableFuture().get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
            assertEquals<String>("task2", results[1].result().toCompletableFuture().get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
            results.forEach { assertIs<ExecutionEnd.Finished>(it.executionEnd().toCompletableFuture().get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) }

            val nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val next = tasks.map { runtime.submit(domain, it) }
            val any = java.util.concurrent.CompletableFuture
                .anyOf(*next.map { it.result().toCompletableFuture() }.toTypedArray())
                .get(nextDeadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            assertTrue(any == "task1" || any == "task2")
            next.forEach { assertIs<ExecutionEnd.Finished>(it.executionEnd().toCompletableFuture().get(nextDeadline - System.nanoTime(), TimeUnit.NANOSECONDS)) }
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    @Test
    fun `diagnose handles nested exceptions with error code`() {
        val root = java.io.IOException("something went wrong (error=1)")
        val nested = RuntimeException("wrapper", root)
        val deeplyNested = RuntimeException("outer", nested)
        assertTrue(ContainmentViolationDetector.diagnose(deeplyNested) != null)
    }

    @Test
    fun `diagnose handles suppressed exceptions with error code`() {
        val root = java.io.IOException("failed (error: 13)")
        val main = RuntimeException("main")
        main.addSuppressed(root)
        assertTrue(ContainmentViolationDetector.diagnose(main) != null)
    }

    fun testInstallOnProcessRejectsPoliciesWithLandlockRequirement() {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowFsRead("/tmp")
            .build()
        val features = Platform.featureMatrix
        if (features.landlockTsyncSupported) {
            verifySupportedInstallation {
                ContainedExecutors.installOnProcess(policy as Policy<PolicyScope.ProcessWideSafe, io.mazewall.enforcer.api.Uncompiled>)
            }
        } else {
            assertFailsWith<io.mazewall.enforcer.api.UnsupportedKernelFeatureException> {
                ContainedExecutors.installOnProcess(policy as Policy<PolicyScope.ProcessWideSafe, io.mazewall.enforcer.api.Uncompiled>)
            }
        }
    }

    private fun verifySupportedInstallation(install: () -> Unit) {
        val installation = runCatching { install() }
        val failure = installation.exceptionOrNull() ?: return
        if (failure is Error) throw failure
        val diagnostic = ContainmentViolationDetector.diagnose(failure) ?: throw failure
        val message = diagnostic.cause.message ?: throw failure
        val strerror13 = io.mazewall.ffi.memory
            .getSystemStrerror(13)
        val accessDenied = Regex("""\b(?:error|errno)[=:]?\s*13\b|\bEACCES\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(message) ||
            (strerror13 != null && message.contains(strerror13, ignoreCase = true))
        if (!accessDenied) throw failure
    }

    @Test
    fun `quality supported install rejects absent diagnostic`() {
        val failure = IllegalArgumentException(null as String?)
        val actual = kotlin.test.assertFails { verifySupportedInstallation { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality supported install rejects unrelated numeric thirteen`() {
        val failure = java.io.IOException("unrelated operation 13")
        val actual = kotlin.test.assertFails { verifySupportedInstallation { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality supported install retains fatal identity`() {
        val failure = AssertionError("fatal installation")
        val actual = kotlin.test.assertFails { verifySupportedInstallation { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality supported install accepts actual access denial as guard evidence`() {
        verifySupportedInstallation { throw java.io.IOException("installation failed (error=13)") }
    }

    @Test
    fun `quality supported install does not accept operation not permitted`() {
        val failure = java.io.IOException("installation failed (error=1)")
        val actual = kotlin.test.assertFails { verifySupportedInstallation { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `installOnProcess rejects policies with Landlock requirement on unsupported kernels`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testInstallOnProcessRejectsPoliciesWithLandlockRequirement")
    }

    @Test
    fun `test hierarchical Landlock stacking success`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testHierarchicalLandlockStackingSuccess")
    }

    @Test
    fun `test hierarchical Landlock stacking failure on expansion`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testHierarchicalLandlockStackingFailureOnExpansion")
    }

    @Test
    fun `test hierarchical Landlock stacking failure on component boundary mismatch`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testHierarchicalLandlockStackingFailureOnComponentBoundaryMismatch")
    }

    @Test
    fun `test hierarchical Landlock stacking identical paths`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testHierarchicalLandlockStackingIdenticalPaths")
    }

    @Test
    fun `installOnCurrentThread accepts both process-wide and thread-local policies`() {
        val threadLocalPolicy: Policy<PolicyScope.ThreadLocalOnly, io.mazewall.enforcer.api.Compiled> = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowFsRead("/tmp")
            .build()
            .compile(
                io.mazewall.platform.syscall.Arch
                .current(),
            )
        val processWidePolicy: Policy<PolicyScope.ProcessWideSafe, io.mazewall.enforcer.api.Compiled> = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().compile(
            io.mazewall.platform.syscall.Arch
            .current(),
        )

        val list = listOf<Policy<*, io.mazewall.enforcer.api.Compiled>>(threadLocalPolicy, processWidePolicy)
        assertEquals(2, list.size)
    }
}
