package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Isolated
@NeedsFreshJvm
class SupervisorProxyIntegrationTest : BaseIntegrationTest() {
    companion object {
        private const val CONTROL_DEADLINE_MILLIS = 250L
        private const val CONTROL_READY_SECONDS = 10L
        private const val EXTERNAL_RETIREMENT_RESERVE_SECONDS = 5L
        private const val RETIREMENT_RESERVE_SECONDS = 10L
        private const val DIAGNOSTIC_SCOPE_SECONDS = 120L
        private var disposableChild = false
        private var childDeadline = Long.MAX_VALUE

        private class DiagnosticScope(
            val deadline: Long,
        ) {
            val futures = mutableListOf<Future<*>>()
            var lateFailure: Throwable? = null
            var interrupted = false
        }

        private val diagnosticScope = ThreadLocal<DiagnosticScope>()

        private class OwnedReadExecutor(
            factory: java.util.concurrent.ThreadFactory = Executors.defaultThreadFactory(),
        ) {
            val worker = AtomicReference<Thread?>()
            val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
                factory.newThread(runnable).also { worker.set(it) }
            }
        }

        private fun readDenied(read: () -> String): Boolean {
            val result = runCatching(read)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                val exceptional = prioritizedFailure(failure)
                if (exceptional is Error || exceptional is InterruptedException) throw exceptional
                if (hasPrimaryPermission(failure)) return true
            }
            result.getOrThrow()
            return false
        }

        private fun hasPrimaryPermission(failure: Throwable): Boolean {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = failure
            while (current != null && seen.add(current)) {
                if (current is ContainmentViolationException) {
                    current.errno?.let { return it == 1 || it == 13 }
                } else if (current is java.nio.file.AccessDeniedException) {
                    return true
                } else if (current is java.nio.file.FileSystemException) {
                    return current.reason in listOf("Permission denied", "Operation not permitted")
                } else if (current is java.io.IOException) {
                    val message = current.message
                    // FileInputStream appends the kernel reason; permission words in the path are not evidence.
                    if (current is java.io.FileNotFoundException &&
                        current.stackTrace.take(3).let { frames ->
                            frames.size == 3 &&
                                frames.all { it.className == "java.io.FileInputStream" } &&
                                frames[0].isNativeMethod &&
                                frames[0].methodName == "open0" &&
                                frames[1].methodName == "open" &&
                                frames[2].methodName == "<init>"
                        } &&
                        listOf(1, 13).any {
                            message?.endsWith(" (${io.mazewall.ffi.memory.getSystemStrerror(it)})") == true
                        }
                    ) {
                        return true
                    }
                    val native = message?.let {
                        Regex("""Exec failed, error: (\d+) \(([^\r\n]*)\) ?""").matchEntire(it)
                    }
                    if (native != null) {
                        val errno = native.groupValues[1].toIntOrNull()
                        return (errno == 1 || errno == 13) &&
                            native.groupValues[2] ==
                            io.mazewall.ffi.memory
                                .getSystemStrerror(checkNotNull(errno))
                    }
                    // A ProcessBuilder filename is not native permission evidence; inspect its cause.
                    if (message != null && !message.startsWith("Cannot run program ")) {
                        val receipt = Regex("""(?:^|[ (,])(?:error|errno)[=:]\s*(\d+)(?:,\s*([^\r\n]*))?$""").find(message)
                        if (receipt != null) {
                            val errno = receipt.groupValues[1].toIntOrNull()
                            return (errno == 1 || errno == 13) &&
                                (
                                    receipt.groups[2] == null ||
                                receipt.groupValues[2] == io.mazewall.ffi.memory
                                    .getSystemStrerror(checkNotNull(errno))
                                )
                        }
                        if (listOf(1, 13).any {
                            io.mazewall.ffi.memory
                            .getSystemStrerror(it) == message
                        }
                        ) {
                            return true
                        }
                    }
                }
                current = current.cause
            }
            return false
        }

        private fun readFastPath(read: () -> String): Boolean {
            val result = runCatching(read)
            result.exceptionOrNull()?.let { throw prioritizedFailure(it) }
            return true
        }

        private fun <T> awaitOwned(
            future: Future<T>,
            onWaitInterrupted: (InterruptedException) -> Unit = {},
        ): T {
            val scope = diagnosticScope.get()
            if (scope != null && scope.futures.none { it === future }) scope.futures.add(future)
            try {
                if (scope == null) return future.get()
                // Leave the original budget's final ten seconds for physical retirement and diagnostics.
                val remaining = scope.deadline - TimeUnit.SECONDS.toNanos(RETIREMENT_RESERVE_SECONDS) - System.nanoTime()
                return future.get(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS)
            } catch (caught: InterruptedException) {
                onWaitInterrupted(caught)
                throw caught
            }
        }

        private fun awaitRead(
            future: Future<Result<Boolean>>,
            onWaitInterrupted: (InterruptedException) -> Unit = {},
        ): Boolean {
            val result = runCatching { awaitOwned(future, onWaitInterrupted).getOrThrow() }
            result.exceptionOrNull()?.let { throw prioritizedFailure(it) }
            return result.getOrThrow()
        }

        private fun shutdownForDiagnostics(
            owned: OwnedReadExecutor,
            file: File? = null,
            beforeDelete: () -> Unit = {},
        ) {
            val scope = checkNotNull(diagnosticScope.get())
            runCatching { owned.executor.shutdown() }.exceptionOrNull()?.let {
                scope.lateFailure = mergeFailures(scope.lateFailure, it)
            }
            val worker = owned.worker.get()
            retireDiagnosticWorker(scope, worker)
            scope.futures.forEach { consumeDiagnosticOutcome(scope, it) }
            // A deadline is failure, never physical-end proof. Native workers remain
            // owned by the disposable JVM's external Process waiter until reaping.
            if (worker?.isAlive != true) {
                runCatching(beforeDelete).exceptionOrNull()?.let {
                    scope.lateFailure = mergeFailures(scope.lateFailure, it)
                }
                runCatching { file?.delete() }.exceptionOrNull()?.let {
                    scope.lateFailure = mergeFailures(scope.lateFailure, it)
                }
            }
        }

        private fun retireDiagnosticWorker(
            scope: DiagnosticScope,
            worker: Thread?,
        ) {
            // Executor termination is not physical Thread exit; the actual owned thread must join.
            while (worker?.isAlive == true && System.nanoTime() < scope.deadline) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(worker, (scope.deadline - System.nanoTime()).coerceAtLeast(0))
                } catch (caught: InterruptedException) {
                    scope.interrupted = true
                    scope.lateFailure = mergeFailures(scope.lateFailure, caught)
                }
            }
            if (worker?.isAlive == true) {
                scope.lateFailure = mergeFailures(scope.lateFailure, IllegalStateException("Owned proxy worker physical retirement incomplete"))
            }
        }

        private fun consumeDiagnosticOutcome(
            scope: DiagnosticScope,
            future: Future<*>,
        ) {
            var retry: Boolean
            do {
                val outcome = runCatching { future.get((scope.deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
                val thrown = outcome.exceptionOrNull()
                if (thrown is InterruptedException) scope.interrupted = true
                val failure = thrown ?: (outcome.getOrNull() as? Result<*>)?.exceptionOrNull()
                if (failure != null) scope.lateFailure = mergeFailures(scope.lateFailure, failure)
                retry = thrown is InterruptedException && System.nanoTime() < scope.deadline
            } while (retry)
        }

        private fun causeChain(failure: Throwable): List<Throwable> {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val causes = mutableListOf<Throwable>()
            val pending = java.util.ArrayDeque<Throwable>()
            pending.add(failure)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                causes.add(current)
                current.cause?.let(pending::addLast)
                current.suppressed.forEach(pending::addLast)
            }
            return causes
        }

        private fun prioritizedFailure(failure: Throwable): Throwable {
            val graph = causeChain(failure)
            if (graph.any { it is InterruptedException }) {
                val scope = diagnosticScope.get()
                if (scope == null) Thread.currentThread().interrupt() else scope.interrupted = true
            }
            val primary = graph.firstOrNull { it is Error }
                ?: graph.firstOrNull { it is InterruptedException } ?: failure
            if (primary !== failure && primary.suppressed.none { it === failure }) primary.addSuppressed(failure)
            return primary
        }

        private fun mergeFailures(
            previous: Throwable?,
            next: Throwable,
        ): Throwable {
            val primary = previous?.let(::prioritizedFailure)
            return CleanupFailures.merge(primary, prioritizedFailure(next))
        }

        private fun readDaemonLog(read: () -> String): String {
            val result = runCatching(read)
            result.exceptionOrNull()?.let { throw prioritizedFailure(it) }
            return result.getOrThrow()
        }

        private fun <T> withDiagnosticCleanup(
            action: () -> T,
            cleanup: () -> Unit,
            deadline: Long = minOf(System.nanoTime() + TimeUnit.SECONDS.toNanos(DIAGNOSTIC_SCOPE_SECONDS), childDeadline),
        ): T {
            val previousScope = diagnosticScope.get()
            val scope = DiagnosticScope(deadline)
            diagnosticScope.set(scope)
            scope.interrupted = Thread.interrupted()
            try {
                val result = runCatching(action)
                var failure = result.exceptionOrNull()?.let(::prioritizedFailure)
                if (Thread.interrupted()) scope.interrupted = true
                val cleanupResult = runCatching(cleanup)
                scope.lateFailure?.let { failure = mergeFailures(failure, it) }
                cleanupResult.exceptionOrNull()?.let { failure = mergeFailures(failure, it) }
                if (Thread.interrupted()) scope.interrupted = true
                failure?.let { throw it }
                return result.getOrThrow()
            } finally {
                if (previousScope == null) diagnosticScope.remove() else diagnosticScope.set(previousScope)
                if (scope.interrupted) {
                    if (previousScope == null) Thread.currentThread().interrupt() else previousScope.interrupted = true
                }
            }
        }

        private fun runStuckNativeRead(
            retainedFile: File,
            independentFile: File,
        ) {
            val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            val writer = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), listener.localPort)
            val reader = listener.accept()
            listener.close()
            val input = reader.getInputStream()
            writer.getOutputStream().write(0)
            check(input.read() == 0) // Warm the actual JDK native read before containment.
            val owned = OwnedReadExecutor()
            val contained = ContainedExecutors.wrap(owned.executor, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
            val entered = CountDownLatch(1)
            val future = contained.submit<Result<Boolean>> {
                runCatching {
                    entered.countDown()
                    input.read() < 0 // The acquired peer stays open and sends nothing; no cooperative release.
                }
            }
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONTROL_READY_SECONDS)
            check(entered.await(CONTROL_READY_SECONDS, TimeUnit.SECONDS))
            val worker = checkNotNull(owned.worker.get())
            while (!isNativeRead(worker) && System.nanoTime() < readyDeadline) Thread.yield()
            check(isNativeRead(worker)) { "Control must reach an actual blocking native socket read" }
            println("PROXY_NATIVE_WORKER_BLOCKED javaThreadId=${worker.threadId()} nativeRead=true")
            val wait = AtomicReference<TimeoutException>()
            val result = runCatching {
                withDiagnosticCleanup(action = {
                    try {
                        awaitRead(future)
                    } catch (caught: TimeoutException) {
                        wait.set(caught)
                        throw caught
                    }
                }, cleanup = {
                    shutdownForDiagnostics(owned, retainedFile)
                    check(worker.isAlive)
                    check(independentFile.delete())
                    println("PROXY_INDEPENDENT_CLEANUP workerAlive=${worker.isAlive}")
                    readDaemonLog { "actual deadline diagnostics" }
                }, deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONTROL_DEADLINE_MILLIS))
            }
            check(result.exceptionOrNull() === wait.get() && wait.get() != null)
            println("PROXY_DEADLINE_OUTCOME identity=true workerAlive=${worker.isAlive} retained=${retainedFile.exists()}")
            // These real sockets and the permanently contained worker stay owned by
            // this disposable JVM until the acquired external Process is retired/reaped.
            CountDownLatch(1).await()
            java.lang.ref.Reference
                .reachabilityFence(writer)
            java.lang.ref.Reference
                .reachabilityFence(reader)
        }

        private fun isNativeRead(worker: Thread): Boolean =
            worker.stackTrace.any {
            it.isNativeMethod && it.className.startsWith("sun.nio.ch.") && it.methodName in listOf("poll", "read0")
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDownAll() {
            io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
                .getInstance()
                .stop()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size >= 2)
            disposableChild = true
            childDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(args[1].toLong())
            val fixture = SupervisorProxyIntegrationTest()
            val execution = runCatching {
                when (args[0]) {
                    "scoping" -> fixture.`test supervised openat allows or denies based on JVM stack trace scoping policy`()
                    "fast-path" -> fixture.`test daemon fast-path allows reads inside java home even from evil context`()
                    "stuck" -> {
                        val files = args.drop(2)
                        if (files.singleOrNull() == "--withhold-readiness") {
                            println("PROXY_READINESS_WITHHELD")
                            CountDownLatch(1).await()
                        } else {
                            runStuckNativeRead(File(files[0]), File(files[1]))
                        }
                    }
                    else -> error("Unknown proxy child fixture")
                }
            }
            val cleanup = runCatching { tearDownAll() }
            var failure = execution.exceptionOrNull()
            cleanup.exceptionOrNull()?.let { failure = mergeFailures(failure, it) }
            failure?.let {
                it.printStackTrace(System.err)
                System.exit(2)
            }
            System.exit(0)
        }
    }

    /**
     * Verifies the [StacktraceScopingPolicy] allow/deny contract.
     *
     * ### What this test checks
     * - **Functional**: a task calling `file.readText()` from within a method named `legitAction`
     *   successfully reads the file (the scoping policy allows it).
     * - **Security**: a task calling `file.readText()` from outside `legitAction` is denied by
     *   the scoping policy with an `IOException` (EPERM from seccomp).
     *
     * ### Why `allowedCalls` is not asserted
     *
     * On a cold JVM thread (first execution), `file.readText()` may trigger lazy classloading
     * of Kotlin stdlib IO classes. Any read originating from the JDK home path is automatically
     * allowed via the daemon-side fast-path bypass to prevent deadlock — the scoping
     * policy is not consulted for that call. The file is still opened successfully (bypass =
     * allow), so the legit functional test passes regardless.
     *
     * Asserting `allowedCalls.isNotEmpty()` would make the test depend on whether classloading
     * happened to coincide with the file access on first execution — a timing condition that
     * varies between a warmed local runner and a cold CI runner. That is an internal
     * implementation detail, not the library's security contract.
     *
     * ### Why `deniedCalls` IS asserted (and is reliable)
     *
     * The evil action always runs **after** the legit action. By the time evil executes, all
     * Kotlin IO classes used by `file.readText()` are already loaded. The daemon fast-path does
     * not fire for the non-JDK user temp file, so the scoping policy is consulted and denies the access.
     * This assertion is therefore deterministic regardless of JVM warmup state.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `test supervised openat allows or denies based on JVM stack trace scoping policy`() {
        if (!disposableChild) {
            ProxyDiagnosticJvm.run("scoping").failure?.let { throw it }
            return
        }
        val tempFile = Files.createTempFile("supervised_test_", ".txt").toFile()
        tempFile.writeText("legit content")

        val deniedCalls = mutableListOf<String>()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack -> authorize(args, stack) },
                Syscall.OPEN to { tid, args, stack -> authorize(args, stack) },
            )

            private fun authorize(
                args: List<Any>,
                stack: List<StackTraceElement>,
            ): Boolean {
                val path = args.firstOrNull() as? String ?: ""
                if (!path.contains("supervised_test_")) {
                    return true
                }
                val isLegit = stack.any { it.className.contains("LegitContext") }
                if (!isLegit) {
                    deniedCalls.add(path)
                }
                return isLegit
            }
        }

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .build()

        val ownedExecutor = OwnedReadExecutor()
        val rawExecutor = ownedExecutor.executor
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)

        withDiagnosticCleanup(action = {
            // Run legit action first. The file is read successfully (either via the allow path
            // of the policy, or via the classloader bypass on a cold JVM — both are correct).
            // The primary effect here is to ensure all Kotlin IO classes are loaded so the
            // subsequent evil-action execution is free from classloading interference.
            val legitResult = awaitOwned(
                containedExecutor
                .submit<String> {
                LegitContext(tempFile).run()
            },
            )
            assertEquals("legit content", legitResult)

            // Run evil action second. All IO classes are now loaded; the classloader bypass
            // cannot fire. The scoping policy is invoked and must deny the access.
            val evilResult = awaitRead(
                containedExecutor.submit<Result<Boolean>> {
                runCatching { EvilContext(tempFile).run() }
            },
            )
            assertTrue(evilResult, "Evil action must be denied by the scoping policy")
            assertTrue(
                deniedCalls.any { it.contains(tempFile.name) },
                "Scoping policy must have been invoked and denied the evil file access",
            )
        }, cleanup = {
            shutdownForDiagnostics(ownedExecutor, tempFile)
            System.err.println("--- VALIDATION LOGS ---")
            while (true) {
                val log = io.mazewall.enforcer.internal.supervisor.ValidationLog.logs
                    .poll() ?: break
                System.err.print(log)
            }
            System.err.println("-----------------------")
            System.err.println("--- DAEMON LOGS ---")
            System.err.println("DEBUG: SupervisorDaemonManager classLoader: ${io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager::class.java.classLoader}")
            readDaemonLog {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                if (logFile.exists()) {
                    logFile.readText().also { System.err.println(it) }
                } else {
                    System.err.println("Daemon log file does not exist")
                    ""
                }
            }
            System.err.println("-------------------")
        })
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test daemon fast-path allows reads inside java home even from evil context`() {
        if (!disposableChild) {
            ProxyDiagnosticJvm.run("fast-path").failure?.let { throw it }
            return
        }
        val releaseFile = File(System.getProperty("java.home"), "release")
        if (!releaseFile.exists()) {
            return
        }

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack -> false },
                Syscall.OPEN to { tid, args, stack -> false },
            )
        }

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .build()

        val ownedExecutor = OwnedReadExecutor()
        val rawExecutor = ownedExecutor.executor
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)

        withDiagnosticCleanup(action = {
            // Read a core JDK file. Despite scopingPolicy returning false for everything,
            // the daemon fast-path should intercept the read and allow it immediately.
            val result = awaitRead(
                containedExecutor.submit<Result<Boolean>> {
                runCatching { readFastPath { releaseFile.readText() } }
            },
            )

            assertTrue(result, "Reading JDK home files must succeed via daemon fast-path bypass")
        }, cleanup = {
            shutdownForDiagnostics(ownedExecutor)
            System.err.println("--- VALIDATION LOGS ---")
            while (true) {
                val log = io.mazewall.enforcer.internal.supervisor.ValidationLog.logs
                    .poll() ?: break
                System.err.print(log)
            }
            System.err.println("-----------------------")
            System.err.println("--- DAEMON LOGS ---")
            System.err.println("DEBUG: SupervisorDaemonManager classLoader: ${io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager::class.java.classLoader}")
            readDaemonLog {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                if (logFile.exists()) {
                    logFile.readText().also { System.err.println(it) }
                } else {
                    System.err.println("Daemon log file does not exist")
                    ""
                }
            }
            System.err.println("-------------------")
        })
    }

    private class LegitContext(
        private val file: File,
    ) {
        fun run(): String {
            return file.readText()
        }
    }

    private class EvilContext(
        private val file: File,
    ) {
        fun run(): Boolean {
            return readDenied { file.readText() }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun proxyStuckNativeReadReachesDeadlineCleanupAndExternalReap() {
        val retainedFile = Files.createTempFile("proxy_stuck_retained_", ".txt").toFile()
        val independentFile = Files.createTempFile("proxy_stuck_independent_", ".txt").toFile()
        try {
            val outcome = ProxyDiagnosticJvm.run("stuck", retainedFile.path, independentFile.path)
            assertTrue(
                outcome.failure is IllegalStateException &&
                outcome.failure.message
                .orEmpty()
                .contains("exceeded"),
            )
            assertTrue(outcome.failure.suppressed.isEmpty(), "External retirement must retain cleanup failures")
            assertTrue(outcome.lines.any { it.startsWith("PROXY_INDEPENDENT_CLEANUP") }, "Deadline must reach independent cleanup with the real worker still blocked")
            assertTrue(outcome.lines.any { it.startsWith("PROXY_DEADLINE_OUTCOME identity=true workerAlive=true retained=true") })
            assertFalse(independentFile.exists())
            assertTrue(retainedFile.exists(), "Live worker's fixture is retained until external physical end")
        } finally {
            retainedFile.delete()
            independentFile.delete()
        }
    }

    @Test
    fun proxyNearExpiredReadinessPreservesRetirementReserve() {
        val outcome = ProxyDiagnosticJvm.run(
            "stuck",
            "--withhold-readiness",
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_500),
        )
        val process = outcome.process
        val stdout = checkNotNull(outcome.stdoutThread)
        try {
            val processAlive = process.isAlive
            val exit = if (processAlive) null else process.exitValue()
            val stdoutAlive = stdout.isAlive
            val lifecycleFailure = outcome.failure?.suppressed?.singleOrNull()
            println(
                "PROXY_READINESS_RESERVE pid=${process.pid()} remainingNanos=${outcome.retirementRemainingNanos} " +
                    "processAlive=$processAlive exit=$exit stdoutAlive=$stdoutAlive",
            )
            println("PROXY_READINESS_FAILURE primary=${outcome.failure} lifecycle=${lifecycleFailure?.message?.substringBefore('\n')} cleanup=${lifecycleFailure?.suppressed?.toList()}")
            assertAll(
                {
                    assertTrue(
                        outcome.failure is IllegalStateException && outcome.failure.message == "Native worker did not reach a real blocking read",
                    )
                },
                {
                    assertTrue(lifecycleFailure is IllegalStateException && lifecycleFailure.message.orEmpty().startsWith("Isolated test process exceeded 0s"))
                    assertTrue(lifecycleFailure.suppressed.isEmpty(), "Owner must complete independent cleanup")
                },
                { assertTrue(outcome.lines.contains("PROXY_READINESS_WITHHELD"), "Control child must actually execute") },
                {
                    assertTrue(
                        outcome.retirementRemainingNanos >= TimeUnit.SECONDS.toNanos(EXTERNAL_RETIREMENT_RESERVE_SECONDS),
                        "Readiness spent the physical-retirement reserve",
                    )
                },
                { assertFalse(processAlive, "Acquired root must be physically reaped before owner return") },
                { assertTrue(exit != null, "Real Process.exitValue must be available") },
                { assertFalse(stdoutAlive, "Acquired stdout thread must physically end before owner return") },
            )
        } finally {
            // Retain the real child/pump even on RED so a failing control cannot orphan them.
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(EXTERNAL_RETIREMENT_RESERVE_SECONDS, TimeUnit.SECONDS), "Control fallback must confirm root reap")
            TimeUnit.SECONDS.timedJoin(stdout, EXTERNAL_RETIREMENT_RESERVE_SECONDS)
            assertFalse(stdout.isAlive, "Control fallback must confirm stdout end")
            println("PROXY_READINESS_CONTROL_SETTLED pid=${process.pid()} exit=${process.exitValue()} processAlive=${process.isAlive} stdoutAlive=${stdout.isAlive}")
        }
    }

    @Test
    fun `quality proxy read accepts typed denial`() {
        assertTrue(readDenied { throw java.nio.file.AccessDeniedException("fixture") })
        assertTrue(readDenied { throw java.io.IOException("Operation not permitted") })
    }

    @Test
    fun `quality proxy read rejects unrelated IO failure`() {
        val failure = java.io.IOException("disk failure")
        assertSame(failure, assertThrows(java.io.IOException::class.java) { readDenied { throw failure } })
    }

    @Test
    fun `quality proxy read preserves fatal denial diagnostic`() {
        val failure = LinkageError("Permission denied")
        assertSame(failure, assertThrows(LinkageError::class.java) { readDenied { throw failure } })
    }

    @Test
    fun `quality proxy read successful access is not denial`() {
        assertEquals(false, readDenied { "content" })
        assertTrue(readFastPath { "content" })
    }

    @Test
    fun `quality proxy fast path preserves unexpected failure through Future`() {
        val failure = java.io.IOException("read failure")
        val future = CompletableFuture.completedFuture(runCatching { readFastPath { throw failure } })
        assertSame(failure, assertThrows(java.io.IOException::class.java) { awaitRead(future) })
    }

    @Test
    fun `quality proxy fast path preserves fatal failure through Future`() {
        val failure = OutOfMemoryError("read failure")
        val future = CompletableFuture.completedFuture(runCatching { readFastPath { throw failure } })
        assertSame(failure, assertThrows(OutOfMemoryError::class.java) { awaitRead(future) })
    }

    @Test
    fun `quality diagnostic read rejects unrelated failure`() {
        val failure = java.io.IOException("diagnostic disk failure")
        assertSame(failure, assertThrows(java.io.IOException::class.java) { readDaemonLog { throw failure } })
    }

    @Test
    fun `quality diagnostic read preserves fatal and success`() {
        val failure = LinkageError("diagnostic failure")
        assertSame(failure, assertThrows(LinkageError::class.java) { readDaemonLog { throw failure } })
        assertEquals("daemon receipt", readDaemonLog { "daemon receipt" })
    }

    @Test
    fun `quality diagnostic cleanup preserves primary fatal receipt`() {
        val primary = OutOfMemoryError("primary failure")
        val secondary = java.io.IOException("diagnostic failure")
        var cleaned = false
        assertSame(
            primary,
            assertThrows(OutOfMemoryError::class.java) {
            withDiagnosticCleanup(action = { throw primary }, cleanup = {
                cleaned = true
                readDaemonLog { throw secondary }
            })
        },
        )
        assertTrue(cleaned)
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())
    }

    @Test
    fun `quality fatal first cleanup retains primary interruption receipt`() {
        val primary = InterruptedException("primary interruption")
        val secondary = LinkageError("diagnostic failure")
        try {
            assertSame(
                secondary,
                assertThrows(LinkageError::class.java) {
                withDiagnosticCleanup(action = { throw primary }, cleanup = { readDaemonLog { throw secondary } })
            },
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, secondary.suppressed.size)
            assertSame(primary, secondary.suppressed.single())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `quality diagnostic cleanup propagates failure after success`() {
        val failure = java.io.IOException("diagnostic failure")
        assertSame(
            failure,
            assertThrows(java.io.IOException::class.java) {
            withDiagnosticCleanup(action = { true }, cleanup = { readDaemonLog { throw failure } })
        },
        )
    }

    @Test
    fun `quality diagnostic read rejects denial wrapping fatal cause`() {
        val fatal = LinkageError("fatal cause")
        val denial = java.nio.file
            .AccessDeniedException("fixture")
            .apply { initCause(fatal) }
        assertSame(fatal, assertThrows(LinkageError::class.java) { readDenied { throw denial } })
    }

    @Test
    fun `quality diagnostic Future restores transported interruption`() {
        val failure = InterruptedException("worker interruption")
        val future = CompletableFuture.completedFuture(Result.failure<Boolean>(failure))
        try {
            assertSame(failure, assertThrows(InterruptedException::class.java) { awaitRead(future) })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `quality fatal first cleanup promotes fatal over ordinary primary`() {
        val primary = java.io.IOException("primary failure")
        val secondary = LinkageError("diagnostic failure")
        assertSame(
            secondary,
            assertThrows(LinkageError::class.java) {
            withDiagnosticCleanup(action = { throw primary }, cleanup = { readDaemonLog { throw secondary } })
        },
        )
        assertEquals(1, secondary.suppressed.size)
        assertSame(primary, secondary.suppressed.single())
    }

    @Test
    fun `quality fatal first cleanup retains first fatal identity`() {
        val primary = OutOfMemoryError("primary failure")
        val secondary = LinkageError("diagnostic failure")
        assertSame(
            primary,
            assertThrows(OutOfMemoryError::class.java) {
            withDiagnosticCleanup(action = { throw primary }, cleanup = { readDaemonLog { throw secondary } })
        },
        )
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())
    }

    @Test
    fun `quality fatal first cleanup retains ordinary interruption identity`() {
        val primary = InterruptedException("primary interruption")
        val secondary = java.io.IOException("diagnostic failure")
        try {
            assertSame(
                primary,
                assertThrows(InterruptedException::class.java) {
                withDiagnosticCleanup(action = { throw primary }, cleanup = { readDaemonLog { throw secondary } })
            },
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, primary.suppressed.size)
            assertSame(secondary, primary.suppressed.single())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `proxy operation primary rejects unrelated failure with suppressed denial`() {
        val primary = java.io.IOException("disk failure")
        primary.addSuppressed(java.nio.file.AccessDeniedException("fixture"))
        assertSame(primary, assertThrows(java.io.IOException::class.java) { readDenied { throw primary } })
    }

    @Test
    fun `proxy operation primary rejects diagnostic only permission text`() {
        val primary = IllegalStateException("diagnostic info: Permission denied")
        assertSame(primary, assertThrows(IllegalStateException::class.java) { readDenied { throw primary } })
        val filename = java.io.FileNotFoundException("/tmp/filename (Operation not permitted)")
        assertSame(filename, assertThrows(java.io.FileNotFoundException::class.java) { readDenied { throw filename } })
    }

    @Test
    fun `proxy operation primary rejects unknown and null diagnostics`() {
        for (primary in listOf(java.io.IOException("unknown operation failure"), java.io.IOException(null as String?))) {
            assertSame(primary, assertThrows(java.io.IOException::class.java) { readDenied { throw primary } })
        }
    }

    @Test
    fun `proxy suppressed fatal outranks primary permission with actual identity`() {
        val fatal = LinkageError("suppressed fatal")
        val denial = java.nio.file
            .AccessDeniedException("fixture")
            .apply { addSuppressed(fatal) }
        assertSame(fatal, assertThrows(LinkageError::class.java) { readDenied { throw denial } })
        assertTrue(failureReachable(fatal, denial), "actual primary retained under promoted fatal")
    }

    @Test
    fun `proxy suppressed interruption outranks permission and restores owner flag`() {
        val wait = InterruptedException("transported actual interruption")
        val denial = java.nio.file
            .AccessDeniedException("fixture")
            .apply { addSuppressed(wait) }
        try {
            assertSame(wait, assertThrows(InterruptedException::class.java) { readDenied { throw denial } })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(failureReachable(wait, denial))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `proxy cyclic graph promotes actual fatal and retains interruption`() {
        val denial = java.nio.file.AccessDeniedException("fixture")
        val wait = InterruptedException("transported interruption")
        val fatal = AssertionError("actual fatal")
        denial.addSuppressed(wait)
        wait.addSuppressed(denial)
        wait.addSuppressed(fatal)
        try {
            assertSame(fatal, assertThrows(AssertionError::class.java) { readDenied { throw denial } })
            assertTrue(failureReachable(fatal, wait))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `proxy diagnostic read promotes actual nested fatal over interruption`() {
        val wait = InterruptedException("diagnostic interruption")
        val fatal = LinkageError("diagnostic fatal")
        wait.addSuppressed(fatal)
        try {
            assertSame(fatal, assertThrows(LinkageError::class.java) { readDaemonLog { throw wait } })
            assertTrue(failureReachable(fatal, wait))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `proxy cleanup consumes fatal nested under actual ordinary cleanup failure`() {
        val primary = java.io.IOException("owner operation")
        val cleanup = java.io.IOException("cleanup operation")
        val fatal = LinkageError("cleanup fatal")
        cleanup.addSuppressed(fatal)
        var attempted = false
        assertSame(
            fatal,
            assertThrows(LinkageError::class.java) {
            withDiagnosticCleanup(action = { throw primary }, cleanup = {
                attempted = true
                readDaemonLog { throw cleanup }
            })
        },
        )
        assertTrue(attempted)
        assertTrue(failureReachable(fatal, primary))
        assertTrue(failureReachable(fatal, cleanup))
    }

    @Test
    fun `proxy actual held task wait interrupt retires worker before deletion and diagnostics`() {
        HeldTaskScenario().run(java.io.IOException("late worker operation"))
    }

    @Test
    fun `proxy actual held task wait interrupt consumes late fatal before restoration`() {
        HeldTaskScenario().run(LinkageError("late worker fatal"))
    }

    private inner class HeldTaskScenario {
        fun run(taskFailure: Throwable) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            val retirementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val waitCaught = CountDownLatch(1)
            val ownerFinished = CountDownLatch(1)
            val actualWait = AtomicReference<InterruptedException?>()
            val actual = AtomicReference<Throwable?>()
            val worker = AtomicReference<Thread?>()
            val ownedExecutor = OwnedReadExecutor { runnable ->
                Thread.ofPlatform().unstarted(runnable).also { worker.set(it) }
            }
            val executor = ownedExecutor.executor
            val file = Files.createTempFile("proxy_control_", ".txt").toFile()
            val future = executor.submit<Result<Boolean>> {
                entered.countDown()
                runCatching {
                    release.await()
                    throw taskFailure
                }
            }
            val flagAtCatch = AtomicReference(false)
            val flagWhileLive = AtomicReference(false)
            val liveAtDeletion = AtomicReference(false)
            val fileAtDiagnostic = AtomicReference(true)
            val liveAtDiagnostic = AtomicReference(false)
            val restored = AtomicReference(false)
            val cleanupAttempted = AtomicReference(false)
            val diagnosticAttempted = AtomicReference(false)
            val retiredAtReturn = AtomicReference(false)
            val owner = Thread.ofPlatform().unstarted {
                try {
                    actual.set(
                        runCatching {
                            withDiagnosticCleanup(action = {
                                awaitRead(future) { caught ->
                                    actualWait.set(caught)
                                    flagAtCatch.set(Thread.currentThread().isInterrupted)
                                    waitCaught.countDown()
                                }
                            }, cleanup = {
                                cleanupAttempted.set(true)
                                flagWhileLive.set(checkNotNull(worker.get()).isAlive && Thread.currentThread().isInterrupted)
                                shutdownForDiagnostics(ownedExecutor, file) {
                                    liveAtDeletion.set(checkNotNull(worker.get()).isAlive)
                                }
                                readDaemonLog {
                                    diagnosticAttempted.set(true)
                                    fileAtDiagnostic.set(file.exists())
                                    liveAtDiagnostic.set(checkNotNull(worker.get()).isAlive)
                                    "control diagnostic"
                                }
                            })
                        }.exceptionOrNull(),
                    )
                } finally {
                    retiredAtReturn.set(worker.get()?.isAlive == false)
                    restored.set(Thread.currentThread().isInterrupted)
                    ownerFinished.countDown()
                }
            }
            var failure: Throwable? = null
            try {
                failure = runCatching {
                    owner.start()
                    assertTrue(entered.await(remaining(deadline), TimeUnit.NANOSECONDS))
                    awaitHeldFutureWait(owner, future, deadline)
                    owner.interrupt()
                    assertTrue(waitCaught.await(remaining(deadline), TimeUnit.NANOSECONDS), "actual thrown Future wait caught")
                    assertTrue(checkNotNull(worker.get()).isAlive, "real worker held at actual wait catch")
                    assertFalse(flagAtCatch.get(), "wait catch cleared owner flag")
                    assertFalse(ownerFinished.await(100, TimeUnit.MILLISECONDS), "owner cannot escape held worker")
                    assertFalse(owner.isInterrupted, "owner flag deferred while real task held")
                    assertTrue(file.exists(), "fixture file retained while worker physically live")
                    release.countDown()
                    owner.join(TimeUnit.NANOSECONDS.toMillis(remaining(deadline)).coerceAtLeast(1))
                    assertFalse(owner.isAlive)
                    assertTrue(retiredAtReturn.get(), "physical worker exit before owner return")
                    assertFalse(checkNotNull(worker.get()).isAlive)
                    assertTrue(cleanupAttempted.get())
                    assertTrue(diagnosticAttempted.get())
                    assertFalse(flagWhileLive.get())
                    assertFalse(liveAtDeletion.get(), "worker physically ended before file deletion")
                    assertFalse(fileAtDiagnostic.get())
                    assertFalse(liveAtDiagnostic.get(), "worker physically ended before diagnostic return")
                    assertTrue(restored.get(), "owner flag restored only after retirement and outcome")
                    assertHeldTaskOutcome(actualWait.get(), actual.get(), taskFailure)
                }.exceptionOrNull()
            } finally {
                // RED may return early; the controller still owns and physically drains both threads.
                release.countDown()
                executor.shutdown()
                val retirement = retireHeldThreads(listOfNotNull(owner, worker.get()), retirementDeadline, failure)
                failure = retirement.first
                if (executor.isTerminated) failure = consumeRetiredTask(future, taskFailure, failure)
                if (worker.get()?.isAlive == false) file.delete()
                if (retirement.second) Thread.currentThread().interrupt()
            }
            failure?.let { throw it }
        }

        private fun awaitHeldFutureWait(
            owner: Thread,
            future: Future<*>,
            deadline: Long,
        ) {
            while (System.nanoTime() < deadline && !isFutureWait(owner)) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, owner.state, "owner physically waiting on pending Future")
            assertTrue(isFutureWait(owner), "actual Future.get wait before controller interrupt")
            assertFalse(future.isDone, "held task cannot complete before actual wait interrupt")
        }

        private fun assertHeldTaskOutcome(
            actualWait: InterruptedException?,
            actual: Throwable?,
            taskFailure: Throwable,
        ) {
            val caught = checkNotNull(actualWait)
            val expected = if (taskFailure is Error) taskFailure else caught
            assertSame(expected, actual)
            assertTrue(failureReachable(expected, caught), "exact actual wait identity retained")
            assertTrue(failureReachable(expected, taskFailure), "actual late task outcome consumed")
        }

        private fun isFutureWait(owner: Thread): Boolean =
            owner.state == Thread.State.TIMED_WAITING &&
            owner.stackTrace.any {
                it.className == "java.util.concurrent.FutureTask" && it.methodName == "awaitDone"
            }

        private fun retireHeldThreads(
            threads: List<Thread>,
            deadline: Long,
            primary: Throwable?,
        ): Pair<Throwable?, Boolean> {
            var failure = primary
            var interrupted = false
            for (thread in threads) {
                while (thread.isAlive && System.nanoTime() < deadline) {
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(thread, remaining(deadline))
                    } catch (caught: InterruptedException) {
                        interrupted = true
                        failure = CleanupFailures.merge(failure, caught)
                    }
                }
                if (thread.isAlive) failure = CleanupFailures.merge(failure, AssertionError("control owned thread retirement incomplete"))
            }
            return failure to interrupted
        }

        private fun consumeRetiredTask(
            future: Future<Result<Boolean>>,
            taskFailure: Throwable,
            primary: Throwable?,
        ): Throwable? =
            runCatching { future.get().exceptionOrNull() }.fold(
                onSuccess = { if (it == null || it === taskFailure) primary else CleanupFailures.merge(primary, it) },
                onFailure = { CleanupFailures.merge(primary, it) },
            )

        private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(1)
    }

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
