package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Kernel restrictions run exclusively in a disposable child, never the test worker. */
class PreparedOperationNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `actual reviewed implementation owns exact recipes through physical retirement`() = withFixture("verifyBoundOperation")

    @Test
    @EnabledIfLinuxAndSupported
    fun `drain deadline retains genuine descriptor hierarchy behind external validation monitor`() = withFixture("verifyValidationDeadline", "owner")

    @Test
    @EnabledIfLinuxAndSupported
    fun `drain deadline retains genuine descriptor hierarchy behind blocked manager validation`() = withFixture("verifyValidationDeadline", "manager")

    @Test
    @EnabledIfLinuxAndSupported
    fun `blocked real descriptor close reports bounded incomplete and retains fatal cleanup outcome`() = withFixture("verifyBlockedFatalClose")

    @Test
    @EnabledIfLinuxAndSupported
    fun `late ordinary owner failure between drain samples never closes runtime or hides later failure`() = withFixture("verifyLateFailure", "ordinary")

    @Test
    @EnabledIfLinuxAndSupported
    fun `late fatal owner failure between drain samples never closes runtime or hides later failure`() = withFixture("verifyLateFailure", "fatal")

    @Test
    @EnabledIfLinuxAndSupported
    fun `all required worker installation permission failures retire genuine resource and ruleset owners`() = withFixture("verifyAllWorkerFailures")

    private fun withFixture(
        method: String,
        vararg arguments: String,
    ) {
        val root = Files.createTempDirectory("bound-operation-")
        try {
            val source = Files.writeString(root.resolve("Operation.java"), "package fixture; public class Operation { public int call() { return 42; } }")
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()))
            val jar = root.resolve("operation.jar")
            JarOutputStream(Files.newOutputStream(jar)).use {
                it.putNextEntry(JarEntry("fixture/Operation.class"))
                it.write(Files.readAllBytes(root.resolve("fixture/Operation.class")))
                it.closeEntry()
            }
            Files.writeString(root.resolve("template.txt"), "template")
            Files.writeString(root.resolve("private.txt"), "private")
            Files.createDirectory(root.resolve("output"))
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, method, root.toString(), *arguments)
        } finally {
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    fun verifyValidationDeadline(
        rootName: String,
        mode: String,
    ) {
        val root = Path.of(rootName)
        val jar = root.resolve("operation.jar")
        val timeout = Duration.ofSeconds(5)
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), setOf(jar)).use { resources ->
                val binding = OperationBinding("deadline-operation", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
                val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("review", document.sha256(), emptySet()), binding)
                ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
                    val domain = runtime.prepare(reviewed, DomainLimits(2, 2), resources)
                    val descriptors = resources.resources.grants.map { it.path.descriptor }
                    val rulesets = liveRulesetPaths()
                    assertTrue(rulesets.isNotEmpty(), "Actual owned Landlock ruleset must remain open")
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val actionEntered = AtomicBoolean()
                    // Deterministic stand-in for non-interruptible I/O while the real validation
                    // monitor is owned. Actual validation and real acquisitions precede the stall.
                    val monitor = if (mode == "owner") resources else resources.resources
                    val validator = Thread.ofPlatform().name("review-validation-stall").start {
                        synchronized(monitor) {
                            if (mode == "owner") resources.validate() else resources.resources.validate()
                            entered.countDown()
                            release.await()
                        }
                    }
                    var submitter: Thread? = null
                    var drainer: Thread? = null
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        if (mode == "manager") {
                            submitter = Thread.ofPlatform().name("review-validation-submit").start {
                                runCatching { runtime.submit(domain, Callable { actionEntered.set(true) }) }
                            }
                            awaitCondition(Duration.ofSeconds(2), "manager physically blocked by resource validation") {
                                Thread.getAllStackTraces().keys.any { it.name.startsWith("mazewall-manager-") && it.state == Thread.State.BLOCKED }
                            }
                        }
                        val result = FutureTask(Callable { runtime.drain(Duration.ofMillis(50)) })
                        drainer = Thread.ofPlatform().name("review-deadline-drain").start(result)
                        assertIs<DrainReport.Incomplete>(result.get(1, TimeUnit.SECONDS))
                        assertFalse(domain.resource.retired(), "Validation-dependent cleanup is not physical retirement")
                        assertTrue(descriptors.all { it.isLiveForIo() }, "Never close live validation's genuine resource descriptors")
                        assertTrue(rulesets.all { Files.exists(it) }, "Retain ruleset ownership with the validation-dependent generation")
                        assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(10)))
                    } finally {
                        release.countDown()
                        validator.join(3000)
                        submitter?.join(3000)
                        drainer?.join(3000)
                    }
                    assertEquals(DrainReport.Complete, runtime.drain(timeout))
                    assertTrue(descriptors.none { it.isLiveForIo() })
                    assertTrue(rulesets.none { Files.exists(it) && Files.readSymbolicLink(it).toString().contains("landlock") })
                    assertFalse(actionEntered.get())
                    assertFailsWith<IllegalStateException> { resources.validate() }
                }
            }
        }
    }

    private fun liveRulesetPaths(): List<Path> =
        Files.list(Path.of("/proc/self/fd")).use { paths ->
        paths.filter { Files.exists(it) && Files.readSymbolicLink(it).toString().contains("landlock") }.toList()
    }

    fun verifyBlockedFatalClose(rootName: String) = verifyCloseOutcome(rootName, false, true)

    fun verifyLateFailure(
        rootName: String,
        kind: String,
    ) = verifyCloseOutcome(rootName, true, kind == "fatal")

    private fun verifyCloseOutcome(
        rootName: String,
        lateCompletion: Boolean,
        fatalFailure: Boolean,
    ) {
        val jar = Path.of(rootName).resolve("operation.jar")
        val real = LinuxNative.snapshot()
        val target = AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>?>()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fatal: Throwable = if (fatalFailure) AssertionError("actual close finished with fatal outcome") else IllegalStateException("actual close finished with ordinary failure")
        val uncaught = AtomicReference<Throwable?>()
        LinuxNative.setEngine(object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd == target.get()) {
                        closing.countDown()
                        release.await()
                        real.fileSystem.close(fd).getOrThrow("actual fixture close")
                        throw fatal
                    }
                    return real.fileSystem.close(fd)
                }
            }
        })
        val timeout = Duration.ofSeconds(5)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet())
                val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
                try {
                    val domain = runtime.prepare(review(resources, policy), DomainLimits(2, 2), resources)
                    val descriptors = resources.resources.grants.map { it.path.descriptor }
                    target.set(
                        resources.resources.grants
                        .first { it.path.requested == jar.toAbsolutePath() }
                        .path.descriptor,
                    )
                    val rulesets = liveRulesetPaths()
                    val cleanupOwner = Thread.getAllStackTraces().keys.single { it.name.endsWith("resource-retirement") }
                    cleanupOwner.uncaughtExceptionHandler =
                        Thread.UncaughtExceptionHandler { _, failure -> uncaught.set(failure) }
                    if (lateCompletion) {
                        // Wrap the existing real worker references only. The test barrier pauses
                        // diagnostic sampling, reproducing CPU suspension after the old null read.
                        // No production hook, fake worker, invented FD or release substitution.
                        val field = domain.resource.javaClass
                            .getDeclaredField("threads")
                            .apply { isAccessible = true }

                        @Suppress("UNCHECKED_CAST")
                        val actual = field.get(domain.resource) as CopyOnWriteArrayList<Thread>
                        val observed = AtomicBoolean()
                        field.set(
                            domain.resource,
                            LateDrainMetricThreads(actual) {
                            assertTrue(closing.await(2, TimeUnit.SECONDS))
                            observed.set(true)
                            release.countDown()
                            cleanupOwner.join(3000)
                            assertFalse(cleanupOwner.isAlive, "Late failing genuine cleanup must physically end before metrics resume")
                        },
                        )
                        val first = runtime.drain(Duration.ofMillis(50))
                        val later = runtime.drain(Duration.ZERO)
                        val closeFailure = runCatching { runtime.close() }.exceptionOrNull()
                        assertTrue(observed.get(), "Force completion between historical failure and physical samples")
                        assertTrue(
                            first is DrainReport.Incomplete && later is DrainReport.Incomplete && later.cleanupFailure === fatal && closeFailure?.cause === fatal,
                            "Never hide late owner failure: first=$first later=$later close=$closeFailure",
                        )
                        assertTrue(descriptors.none { it.isLiveForIo() })
                        assertTrue(rulesets.none { Files.exists(it) && Files.readSymbolicLink(it).toString().contains("landlock") })
                        if (fatalFailure) assertTrue(uncaught.get() === fatal)
                        return
                    }
                    val drain = FutureTask(Callable { runtime.drain(Duration.ofMillis(50)) })
                    val drainer = Thread.ofPlatform().start(drain)
                    try {
                        assertTrue(closing.await(2, TimeUnit.SECONDS))
                        assertIs<DrainReport.Incomplete>(drain.get(1, TimeUnit.SECONDS))
                        val acquiredDescriptor = assertNotNull(target.get(), "Blocked genuine close must have its acquired descriptor")
                        assertTrue(acquiredDescriptor.isLiveForIo(), "Blocked genuine close must retain its acquisition")
                        assertTrue(rulesets.all { Files.exists(it) })
                    } finally {
                        release.countDown()
                        drainer.join(3000)
                    }
                    val outcome = assertIs<DrainReport.Incomplete>(runtime.drain(timeout))
                    assertTrue(outcome.cleanupFailure === fatal)
                    assertTrue(uncaught.get() === fatal)
                    assertTrue(descriptors.none { it.isLiveForIo() })
                    assertTrue(rulesets.none { Files.exists(it) && Files.readSymbolicLink(it).toString().contains("landlock") })
                    val failure = assertFailsWith<io.mazewall.enforcer.api.execution.IncompleteDrainException> { runtime.close() }
                    assertTrue(failure.cause === fatal)
                } finally {
                    release.countDown()
                    resources.close()
                    runtime.drain(timeout)
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    private class LateDrainMetricThreads(
        actual: CopyOnWriteArrayList<Thread>,
        private val atMetric: () -> Unit,
    ) : CopyOnWriteArrayList<Thread>(actual) {
        private val armed = AtomicBoolean(true)

        override fun iterator(): MutableIterator<Thread> {
            if (Thread.currentThread().stackTrace.any { it.className == "io.mazewall.enforcer.internal.execution.OwnedDomain" && it.methodName == "liveWorkers" } &&
                armed.compareAndSet(true, false)
            ) {
                    atMetric()
                }
            return super.iterator()
        }
    }

    fun verifyAllWorkerFailures(rootName: String) {
        val jar = Path.of(rootName).resolve("operation.jar")
        val real = LinuxNative.snapshot()
        val attempts = AtomicInteger()
        LinuxNative.setEngine(object : NativeEngine by real {
            override val raw = object : RawSyscallOperations by real.raw {
                override fun syscall(
                    nr: Long,
                    a1: NativeArg,
                    a2: NativeArg,
                    a3: NativeArg,
                    a4: NativeArg,
                    a5: NativeArg,
                    a6: NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (nr == NativeConstants.LANDLOCK_RESTRICT_SELF_NR && Thread.currentThread().name.startsWith("mazewall-domain-")) {
                        if (attempts.incrementAndGet() == 1) {
                            // The canonical installer serializes Landlock under processLock.
                            // Wait for physical worker creation, not a second syscall behind that lock.
                            awaitCondition(Duration.ofSeconds(2), "every required physical worker started before first fault") {
                                Thread.getAllStackTraces().keys.count { it.name.startsWith("mazewall-domain-") && !it.name.endsWith("resource-retirement") } == 2
                            }
                        }
                        return LinuxNative.SyscallResult.Error(1, -1L)
                    }
                    return real.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
        })
        val timeout = Duration.ofSeconds(5)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resources ->
                    val descriptors = resources.resources.grants.map { it.path.descriptor }
                    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
                        val failure = assertFailsWith<IllegalStateException> { runtime.prepare(review(resources, policy), DomainLimits(2, 2), resources) }
                        assertTrue(
                            generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("errno=1") == true },
                            "Permission rejection must retain errno in original cause chain: $failure",
                        )
                        assertEquals(2, attempts.get())
                        assertEquals(DrainReport.Complete, runtime.drain(timeout))
                        assertTrue(descriptors.none { it.isLiveForIo() })
                        assertTrue(liveRulesetPaths().isEmpty())
                        assertFailsWith<IllegalStateException> { resources.validate() }
                    }
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    private fun review(
        resources: PreparedOperationResources,
        policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
    ): ReviewedOperationArtifact {
        val binding = OperationBinding("reviewed-operation", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
        val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
        return ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("external-review", document.sha256(), emptySet()), binding)
    }

    fun verifyBoundOperation(rootName: String) {
        val root = Path.of(rootName)
        val jar = root.resolve("operation.jar")
        val template = root.resolve("template.txt")
        val output = root.resolve("output")
        val timeout = Duration.ofSeconds(10)
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("fixture.Operation")
            val target = selected.getConstructor().newInstance()
            val call = selected.getMethod("call")
            val policy = WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(jar, template)
                .allowFsReadWrite(output)
                .build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, setOf(template)).use { resources ->
                val binding = OperationBinding("actual-operation", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
                val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                val review = TrustedPolicyReview("external-test-review", document.sha256(), emptySet())
                val artifact = ReviewedOperationArtifact.load(document.toJson(), review, binding)
                FileChannel.open(template, StandardOpenOption.WRITE).use { preopened ->
                    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
                        val domain = runtime.prepare(artifact, DomainLimits(2, 2), resources)
                        resources.close() // ownership transfer: cannot revoke a live generation
                        assertEquals(
                            42,
                            Mazewall.callContained(
                                runtime,
                                domain,
                                Callable {
                            assertEquals("template", Files.readString(template))
                            assertFailsWith<AccessDeniedException> { Files.readString(root.resolve("private.txt")) }
                            assertFailsWith<AccessDeniedException> { Files.writeString(template, "forbidden") }
                            assertFailsWith<AccessDeniedException> { Files.delete(template) }
                            assertFailsWith<AccessDeniedException> { Files.move(template, output.resolve("template-moved.txt")) }
                            val first = Files.createDirectory(output.resolve("first"))
                            val second = Files.createDirectory(output.resolve("second"))
                            val report = Files.writeString(first.resolve("report.txt"), "created-report")
                            Files.writeString(report, "truncated")
                            Files.move(report, second.resolve("report.txt"))
                            Files.delete(second.resolve("report.txt"))
                            Files.delete(first)
                            Files.delete(second)
                            call.invoke(target)
                        },
                            ),
                        )
                        assertEquals(8, Mazewall.callContained(runtime, domain, Callable { preopened.write(ByteBuffer.wrap("mutated!".toByteArray())) }))
                        // Landlock does not revoke preopened write capability; snapshot validation detects its effect.
                        assertEquals("mutated!", Files.readString(template))
                        assertFailsWith<IllegalStateException> { runtime.submit(domain, Callable { error("must not enter stale generation") }) }
                    }
                }
                assertFailsWith<IllegalStateException> { resources.validate() }
            }
        }
    }
}
