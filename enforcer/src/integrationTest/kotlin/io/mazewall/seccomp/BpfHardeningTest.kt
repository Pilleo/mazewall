package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@NeedsFreshJvm
class BpfHardeningTest : BaseIntegrationTest() {
    @Test
    fun `test that prctl can be blocked when inspection is explicitly disabled`() {
        if (!disposableChild) {
            runOwnedJvm("prctl")?.let { throw it }
            return
        }
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowUnsafePrctl() // Disable argument inspection (previously would cause PRCTL to be skipped in block list)
                .block(Syscall.PRCTL) // Explicitly block it
                .build()

        val result = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        runFixture {
                    ContainedExecutors.installOnCurrentThread(policy)
                    // PR_SET_NAME = 15
                    val res =
                    LinuxNative.process.prctl(
                        PrctlCommand.SetName(NativeArg.MemoryArg(ManagedSegment.NULL)),
                    )

                    result.set(res)
        }

        val res = result.get()
        assertTrue(res is LinuxNative.SyscallResult.Error && res.errno == 1, "PRCTL should have been blocked with EPERM, got $res")
    }

    @Test
    fun `test that mmap with PROT_EXEC is blocked by default and standard mmap is preserved`() {
        if (!disposableChild) {
            runOwnedJvm("mmap")?.let { throw it }
            return
        }
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.MMAP) // Even if we block MMAP, critical bypass allows it for non-exec
                .build()

        val execResult = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        val readWriteResult = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        runFixture {
                    ContainedExecutors.installOnCurrentThread(policy)

                    // 1. Calling mmap with PROT_EXEC (7) should be blocked (EPERM)
                    execResult.set(
                        fixtureMapping(7),
                    )

                    // 2. Calling mmap with PROT_READ | PROT_WRITE (3) should succeed because MMAP is critical
                    readWriteResult.set(
                        fixtureMapping(3),
                    )
        }

        val execRes = execResult.get()
        assertTrue(execRes is LinuxNative.SyscallResult.Error && execRes.errno == 1, "MMAP with PROT_EXEC should have been blocked with EPERM, got $execRes")

