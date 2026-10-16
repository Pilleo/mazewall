package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DedicatedProcessBaselineTest {
    @Test
    fun `dedicated baseline restricts already running sibling and allows JDK read`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "verifyExistingSibling")
    }

    fun verifyExistingSibling() {
        val forbidden = Path.of("/etc/passwd")
        check(Files.readAllBytes(forbidden).isNotEmpty()) // Positive control before restriction.
        withExistingSibling(siblingBody = { proceed ->
            val read = runCatching {
                check(proceed.await(10, TimeUnit.SECONDS))
                Files.readAllBytes(forbidden)
            }
            val failure = read.exceptionOrNull()
            // Only typed filesystem denial proves the existing thread acquired Landlock.
            when {
                failure == null -> AssertionError("existing sibling escaped process Landlock")
                (failure as? AccessDeniedException) != null -> null
                else -> failure
            }?.let { throw it }
        }) {
            val receipt = ContainedExecutors.installOnDedicatedProcess(
                ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
            )
            check(receipt.processWide && receipt.installed && receipt.landlockApplied)
            check(Files.readAllBytes(Path.of(System.getProperty("java.home"), "release")).isNotEmpty())
        }
    }

    private fun withExistingSibling(
        siblingBody: (CountDownLatch) -> Unit,
        beforeReady: (Thread, CountDownLatch) -> Unit = { _, _ -> },
        onWaitInterrupted: (InterruptedException) -> Unit = {},
        ownerBody: () -> Unit,
    ) {
        val ready = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var failure: Throwable? = null
        var interrupted = false
        val sibling = Thread.ofPlatform().start {
            outcome.set(
                runCatching {
                beforeReady(Thread.currentThread(), proceed)
                ready.countDown()
                siblingBody(proceed)
            }.exceptionOrNull(),
            )
        }
        try {
            runCatching {
                check(ready.await(remaining(deadline), TimeUnit.NANOSECONDS))
                ownerBody()
            }.exceptionOrNull()?.let { caught ->
                if (caught is InterruptedException) interrupted = true
                failure = CleanupFailures.merge(failure, caught)
                if (caught is InterruptedException) onWaitInterrupted(caught)
            }
        } finally {
            proceed.countDown()
            while (sibling.isAlive && System.nanoTime() < deadline) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(sibling, remaining(deadline))
                } catch (caught: InterruptedException) {
                    interrupted = true
                    failure = CleanupFailures.merge(failure, caught)
                    onWaitInterrupted(caught)
                }
            }
            if (sibling.isAlive) {
                failure = CleanupFailures.merge(failure, IllegalStateException("owned sibling retirement exceeded original 10s budget"))
            } else {
                outcome.get()?.let { failure = CleanupFailures.merge(failure, it) }
            }
            if (Thread.interrupted()) {
                interrupted = true
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    @Test
    fun `ordinary owner failure retains late sibling ordinary failure`() {
        ownershipControl(IllegalStateException("owner body"), IllegalArgumentException("late sibling"))
    }

    @Test
    fun `ordinary owner failure promotes actual late sibling assertion error`() {
        ownershipControl(IllegalStateException("owner body"), AssertionError("late sibling"))
    }

    @Test
    fun `ordinary owner failure promotes actual late sibling linkage error`() {
        ownershipControl(IllegalStateException("owner body"), LinkageError("late sibling"))
    }

    @Test
    fun `successful owner consumes actual late sibling fatal`() {
        ownershipControl(null, LinkageError("late sibling"))
    }

    @Test
    fun `held sibling owner interruption defers restoration until physical retirement`() {
        ownershipControl(null, LinkageError("late sibling"), interruptOwner = true)
    }

    @Test
    fun `owner body failure concurrent interruption retains actual sibling fatal`() {
        ownershipControl(IllegalStateException("owner body"), AssertionError("late sibling"), interruptOwner = true)
    }

    @Test
    fun `held readiness interruption still releases and retires actual sibling fatal`() {
        ownershipControl(null, LinkageError("late sibling"), interruptOwner = true, holdReadiness = true)
    }

    @Test
    fun `ordinary owner cleanup evidence is not typed filesystem denial`() {
        ownershipControl(IllegalStateException("owner cleanup"), AccessDeniedException("/etc/passwd"))
    }

    @Test
    fun `owner fatal retains late sibling ordinary failure`() {
        ownershipControl(AssertionError("owner body"), IllegalArgumentException("late sibling"))
    }

    @Test
    fun `first owner fatal retains actual concurrent sibling fatal`() {
        ownershipControl(LinkageError("owner body"), AssertionError("late sibling"))
    }

    private fun ownershipControl(
        bodyFailure: Throwable?,
        siblingFailure: Throwable,
        interruptOwner: Boolean = false,
        holdReadiness: Boolean = false,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val release = CountDownLatch(1)
        val siblingHeld = CountDownLatch(1)
        val ownerFinished = CountDownLatch(1)
        val waitCaught = CountDownLatch(1)
        val actualWait = AtomicReference<InterruptedException?>()
        val restoredWhileHeld = AtomicReference(false)
        val siblingAliveAtWaitCatch = AtomicReference(false)
        val siblingRef = AtomicReference<Thread?>()
        val proceedRef = AtomicReference<CountDownLatch?>()
        val actual = AtomicReference<Throwable?>()
        val restored = AtomicReference(false)
        val retiredAtReturn = AtomicReference(false)
        val proceedObserved = AtomicReference(false)
        val owner = Thread.ofPlatform().unstarted {
            try {
                actual.set(
                    runCatching {
                        withExistingSibling(
                            siblingBody = { proceed ->
                                if (!holdReadiness) siblingHeld.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                proceedObserved.set(proceed.await(10, TimeUnit.SECONDS))
                                throw siblingFailure
                            },
                            beforeReady = { sibling, proceed ->
                                siblingRef.set(sibling)
                                proceedRef.set(proceed)
                                if (holdReadiness) {
                                    siblingHeld.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                            },
                            onWaitInterrupted = { caught ->
                                actualWait.set(caught)
                                restoredWhileHeld.set(Thread.currentThread().isInterrupted)
                                siblingAliveAtWaitCatch.set(siblingRef.get()?.isAlive == true)
                                waitCaught.countDown()
                            },
                            ownerBody = { bodyFailure?.let { throw it } },
                        )
                    }.exceptionOrNull(),
                )
            } finally {
                retiredAtReturn.set(siblingRef.get()?.isAlive == false)
                restored.set(Thread.currentThread().isInterrupted)
                ownerFinished.countDown()
            }
        }
        var failure: Throwable? = null
        try {
            failure = runCatching {
                owner.start()
                assertTrue(siblingHeld.await(remaining(deadline), TimeUnit.NANOSECONDS), "actual sibling entered held boundary")
                if (!holdReadiness) {
                    assertTrue(checkNotNull(proceedRef.get()).await(remaining(deadline), TimeUnit.NANOSECONDS), "owner body reached cleanup")
                }
                if (interruptOwner) {
                    owner.interrupt()
                    assertTrue(waitCaught.await(remaining(deadline), TimeUnit.NANOSECONDS), "actual interrupted wait caught before sibling release")
                    assertTrue(siblingAliveAtWaitCatch.get(), "actual sibling alive at wait catch")
                    assertTrue(checkNotNull(siblingRef.get()).isAlive, "actual sibling remains held after wait catch")
                    assertFalse(restoredWhileHeld.get(), "owner flag not restored at wait catch")
                    assertFalse(owner.isInterrupted, "owner flag remains deferred while sibling held")
                }
                assertFalse(ownerFinished.await(100, TimeUnit.MILLISECONDS), "owner must not escape its held sibling")
                release.countDown()
                owner.join(TimeUnit.NANOSECONDS.toMillis(remaining(deadline)).coerceAtLeast(1))
                assertFalse(owner.isAlive, "owner physically retired")
                assertTrue(retiredAtReturn.get(), "sibling physically retired before owner returned")
                assertFalse(checkNotNull(siblingRef.get()).isAlive, "actual sibling physically retired")
                assertTrue(proceedObserved.get(), "owner released sibling on this exit")
                val expected = when {
                    bodyFailure is Error -> bodyFailure
                    siblingFailure is Error || bodyFailure == null -> siblingFailure
                    else -> bodyFailure
                }
                assertSame(expected, actual.get(), "actual fatal-first identity")
                if (bodyFailure != null) {
                    val secondary = if (expected === siblingFailure) bodyFailure else siblingFailure
                    assertTrue(expected.suppressed.any { it === secondary }, "actual secondary identity retained")
                }
                if (interruptOwner) {
                    assertTrue(restored.get(), "owner interruption restored after join and outcome consumption")
                    val caught = actualWait.get()
                    assertTrue(caught != null, "actual thrown wait identity observed")
                    assertTrue(failureReachable(expected, checkNotNull(caught)), "actual thrown wait identity retained through cause or suppressed")
                }
            }.exceptionOrNull()
        } finally {
            release.countDown()
            proceedRef.get()?.countDown()
            // The control owns both real threads even when the RED boundary escapes early.
            failure = retireControlThreads(listOfNotNull(owner, siblingRef.get()), deadline, failure)
        }
        failure?.let { throw it }
    }

    private fun retireControlThreads(
        threads: List<Thread>,
        deadline: Long,
        primary: Throwable?,
    ): Throwable? {
        var failure = primary
        var interrupted = false
        try {
            for (thread in threads) {
                while (thread.isAlive && System.nanoTime() < deadline) {
                    try {
                        thread.join(TimeUnit.NANOSECONDS.toMillis(remaining(deadline)).coerceAtLeast(1))
                    } catch (caught: InterruptedException) {
                        interrupted = true
                        failure = CleanupFailures.merge(failure, caught)
                    }
                }
                if (thread.isAlive) {
                    failure = CleanupFailures.merge(failure, AssertionError("control could not physically retire owned thread"))
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return failure
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(1)

    private fun failureReachable(
        root: Throwable,
        target: Throwable,
    ): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = mutableListOf(root)
        while (pending.isNotEmpty()) {
            val next = pending.removeAt(pending.lastIndex)
            if (next === target) return true
            if (!seen.add(next)) continue
            next.cause?.let { pending.add(it) }
            pending.addAll(next.suppressed)
        }
        return false
    }
}
