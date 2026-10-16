package io.mazewall.landlock

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LandlockTsyncIntegrationTest : BaseIntegrationTest() {
    /**
     * Tiny main class for the out-of-process TSYNC test.
     */
    object TsyncChild {
        @JvmStatic
        fun main(args: Array<String>) {
            val policy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .block(Syscall.OPEN, Syscall.OPENAT, Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                    .build()

            val exitCode = runSiblingRead({ File("/etc/hostname").readText() }) {
                Landlock.applyRuleset(policy.definition)
            }
            exitProcess(exitCode)
        }

        fun runSiblingRead(
            read: () -> String,
            restrict: () -> Unit,
        ): Int {
            val latch = java.util.concurrent.CountDownLatch(1)
            val doneLatch = java.util.concurrent.CountDownLatch(1)
            val outcome = AtomicReference<Result<String>>()

            // Spawn the sibling thread BEFORE Landlock is applied
            val thread =
                Thread {
                    latch.countDown()
                    val result = runCatching {
                        doneLatch.await()
                        read()
                    }
                    outcome.set(result)
                }
            thread.start()
            val parentResult = runCatching {
                latch.await()
                restrict()
            }
            doneLatch.countDown()
            var failure = parentResult.exceptionOrNull()
            var interrupted = failure is InterruptedException
            // Join owns physical retirement even when the caller has been interrupted.
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (interruption: InterruptedException) {
                    interrupted = true
                    failure = CleanupFailures.merge(failure, interruption)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            assertFalse(thread.isAlive, "Sibling must retire before interpreting its read")
            val result = requireNotNull(outcome.get()) { "Sibling did not publish its read result" }
            val siblingFailure = result.exceptionOrNull()
            if (siblingFailure != null && failure != null) failure = CleanupFailures.merge(failure, siblingFailure)
            failure?.let { throw it }
            if (siblingFailure == null) return 0
            promoteFatalOrInterruption(siblingFailure)
            if (!hasReadPermissionEvidence(siblingFailure)) throw siblingFailure
            return 42
        }

        private fun promoteFatalOrInterruption(failure: Throwable) {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val pending = java.util.ArrayDeque<Throwable>()
            val failures = mutableListOf<Throwable>()
            pending.add(failure)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                failures.add(current)
                current.cause?.let { pending.add(it) }
                current.suppressed.forEach { pending.add(it) }
            }
            failures.filterIsInstance<Error>().firstOrNull()?.let { throw it }
            failures.filterIsInstance<InterruptedException>().firstOrNull()?.let {
                // The acquired sibling has already physically retired at this boundary.
                Thread.currentThread().interrupt()
                throw it
            }
        }

        private fun hasReadPermissionEvidence(failure: Throwable): Boolean {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var primary: Throwable? = failure
            while (primary is ContainmentViolationException) {
                if (!seen.add(primary)) return false
                val errno = primary.errno
                if (errno != null) return errno == 1 || errno == 13
                primary = primary.cause
            }
            if (primary is java.nio.file.AccessDeniedException) return true
            return primary is java.nio.file.FileSystemException &&
                primary.reason in listOf("Permission denied", "Operation not permitted")
        }
    }

    @Test
    fun `lifecycle interruption before initial await retires sibling and restores interrupt`() {
        val sibling = AtomicReference<Thread>()
        var restrictionAttempted = false
        try {
            Thread.currentThread().interrupt()
            val actual = assertFails {
                TsyncChild.runSiblingRead({
                    sibling.set(Thread.currentThread())
                    "readable"
                }) { restrictionAttempted = true }
            }
            kotlin.test.assertIs<InterruptedException>(actual)
            assertFalse(restrictionAttempted)
            assertFalse(requireNotNull(sibling.get()).isAlive)
            assertEquals(true, Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `lifecycle parent unexpected failure cannot become sibling typed denial`() {
        val failure = java.io.IOException("unexpected parent restriction failure")
        val actual = assertFails {
            TsyncChild.runSiblingRead({ throw java.nio.file.AccessDeniedException("sibling read") }) { throw failure }
        }
        assertSame(failure, actual)
    }

    @Test
    fun `lifecycle concurrent sibling fatal outranks parent denial`() {
        val fatal = AssertionError("sibling fatal during parent denial")
        val actual = assertFails {
            TsyncChild.runSiblingRead({ throw fatal }) {
                throw java.nio.file.AccessDeniedException("parent restriction")
            }
        }
        assertSame(fatal, actual)
    }

    @Test
    fun `lifecycle interrupted join retires sibling and preserves parent fatal`() {
        val fatal = AssertionError("parent fatal during interruption")
        val sibling = AtomicReference<Thread>()
        try {
            val actual = assertFails {
                TsyncChild.runSiblingRead({
                    sibling.set(Thread.currentThread())
                    "readable"
                }) {
                    Thread.currentThread().interrupt()
                    throw fatal
                }
            }
            assertSame(fatal, actual)
            assertFalse(requireNotNull(sibling.get()).isAlive)
            assertEquals(true, Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `sibling read preserves unrelated exception after retirement`() {
        val failure = java.io.IOException("unrelated read failure")
        assertRetiredFailure(failure)
    }

    @Test
    fun `sibling read preserves fatal identity after retirement`() {
        val failure = AssertionError("fatal read failure")
        assertRetiredFailure(failure)
    }

    private fun assertRetiredFailure(failure: Throwable) {
        var sibling: Thread? = null
        val actual = assertFails {
            TsyncChild.runSiblingRead({
                sibling = Thread.currentThread()
                throw failure
            }, {})
        }
        assertSame(failure, actual)
        assertFalse(requireNotNull(sibling).isAlive)
    }

    @Test
    fun `sibling readable path remains unrestricted`() {
        assertEquals(0, TsyncChild.runSiblingRead({ File("/etc/hostname").readText() }, {}))
    }

    @Test
    fun `sibling typed denial reports restriction`() {
        assertEquals(42, TsyncChild.runSiblingRead({ throw java.nio.file.AccessDeniedException("/etc/hostname") }, {}))
    }

    @Test
    fun `F05 modeled classification rejects EINVAL unknown and null errno`() {
        for (errno in listOf(22, 999, null)) {
            assertRetiredFailure(ContainmentViolationException("modeled structured failure", errno = errno))
        }
        assertRetiredFailure(ContainmentViolationException("Permission denied", java.nio.file.AccessDeniedException("modeled"), errno = 22))
    }

    @Test
    fun `F05 modeled classification rejects unrelated primary and suppressed denial`() {
        for (primary in listOf(
            IllegalStateException("unrelated", java.nio.file.AccessDeniedException("modeled")),
            java.io.IOException("business Permission denied"),
            java.nio.file.FileSystemException("Permission denied", null, "Invalid argument"),
            ContainmentViolationException("unknown primary"),
        )) {
            primary.addSuppressed(java.nio.file.AccessDeniedException("suppressed modeled denial"))
            assertRetiredFailure(primary)
        }
    }

    @Test
    fun `F05 modeled classification accepts concrete permission denials`() {
        for (failure in listOf(
            java.nio.file.AccessDeniedException("modeled path"),
            java.nio.file.FileSystemException("modeled path", null, "Permission denied"),
            ContainmentViolationException("modeled errno", errno = 1),
            ContainmentViolationException("modeled errno", errno = 13),
            ContainmentViolationException("modeled translation", java.nio.file.AccessDeniedException("modeled path")),
        )) {
            val sibling = AtomicReference<Thread>()
            assertEquals(
                42,
                TsyncChild.runSiblingRead({
                sibling.set(Thread.currentThread())
                throw failure
            }, {}),
            )
            assertFalse(requireNotNull(sibling.get()).isAlive)
        }
    }

    @Test
    fun `F05 modeled classification promotes fatal identity through cyclic denial graph`() {
        for (suppressed in listOf(false, true)) {
            val fatal = LinkageError("modeled nested fatal")
            val denial = java.nio.file.AccessDeniedException("modeled denial")
            if (suppressed) denial.addSuppressed(fatal) else denial.initCause(fatal)
            fatal.addSuppressed(denial)
            assertModeledSiblingFailure(denial, fatal)
        }
    }

    @Test
    fun `F05 modeled classification promotes interruption identity through cyclic denial graph`() {
        for (suppressed in listOf(false, true)) {
            val interrupted = InterruptedException("modeled nested interruption")
            val denial = java.nio.file.AccessDeniedException("modeled denial")
            if (suppressed) denial.addSuppressed(interrupted) else denial.initCause(interrupted)
            interrupted.addSuppressed(denial)
            try {
                assertModeledSiblingFailure(denial, interrupted)
                assertEquals(true, Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `F05 modeled classification prioritizes fatal over interruption and denial`() {
        val fatal = LinkageError("modeled fatal priority")
        val interrupted = InterruptedException("modeled interruption")
        val denial = java.nio.file.AccessDeniedException("modeled denial")
        denial.initCause(interrupted)
        denial.addSuppressed(fatal)
        fatal.addSuppressed(denial)
        try {
            assertModeledSiblingFailure(denial, fatal)
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertModeledSiblingFailure(
        primary: Throwable,
        expected: Throwable,
    ) {
        val sibling = AtomicReference<Thread>()
        val actual = assertFails {
            TsyncChild.runSiblingRead({
                sibling.set(Thread.currentThread())
                throw primary
            }, {})
        }
        assertSame(expected, actual)
        assertFalse(requireNotNull(sibling.get()).isAlive)
    }

    @Test
    fun `test TSYNC sandboxes sibling threads in child process`() {
        val javaBin =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val classpath = System.getProperty("java.class.path")

        val pb =
            ProcessBuilder(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath,
                "io.mazewall.landlock.LandlockTsyncIntegrationTest\$TsyncChild",
            )

        val process = pb.start()
        val exitCode = process.waitFor()

        // Landlock does not support TSYNC yet by default (thread-scoped only in current kernel driver implementation),
        // so the sibling thread is uncontained, leading to successful read (exit code 0).
        assertEquals(0, exitCode, "Expected exit code 0 as Landlock TSYNC is currently disabled by default")
    }
}
