package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IsolatedProcessTesterLifecycleTest {
    @Test
    fun `reflection runner exits zero after real successful invocation`() {
        val result = runnerProcess(RunnerSuccess::class.java.name, "succeed", "argument-receipt")
        kotlin.test.assertEquals(0, result.first)
        assertTrue(result.second.contains("runner-success:argument-receipt"))
    }

    @Test
    fun `reflection runner usage exits one without invocation`() {
        val result = runnerProcess()
        kotlin.test.assertEquals(1, result.first)
        assertTrue(result.second.contains("Usage: IsolatedTestRunner"))
    }

    @Test
    fun `reflection runner unwraps ordinary invocation failure and exits two`() {
        val result = runnerProcess(RunnerSuccess::class.java.name, "ordinaryFailure")
        kotlin.test.assertEquals(2, result.first)
        assertTrue(result.second.contains("java.lang.IllegalStateException: ordinary invocation receipt"))
        assertFalse(result.second.contains("java.lang.reflect.InvocationTargetException"), "reflection wrapper must not obscure the invoked cause")
    }

    @Test
    fun `reflection invocation fatal is unwrapped and reported rather than mistaken for direct fatal`() {
        val result = runnerProcess(RunnerSuccess::class.java.name, "fatalFailure")
        kotlin.test.assertEquals(2, result.first)
        assertTrue(result.second.contains("java.lang.AssertionError: invoked fatal receipt"))
        assertFalse(result.second.contains("java.lang.reflect.InvocationTargetException"))
    }

    @Test
    fun `direct fatal class initialization escapes runner ordinary failure handling`() {
        val result = runnerProcess(RunnerFatalInitialization::class.java.name, "succeed")
        kotlin.test.assertEquals(1, result.first)
        assertTrue(result.second.contains("Exception in thread \"main\" java.lang.AssertionError: direct initialization fatal receipt"))
        assertFalse(result.second.contains("IsolatedTestRunner failed to execute"))
    }

    @Test
    fun `only missing no argument constructor falls back to actual INSTANCE`() {
        val result = runnerProcess(RunnerSingleton::class.java.name, "succeed")
        kotlin.test.assertEquals(0, result.first)
        assertTrue(result.second.contains("trying INSTANCE: java.lang.NoSuchMethodException:"))
        assertTrue(result.second.contains("actual INSTANCE invocation receipt"))
    }

    @Test
    fun `failed INSTANCE lookup retains original missing constructor evidence`() {
        val result = runnerProcess(RunnerWithoutSingleton::class.java.name, "succeed")
        kotlin.test.assertEquals(2, result.first)
        assertTrue(result.second.contains("java.lang.NoSuchFieldException: INSTANCE"))
        assertTrue(result.second.contains("Suppressed: java.lang.NoSuchMethodException: ${RunnerWithoutSingleton::class.java.name}.<init>()"))
    }

    @Test
    fun `constructor invocation failure never falls back to available INSTANCE`() {
        val result = runnerProcess(RunnerFailingConstructor::class.java.name, "succeed")
        kotlin.test.assertEquals(2, result.first)
        assertTrue(result.second.contains("java.lang.IllegalStateException: constructor invocation receipt"))
        assertFalse(result.second.contains("trying INSTANCE"))
        assertFalse(result.second.contains("forbidden constructor fallback receipt"))
    }

    @Test
    fun `runner boundary retains null cause wrapper and direct fatal identity in independent process`() {
        val result = runnerProcess(mainClass = IsolatedTestRunner::class.java.name + "\$FailureClassificationFixture")
        kotlin.test.assertEquals(0, result.first)
        assertTrue(result.second.contains("null-wrapper and direct-fatal identities retained"))
    }

    private fun runnerProcess(
        vararg arguments: String,
        mainClass: String = IsolatedTestRunner::class.java.name,
    ): Pair<Int, String> {
        val output = Files.createTempFile("isolated-runner-contract-", ".log")
        val builder = ProcessBuilder(
            listOf(System.getProperty("java.home") + "/bin/java", "-Xmx128m", "-cp", System.getProperty("java.class.path"), mainClass) + arguments,
        ).redirectErrorStream(true).redirectOutput(output.toFile())
        // This root test module has no internal platform access. Only child JVM
        // option inheritance is removed; the runner itself is executed unchanged.
        listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach { builder.environment().remove(it) }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "actual runner child must exit within bound")
            return process.exitValue() to Files.readString(output)
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS), "actual runner child must be physically reaped")
            assertFalse(process.isAlive)
        }
    }

    internal class RunnerSuccess {
        fun succeed(argument: String) = println("runner-success:$argument")

        fun ordinaryFailure(): Unit = throw IllegalStateException("ordinary invocation receipt")

        fun fatalFailure(): Unit = throw AssertionError("invoked fatal receipt")
    }

    internal class RunnerFatalInitialization {
        fun succeed() = println("must never execute")

        companion object {
            init {
                throw AssertionError("direct initialization fatal receipt")
            }
        }
    }

    internal class RunnerSingleton private constructor(
        val receipt: Boolean,
    ) {
        fun succeed() {
            check(receipt)
            println("actual INSTANCE invocation receipt")
        }

        companion object {
            @JvmField
            val INSTANCE = RunnerSingleton(true)
        }
    }

    internal class RunnerWithoutSingleton(
        val receipt: Boolean,
    ) {
        fun succeed() = check(receipt)
    }

    internal class RunnerFailingConstructor private constructor(
        val receipt: Boolean,
    ) {
        constructor() : this(false) {
            throw IllegalStateException("constructor invocation receipt")
        }

        fun succeed() {
            check(receipt)
            println("forbidden constructor fallback receipt")
        }

        companion object {
            @JvmField
            val INSTANCE = RunnerFailingConstructor(true)
        }
    }

    @Test
    fun `successful completion still cleans and reaps descendants`() {
        val process = ProcessBuilder("/bin/true").start()
        assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        val lifecycle = RecordingLifecycle(waitResult = true, awaitResults = ArrayDeque(listOf(true)))
        IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofSeconds(1), listOf("fixture"), lifecycle)
        assertTrue(lifecycle.destroyed, "successful parent completion must still retire its process tree")
        assertTrue(lifecycle.awaitResults.isEmpty(), "cleanup must await exit even after successful completion")
    }

    @Test
    fun `successful parent exit cannot abandon an observed descendant`() {
        val root = Files.createTempDirectory("isolated-success-descendant-")
        val observed = AtomicBoolean()
        IsolatedProcessTester.runIsolatedTest(
            IsolatedProcessLifecycleFixture::class.java.name,
            "exit-with-child",
            root.toString(),
            onDescendantsObserved = { descendants ->
                if (descendants.isNotEmpty() && observed.compareAndSet(false, true)) {
                    Files.writeString(root.resolve("release"), "observed")
                }
            },
        )
        assertTrue(observed.get())
        assertEventuallyDead(root.resolve("parent.pid"))
        assertEventuallyDead(root.resolve("child.pid"))
    }

    @Test
    fun `interruption during successful cleanup is restored`() {
        val process = ProcessBuilder("/bin/true").start()
        assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        val lifecycle = RecordingLifecycle(waitResult = true, awaitFailure = InterruptedException("cleanup interrupted"))
        try {
            runCatching {
                IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofSeconds(1), listOf("fixture"), lifecycle)
            }
            assertTrue(Thread.currentThread().isInterrupted, "successful-path cleanup must preserve interruption")
            assertTrue(lifecycle.forced, "interrupted graceful reaping must still force cleanup")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `forced cleanup also owns descendants discovered during graceful cleanup`() {
        val process = ProcessBuilder("/bin/true").start()
        val lateChild = ProcessHandle.current()
        val lifecycle = RecordingLifecycle(
            awaitResults = ArrayDeque(listOf(false, true)),
            descendantResults = ArrayDeque(listOf(emptyList(), listOf(lateChild))),
        )
        assertIs<IllegalStateException>(
            runCatching {
                IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofMillis(1), listOf("fixture"), lifecycle)
            }.exceptionOrNull(),
        )
        assertTrue(lifecycle.forcedHandles == listOf(lateChild), "late descendants must join forced cleanup ownership")
    }

    @Test
    fun `fatal wait failure retains identity and attaches cleanup failure`() {
        val process = ProcessBuilder("/bin/true").start()
        val fatal = AssertionError("fatal fixture")
        val cleanup = IllegalStateException("cleanup fixture")
        val lifecycle = RecordingLifecycle(waitFailure = fatal, destroyFailure = cleanup)
        val caught = runCatching {
            IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofSeconds(1), listOf("fixture"), lifecycle)
        }.exceptionOrNull()
        assertTrue(caught === fatal)
        assertTrue(fatal.suppressed.contentEquals(arrayOf(cleanup)))
    }

    @Test
    fun `late fatal cleanup outranks ordinary wait failure without abandoning forced reap`() {
        val process = ProcessBuilder("/bin/true").start()
        assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        val ordinary = IllegalStateException("ordinary wait fixture")
        val fatal = AssertionError("late fatal cleanup fixture")
        val lifecycle = RecordingLifecycle(
            waitFailure = ordinary,
            destroyFailure = fatal,
            awaitResults = ArrayDeque(listOf(false, true)),
        )
        val caught = runCatching {
            IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofSeconds(1), listOf("fixture"), lifecycle)
        }.exceptionOrNull()
        assertTrue(caught === fatal, "fatal cleanup must remain the primary failure")
        assertTrue(fatal.suppressed.contentEquals(arrayOf(ordinary)), "ordinary failure identity must survive promotion")
        assertTrue(lifecycle.forced, "fatal graceful cleanup must not abandon forced cleanup")
        assertTrue(lifecycle.awaitResults.isEmpty(), "both physical termination checks must be consumed")
    }

    @Test
    fun `unconfirmed forced cleanup is an explicit failure`() {
        val process = ProcessBuilder("/bin/true").start()
        val lifecycle = RecordingLifecycle(waitResult = false, awaitResults = ArrayDeque(listOf(false, false)))

        val failure = assertIs<IllegalStateException>(
            runCatching {
                IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofMillis(1), listOf("fixture"), lifecycle)
            }.exceptionOrNull(),
        )

        assertTrue(
            failure.suppressed
            .single()
            .message!!
            .contains("could not confirm termination"),
        )
        assertTrue(lifecycle.forced)
        assertTrue(lifecycle.awaitResults.isEmpty(), "both graceful and forced cleanup waits must be checked")
    }

    @Test
    fun `interrupt survives exceptional descendant enumeration`() {
        assertInterruptedCleanupFailure(IllegalStateException("enumeration failed"), throwDuringDestroy = false)
    }

    @Test
    fun `interrupt survives exceptional destroy`() {
        assertInterruptedCleanupFailure(IllegalStateException("destroy failed"), throwDuringDestroy = true)
    }

    @Test
    fun `invalid timeout is rejected before the child starts`() {
        val root = Files.createTempDirectory("isolated-invalid-timeout-")
        withTimeoutProperty("invalid") {
            assertIs<IllegalArgumentException>(
                runCatching {
                    IsolatedProcessTester.runIsolatedTest(
                        IsolatedProcessLifecycleFixture::class.java.name,
                        "exit",
                        root.toString(),
                    )
                }.exceptionOrNull(),
            )
        }
        Thread.sleep(250)
        assertFalse(root.resolve("parent.pid").exists(), "timeout validation must happen before ProcessBuilder.start")
    }

    @Test
    fun `timeout forcibly reaps the parent and descendants within a bound`() {
        val root = Files.createTempDirectory("isolated-timeout-")
        val started = System.nanoTime()
        withTimeoutProperty("1") {
            assertIs<IllegalStateException>(
                runCatching {
                    IsolatedProcessTester.runIsolatedTest(
                        IsolatedProcessLifecycleFixture::class.java.name,
                        "hang",
                        root.toString(),
                    )
                }.exceptionOrNull(),
            )
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(8))
        assertEventuallyDead(root.resolve("parent.pid"))
        assertEventuallyDead(root.resolve("child.pid"))
    }

    @Test
    fun `interruption reaps the process tree and preserves interrupt status`() {
        val root = Files.createTempDirectory("isolated-interrupt-")
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val waiter = Thread {
            try {
                IsolatedProcessTester.runIsolatedTest(IsolatedProcessLifecycleFixture::class.java.name, "hang", root.toString())
            } catch (caught: Throwable) {
                failure.set(caught)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        withTimeoutProperty("30") {
            waiter.start()
            waitFor(root.resolve("ready"))
            waiter.interrupt()
            waiter.join(8_000)
        }
        assertFalse(waiter.isAlive, "interrupted waiter must terminate within the cleanup bound")
        assertIs<IllegalStateException>(failure.get())
        assertTrue(interrupted.get(), "interrupt status must be restored")
        assertEventuallyDead(root.resolve("parent.pid"))
        assertEventuallyDead(root.resolve("child.pid"))
    }

    private fun withTimeoutProperty(
        value: String,
        action: () -> Unit,
    ) {
        val key = "io.mazewall.isolatedTestTimeoutSeconds"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, value)
            action()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    private fun assertInterruptedCleanupFailure(
        cleanupFailure: RuntimeException,
        throwDuringDestroy: Boolean,
    ) {
        val process = ProcessBuilder("/bin/true").start()
        val lifecycle =
            RecordingLifecycle(
                waitFailure = InterruptedException("wait interrupted"),
                enumerationFailure = cleanupFailure.takeUnless { throwDuringDestroy },
                destroyFailure = cleanupFailure.takeIf { throwDuringDestroy },
            )
        try {
            val failure = assertIs<IllegalStateException>(
                runCatching {
                    IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofSeconds(1), listOf("fixture"), lifecycle)
                }.exceptionOrNull(),
            )
            assertIs<InterruptedException>(failure.cause)
            assertTrue(failure.suppressed.contentEquals(arrayOf(cleanupFailure)))
            assertTrue(Thread.currentThread().isInterrupted, "cleanup exceptions must not prevent interrupt restoration")
        } finally {
            Thread.interrupted()
        }
    }

    private class RecordingLifecycle(
        private val waitResult: Boolean = false,
        private val waitFailure: Throwable? = null,
        private val enumerationFailure: RuntimeException? = null,
        private val destroyFailure: Throwable? = null,
        private val awaitFailure: InterruptedException? = null,
        val awaitResults: ArrayDeque<Boolean> = ArrayDeque(),
        val descendantResults: ArrayDeque<List<ProcessHandle>> = ArrayDeque(),
    ) : IsolatedProcessTester.ProcessLifecycleOperations {
        var forced = false
        var destroyed = false
        val forcedHandles = mutableListOf<ProcessHandle>()

        override fun waitFor(
            process: Process,
            timeout: Duration,
        ): Boolean = waitFailure?.let { throw it } ?: waitResult

        override fun descendants(process: Process): List<ProcessHandle> = enumerationFailure?.let { throw it } ?: descendantResults.removeFirstOrNull() ?: emptyList()

        override fun destroy(process: Process) {
            destroyed = true
            destroyFailure?.let { throw it }
        }

        override fun destroy(handle: ProcessHandle) = Unit

        override fun destroyForcibly(process: Process) {
            forced = true
        }

        override fun destroyForcibly(handle: ProcessHandle) {
            forced = true
            forcedHandles.add(handle)
        }

        override fun awaitExit(
            process: Process,
            descendants: List<ProcessHandle>,
        ): Boolean = awaitFailure?.let { throw it } ?: awaitResults.removeFirstOrNull() ?: true
    }

    private fun waitFor(path: java.nio.file.Path) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!path.exists() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(path.exists(), "fixture did not become ready: $path")
    }

    private fun assertEventuallyDead(pidFile: java.nio.file.Path) {
        waitFor(pidFile)
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process $pid was not reaped")
    }
}