        val rwRes = readWriteResult.get()
        assertTrue(rwRes is LinuxNative.SyscallResult.Success, "Standard MMAP (non-exec) should succeed as it is JVM-critical, got $rwRes")
    }

    // Contained workers run only in a JVM owned by the external process waiter.
    // Local held-worker controls are uncontained and released in their finally blocks.
    private fun runFixture(
        retirementMillis: Long = 2000,
        operation: () -> Unit,
    ) {
        val error = AtomicReference<Throwable>()
        val worker = Thread.ofPlatform().unstarted {
            error.set(runCatching(operation).exceptionOrNull())
        }
        val deadline = minOf(
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retirementMillis),
            childDeadline - TimeUnit.SECONDS.toNanos(2),
        )
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        try {
            worker.start()
            while (worker.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    failure = IllegalStateException("Incomplete worker retirement: platform thread is still alive")
                    break
                }
                try {
                    TimeUnit.NANOSECONDS.timedJoin(worker, remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            error.get()?.let { failure = CleanupFailures.merge(failure, it) }
        } finally {
            interrupted = Thread.interrupted() || interrupted
            if (interrupted) Thread.currentThread().interrupt()
        }
        // On child failure main exits nonzero; the external owner still retains and
        // reaps the entire JVM. No live contained worker can reach the parent JVM.
        failure?.let { throw it }
    }

    private fun fixtureMapping(
        protection: Int,
        use: (LinuxNative.SyscallResult<Long, *>) -> Unit = {},
        cleanup: (NativeMapping) -> Unit = { it.close() },
    ): LinuxNative.SyscallResult<Long, *> {
        val native = LinuxNative.snapshot()
        val result = native.fileSystem.mmap(0, 4096, MmapProt(protection), MmapFlags(0x22), FileDescriptor.ANON, 0)
        val mapping = if (result is LinuxNative.SyscallResult.Success) NativeMapping(native.raw, result.value, 4096) else null
        var failure: Throwable? = null
        try {
            failure = runCatching { use(result) }.exceptionOrNull()
        } finally {
            if (mapping != null) {
                runCatching { cleanup(mapping) }.exceptionOrNull()?.let { cleanupFailure ->
                    failure = CleanupFailures.merge(failure, cleanupFailure)
                }
            }
        }
        failure?.let { throw it }
        return result
    }

    private fun unmapOwned(address: Long) {
        NativeMapping(LinuxNative.raw, address, 4096).close()
    }

    @Test
    fun `quality F12 owner interrupt waits for physical end and restores interrupt`() {
        interruptControl(null)
    }

    @Test
    fun `quality F12 owner interrupt selects late actual child fatal`() {
        interruptControl(LinkageError("late child fatal"))
    }

    @Test
    fun `quality F12 owner interrupt selects late actual child error`() {
        interruptControl(AssertionError("late child error"))
    }

    @Test
    fun `quality F12 held worker reports explicit incomplete retirement`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownerEnded = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val outcome = AtomicReference<Throwable>()
        val owner = Thread.ofPlatform().unstarted {
            try {
                outcome.set(
                    runCatching {
                        runFixture(retirementMillis = 100) {
                            worker.set(Thread.currentThread())
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }.exceptionOrNull(),
                )
            } finally {
                ownerEnded.countDown()
            }
        }
        owner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(ownerEnded.await(500, TimeUnit.MILLISECONDS), "Missing bounded incomplete-retirement report")
            assertTrue(worker.get().isAlive, "Control must hold a physically live worker")
            val failure = outcome.get()
            assertTrue(
                failure is IllegalStateException && failure.message.orEmpty().contains("retirement"),
                "Expected explicit incomplete-retirement failure, got $failure",
            )
        } finally {
            release.countDown()
            worker.get()?.let {
                it.join(2000)
                assertFalse(it.isAlive, "Held control worker did not physically end")
            }
            owner.join(2000)
            assertFalse(owner.isAlive)
        }
    }

    private fun interruptControl(childFailure: Throwable?) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val ownerEnded = CountDownLatch(1)
        val outcome = AtomicReference<Throwable>()
        val worker = AtomicReference<Thread>()
        val restored = AtomicReference(false)
        val owner = Thread.ofPlatform().unstarted {
            try {
                outcome.set(
                    runCatching {
                        runFixture {
                            worker.set(Thread.currentThread())
                            entered.countDown()
                            try {
                                check(release.await(5, TimeUnit.SECONDS)) { "Control release timed out" }
                                childFailure?.let { throw it }
                            } finally {
                                ended.countDown()
                            }
                        }
                    }.exceptionOrNull(),
                )
            } finally {
                restored.set(Thread.currentThread().isInterrupted)
                ownerEnded.countDown()
            }
        }
        owner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.interrupt()
            assertFalse(ownerEnded.await(100, TimeUnit.MILLISECONDS), "Owner escaped a physically live worker")
        } finally {
            release.countDown()
            assertTrue(ended.await(2, TimeUnit.SECONDS), "Control worker did not physically finish")
            worker.get()?.let {
                it.join(2000)
                assertFalse(it.isAlive, "Control worker remained physically alive")
            }
            owner.join(2000)
            assertFalse(owner.isAlive, "Control owner did not physically finish")
        }
        assertTrue(restored.get(), "Owner interrupt must be deferred and restored")
        assertSame(childFailure, outcome.get(), "Select the actual child outcome, not the owner interruption")
    }

    @Test
    fun `quality F12 successful ordinary mapping is owned and cleaned`() {
        mappingControl(3, null, null)
    }

    @Test
    fun `quality F12 unexpected executable success mapping is owned and cleaned`() {
        // No filter here: obtain a real executable success at the same mmap boundary.
        mappingControl(7, null, null)
    }

    @Test
    fun `quality F12 mapping cleanup runs after ordinary primary failure`() {
        mappingControl(3, IllegalStateException("primary"), null)
    }

    @Test
    fun `quality F12 mapping cleanup runs after actual fatal primary`() {
        mappingControl(3, LinkageError("primary fatal"), null)
    }

    @Test
    fun `quality F12 ordinary primary selects actual fatal cleanup`() {
        mappingControl(3, IllegalStateException("primary"), LinkageError("cleanup fatal"))
    }

    @Test
    fun `quality F12 successful mapping publishes ordinary cleanup failure`() {
        mappingControl(3, null, IllegalStateException("cleanup"))
    }

    @Test
    fun `quality F12 successful mapping publishes actual fatal cleanup`() {
        mappingControl(3, null, LinkageError("cleanup fatal"))
    }

    @Test
    fun `quality F12 actual fatal primary retains ordinary cleanup evidence`() {
        mappingControl(3, LinkageError("primary fatal"), IllegalStateException("cleanup"))
    }

    private fun mappingControl(
        protection: Int,
        primary: Throwable?,
        cleanupFailure: Throwable?,
    ) {
        val owned = AtomicReference<Long>()
        val closed = AtomicReference(false)
        var actual: Throwable? = null
        try {
            actual = runCatching {
                runFixture {
                    fixtureMapping(
                        protection,
                        use = { result ->
                            assertTrue(result is LinuxNative.SyscallResult.Success, "Control needs actual mmap success: $result")
                            owned.set(result.value)
                            primary?.let { throw it }
                        },
                        cleanup = { mapping ->
                            assertEquals(owned.get(), mapping.address, "Only the actual successful address is owned")
                            mapping.close()
                            closed.set(true)
                            cleanupFailure?.let { throw it }
                        },
                    )
                }
            }.exceptionOrNull()
        } finally {
            // Even a failing control must not leak its successful native mapping.
            owned.get()?.let { if (!closed.get()) unmapOwned(it) }
        }
        assertTrue(closed.get(), "Native fixture failed to clean its actual successful mapping")
        val expected = if (cleanupFailure is Error && primary !is Error) cleanupFailure else primary ?: cleanupFailure
        assertSame(expected, actual, "Actual fatal failures must outrank ordinary failures")
        if (primary != null && cleanupFailure != null) {
            val secondary = if (expected === primary) cleanupFailure else primary
            assertTrue(actual?.suppressed?.any { it === secondary } == true, "Selection must retain the actual secondary failure")
        }
    }

    @Test
    fun `quality F12 genuinely stuck contained worker JVM is terminated and reaped`() {
        val failure = runOwnedJvm("stuck")
        assertTrue(
            failure is IllegalStateException && failure.message.orEmpty().contains("exceeded"),
            "A genuine contained-worker timeout must fail after actual process retirement, got $failure",
        )
        assertTrue(failure.suppressed.isEmpty(), "Stuck JVM retirement must not hide cleanup failure")
    }

    private fun runOwnedJvm(mode: String): Throwable? {
        // Stay inside the existing enclosing 120s budget, leaving 5s for test
        // dispatch overhead. This 115s deadline includes launch and cleanup;
        // the last 5s reserve covers two 2s reaps and stdout-pump retirement.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(115)
        val reserve = TimeUnit.SECONDS.toNanos(5)
        val spec = JvmChildSpec(
            mainClass = BpfHardeningTest::class.java.name,
            mainArgs = listOf(mode, "105000"),
            maxHeap = "128m",
            extraJvmArgs = listOf("-Djdk.lang.Process.launchMechanism=FORK"),
            javaAgents = JavaAgentSelection.None,
        )
        val command = JvmChildProcess.commandLine(spec)
        val lifecycle = OwnedJvmLifecycle()
        var pump: ChildStdoutPump? = null
        var verificationStarted = false
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        // Ownership is acquired before the child can install any containment.
        val process = runCatching {
            JvmChildProcess.start(RealProcessLauncher, spec)
        }.getOrElse { launchFailure ->
            if (interrupted) Thread.currentThread().interrupt()
            throw launchFailure
        }
        try {
            runCatching {
                val stdoutPump = JvmChildProcess.startStdoutPump(
                    process,
                    "F12_CONTAINED_WORKER_HELD",
                    { line -> println(line) },
                    "f12-owned-jvm-output",
                )
                pump = stdoutPump
                if (mode == "stuck") {
                    val readinessSeconds = TimeUnit.NANOSECONDS
                        .toSeconds(
                        (deadline - reserve - System.nanoTime()).coerceAtLeast(0),
                    ).coerceAtMost(10)
                    check(JvmChildProcess.awaitReady(stdoutPump, readinessSeconds)) { "Contained stuck worker did not publish actual readiness" }
                    check(process.isAlive) { "Stuck worker JVM exited before timeout control" }
                }
                val remaining = (deadline - reserve - System.nanoTime()).coerceAtLeast(0)
                val wait = if (mode == "stuck") minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)) else remaining
                verificationStarted = true
                IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofNanos(wait), command, lifecycle)
            }.exceptionOrNull()?.let { caught ->
                if (caught is InterruptedException) interrupted = true
                failure = CleanupFailures.merge(failure, caught)
            }
        } finally {
            interrupted = Thread.interrupted() || interrupted
            if (!verificationStarted) {
                runCatching {
                    IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ZERO, command, lifecycle)
                }.exceptionOrNull()?.let { cleanupFailure ->
                    failure = CleanupFailures.merge(failure, cleanupFailure)
                }
            }
            interrupted = Thread.interrupted() || interrupted
            if (process.isAlive) {
                failure = CleanupFailures.merge(failure, IllegalStateException("Owned JVM retirement could not be confirmed"))
            } else {
                // JDK Process publishes exitValue only after its process reaper
                // has observed exit; this cannot block or lose an owner interrupt.
                runCatching { process.exitValue() }.exceptionOrNull()?.let {
                    failure = CleanupFailures.merge(failure, it)
                }
            }
            retireOwnedStdout(pump, deadline)?.let {
                failure = CleanupFailures.merge(failure, it)
            }
            interrupted = Thread.interrupted() || interrupted
            if (interrupted) Thread.currentThread().interrupt()
        }
        return failure
    }

    private fun retireOwnedStdout(
        pump: ChildStdoutPump?,
        deadline: Long,
    ): Throwable? {
        val thread = pump?.thread ?: return null
        var interrupted = Thread.interrupted()
        try {
            while (thread.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return IllegalStateException("Owned JVM stdout retirement incomplete")
                try {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return null
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private class OwnedJvmLifecycle : IsolatedProcessTester.ProcessLifecycleOperations {
        private val observed = linkedMapOf<Long, ProcessHandle>()

        override fun waitFor(
            process: Process,
            timeout: Duration,
        ): Boolean {
            descendants(process)
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS).also { descendants(process) }
        }

        override fun descendants(process: Process): List<ProcessHandle> {
            process.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
            observed.values.toList().forEach { child ->
                child.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
            }
            return observed.values.toList().asReversed()
        }

        override fun destroy(process: Process) {
            process.destroy()
        }

        override fun destroy(handle: ProcessHandle) {
            handle.destroy()
        }

        override fun destroyForcibly(process: Process) {
            process.destroyForcibly()
        }

        override fun destroyForcibly(handle: ProcessHandle) {
            handle.destroyForcibly()
        }

        override fun awaitExit(
            process: Process,
            descendants: List<ProcessHandle>,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var interrupted = Thread.interrupted()
            try {
                while (process.isAlive || descendants.any { it.isAlive }) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    try {
                        TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                process.exitValue()
                return true
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        private var disposableChild = false
        private var childDeadline = Long.MAX_VALUE

        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size == 2)
            disposableChild = true
            childDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(args[1].toLong())
            val fixture = BpfHardeningTest()
            val execution = runCatching {
                when (args[0]) {
                    "prctl" -> fixture.`test that prctl can be blocked when inspection is explicitly disabled`()
                    "mmap" -> fixture.`test that mmap with PROT_EXEC is blocked by default and standard mmap is preserved`()
                    "stuck" -> fixture.runFixture(retirementMillis = args[1].toLong()) {
                        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.MMAP).build()
                        ContainedExecutors.installOnCurrentThread(policy)
                        println("F12_CONTAINED_WORKER_HELD")
                        val neverReleased = CountDownLatch(1)
                        while (true) neverReleased.await(1, TimeUnit.SECONDS)
                    }
                    else -> error("Unknown F12 child fixture")
                }
            }
            execution.exceptionOrNull()?.let {
                it.printStackTrace(System.err)
                // The external parent retains the actual Process through reaping,
                // including failure with a still-live contained platform worker.
                System.exit(2)
            }
            System.exit(0)
        }
    }
}
