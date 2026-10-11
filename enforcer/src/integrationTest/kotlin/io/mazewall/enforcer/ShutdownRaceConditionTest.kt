package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShutdownRaceConditionTest : BaseIntegrationTest() {
    fun testRegistrySyncOnInterruption() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.OPEN)
            .allowMmapExec()
            .build()
        val executor = Executors.newFixedThreadPool(1)

        val filterDepthAfterInterruption = AtomicInteger(-1)
        val error = AtomicReference<Throwable?>(null)
        val installed = CountDownLatch(1)
        val holdWorker = CountDownLatch(1)

        executor.execute {
            runContainmentWorker(installed, error, {
                ContainmentStateRegistry.threadState = ContainerState()
                ContainedExecutors.installOnCurrentThread(policy)
            }, {
                holdWorker.awaitOrFail("installed worker interrupted during shutdown")
            }, {
                filterDepthAfterInterruption.set(ContainmentStateRegistry.threadState.filterDepth)
            })
        }

        val parentFailure = runCatching {
            installed.awaitOrFail("thread filter installation completed")
        }.exceptionOrNull()
        retireWorker(executor, holdWorker, error, parentFailure)?.let { throw it }

        assertEquals(1, filterDepthAfterInterruption.get(), "Filter depth should be 1 even after interruption")
    }

    @Test
    fun `test registry sync on interruption`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testRegistrySyncOnInterruption")
    }

    private fun runContainmentWorker(
        installed: CountDownLatch,
        error: AtomicReference<Throwable?>,
        install: () -> Unit,
        awaitShutdown: () -> Unit,
        recordDepth: () -> Unit,
    ) {
        var installationCompleted = false
        val outcome = runCatching {
            install()
            installationCompleted = true
            installed.countDown()
            awaitShutdown()
        }
        val failure = outcome.exceptionOrNull()
        val expectedInterruption = installationCompleted && failure is InterruptedException
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        val primary = if (expectedInterruption) null else failure
        val cleanupFailure = runCatching { recordDepth() }.exceptionOrNull()
        error.set(if (cleanupFailure == null) primary else CleanupFailures.merge(primary, cleanupFailure))
    }

    private fun retireWorker(
        executor: ExecutorService,
        holdWorker: CountDownLatch,
        error: AtomicReference<Throwable?>,
        parentFailure: Throwable?,
    ): Throwable? {
        var failure = parentFailure
        var interrupted = parentFailure is InterruptedException
        val shutdownFailure = runCatching { executor.shutdownNow() }.exceptionOrNull()
        if (shutdownFailure != null) failure = CleanupFailures.merge(failure, shutdownFailure)
        holdWorker.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var retired = false
        while (!retired) {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            val retirement = runCatching { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS) }
            val retirementFailure = retirement.exceptionOrNull()
            if (retirementFailure == null) {
                retired = retirement.getOrThrow()
                if (!retired) {
                    failure = CleanupFailures.merge(failure, AssertionError("interrupted containment worker terminated"))
                    break
                }
            } else {
                failure = CleanupFailures.merge(failure, retirementFailure)
                if (retirementFailure is InterruptedException) {
                    interrupted = true
                } else {
                    break
                }
            }
        }
        // Worker-owned evidence is read only after physical retirement, not logical task completion.
        if (retired) error.get()?.let { failure = CleanupFailures.merge(failure, it) }
        if (interrupted) Thread.currentThread().interrupt()
        return failure
    }

    private fun observeWorker(
        installed: CountDownLatch = CountDownLatch(1),
        parentFailure: Throwable? = null,
        install: () -> Unit = {},
        awaitShutdown: () -> Unit = {},
        recordDepth: () -> Unit = {},
    ): Throwable? {
        val executor = Executors.newFixedThreadPool(1)
        val error = AtomicReference<Throwable?>(null)
        executor.execute { runContainmentWorker(installed, error, install, awaitShutdown, recordDepth) }
        val failure = retireWorker(executor, CountDownLatch(1), error, parentFailure)
        assertTrue(executor.isTerminated, "parent observes evidence only after physical worker retirement")
        return failure
    }

    @Test
    fun `failed installation retains original failure without success signal`() {
        val installed = CountDownLatch(1)
        val failure = IllegalStateException("installation failed")
        assertSame(failure, observeWorker(installed, install = { throw failure }))
        assertEquals(1, installed.count, "failed installation must not signal success")
    }

    @Test
    fun `interruption before installation is not expected shutdown`() {
        val installed = CountDownLatch(1)
        val failure = InterruptedException("installation interrupted")
        assertSame(failure, observeWorker(installed, install = { throw failure }))
        assertEquals(1, installed.count, "interrupted installation must not signal success")
    }

    @Test
    fun `unexpected wait failure reaches physically retired parent`() {
        val failure = IllegalStateException("unexpected wait failure")
        assertSame(failure, observeWorker(awaitShutdown = { throw failure }))
    }

    @Test
    fun `expected interruption after installation restores worker interrupt flag`() {
        val installed = CountDownLatch(1)
        val interrupted = AtomicReference(false)
        assertEquals(
            null,
            observeWorker(installed, awaitShutdown = {
            Thread.interrupted()
            throw InterruptedException("installed worker interrupted")
        }, recordDepth = { interrupted.set(Thread.currentThread().isInterrupted) }),
        )
        assertEquals(0, installed.count)
        assertTrue(interrupted.get(), "observed expected interruption is preserved on retiring worker")
    }

    @Test
    fun `fatal depth cleanup retains original worker failure`() {
        val original = IllegalStateException("worker wait failed")
        val fatal = LinkageError("depth cleanup failed")
        assertSame(fatal, observeWorker(awaitShutdown = { throw original }, recordDepth = { throw fatal }))
        assertTrue(fatal.suppressed.any { it === original }, "fatal cleanup retains original worker evidence")
    }

    @Test
    fun `fatal worker evidence outranks original parent failure after retirement`() {
        val original = IllegalStateException("parent wait failed")
        val fatal = LinkageError("worker failed")
        assertSame(fatal, observeWorker(parentFailure = original, awaitShutdown = { throw fatal }))
        assertTrue(fatal.suppressed.any { it === original }, "fatal worker retains original parent evidence")
    }

    @Test
    fun `original parent failure retains ordinary worker cleanup evidence`() {
        val original = AssertionError("parent wait failed")
        val cleanup = IllegalStateException("depth cleanup failed")
        assertSame(original, observeWorker(parentFailure = original, recordDepth = { throw cleanup }))
        assertTrue(original.suppressed.any { it === cleanup }, "parent retains actual worker cleanup evidence")
    }
}
