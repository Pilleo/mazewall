package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.TrustedPolicyReview
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.CleanupFailures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Native restrictions belong only to disposable actual packaging children. */
class DeploymentOriginNativeTest : BaseIntegrationTest() {
    @TempDir var injectedDirectory: Path? = null
    private val directory: Path get() = requireNotNull(injectedDirectory) { "JUnit temporary directory was not injected" }

    @Test
    @EnabledIfLinuxAndSupported
    fun `executable Boot application workers deny sibling and retain real owners through incomplete drain`() = boot(false)

    @Test
    @EnabledIfLinuxAndSupported
    fun `executable Boot nested library workers deny sibling and physically retire real owners`() = boot(true)

    @Test
    @EnabledIfLinuxAndSupported
    fun `exploded workers use explicit directory closure and physically retire all genuine descendants`() {
        val root = DeploymentPackagingFixture.compile(directory)
        Files.writeString(directory.resolve("private.txt"), "secret")
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyExploded", root.toString())
    }

    private fun boot(library: Boolean) {
        val jar = DeploymentPackagingFixture.boot(directory, library)
        Files.writeString(directory.resolve("private.txt"), "secret")
        assertTrue(DeploymentPackagingFixture.launch(jar, "native", jar.toString(), probeClass = DeploymentNativeProbe::class.java.name).contains("NATIVE_RETIRED"))
    }

    @Test
    fun `F06 boundary retains ordinary primary identity under late fatal cleanup`() {
        val owner = FileChannel.open(Files.writeString(directory.resolve("fatal-owner"), "owned"))
        val primary = IllegalStateException("body evidence")
        val fatal = AssertionError("late cleanup evidence")
        try {
            val observed = assertFailsWith<AssertionError> {
                DeploymentFixtureBoundary.run {
                    own {
                        owner.close()
                        throw fatal
                    }
                    throw primary
                }
            }
            assertSame(fatal, observed)
            assertTrue(observed.suppressed.any { it === primary }, "Exact body evidence must survive fatal promotion")
            assertTrue(!owner.isOpen, "The genuine acquisition must close")
        } finally {
            owner.close()
        }
    }

    @Test
    fun `F06 boundary retains original fatal identity through ordinary cleanup and all later owners`() {
        // Nested use owns acquisition rollback and unconditional rescue independently of the boundary.
        FileChannel.open(Files.writeString(directory.resolve("primary-fatal-owner-1"), "owned")).use { first ->
            FileChannel.open(Files.writeString(directory.resolve("primary-fatal-owner-2"), "owned")).use { second ->
                FileChannel.open(Files.writeString(directory.resolve("primary-fatal-owner-3"), "owned")).use { third ->
                    val owners = listOf(first, second, third)
                    val primary = AssertionError("original fatal body evidence")
                    val cleanup = IllegalArgumentException("ordinary cleanup evidence")
                    val attempts = mutableListOf<Int>()
                    val observed = assertFailsWith<AssertionError> {
                        DeploymentFixtureBoundary.run {
                            owners.forEachIndexed { index, owner ->
                                own {
                                    attempts.add(index)
                                    owner.close()
                                    if (index == 2) throw cleanup
                                }
                            }
                            throw primary
                        }
                    }
                    assertSame(primary, observed, "Ordinary cleanup must not replace the original fatal")
                    assertEquals(1, observed.suppressed.size, "Exactly the ordinary cleanup evidence must be retained")
                    assertSame(cleanup, observed.suppressed.single())
                    assertEquals(listOf(2, 1, 0), attempts, "Every later acquired owner must receive cleanup")
                    assertTrue(owners.none { it.isOpen }, "All acquired owners must physically close before rescue")
                }
            }
        }
    }

    @Test
    fun `F06 boundary attempts all later owners after early cleanup failure`() {
        val owners = (1..3).map { FileChannel.open(Files.writeString(directory.resolve("owner-$it"), "owned")) }
        val primary = IllegalStateException("body evidence")
        val cleanup = IllegalArgumentException("first owner cleanup evidence")
        val attempts = mutableListOf<Int>()
        try {
            val observed = assertFailsWith<IllegalStateException> {
                DeploymentFixtureBoundary.run {
                    // Registration is reverse acquisition order, as in the actual fixture.
                    owners.forEachIndexed { index, owner ->
                        own {
                            attempts.add(index)
                            owner.close()
                            if (index == 2) throw cleanup
                        }
                    }
                    throw primary
                }
            }
            assertSame(primary, observed)
            assertTrue(observed.suppressed.any { it === cleanup })
            assertEquals(listOf(2, 1, 0), attempts, "Every later owned cleanup must be attempted")
            assertTrue(owners.none { it.isOpen }, "All genuine acquisitions must close")
        } finally {
            owners.forEach { it.close() }
        }
    }

    @Test
    fun `F06 boundary defers interruption until physical worker and owner retirement`() {
        val owner = FileChannel.open(Files.writeString(directory.resolve("worker-owner"), "owned"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primary = IllegalStateException("body evidence with physically live worker")
        val interrupted = InterruptedException("early cleanup interruption")
        val incomplete = IllegalStateException("uncontained owner is not yet physically retired")
        val attempts = mutableListOf<String>()
        // Uncontained platform worker: no fabricated installation receipt, FD, or PID.
        val worker = Thread.ofPlatform().unstarted {
            try {
                entered.countDown()
                release.await()
            } finally {
                owner.close()
            }
        }
        // Reserve half of the enclosing 120-second test budget for unconditional rescue.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)

        fun retire() {
            release.countDown()
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining)
            assertTrue(!worker.isAlive, "Physical worker retirement is required, not cancellation")
        }
        try {
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Actual worker must enter")
            val observed = assertFailsWith<IllegalStateException> {
                DeploymentFixtureBoundary.run {
                    own {
                        attempts.add("physical-retirement")
                        assertTrue(!Thread.currentThread().isInterrupted, "Interrupt restoration must wait for physical cleanup")
                        retire()
                        assertTrue(!owner.isOpen, "Worker-owned acquisition closes only on physical exit")
                    }
                    own {
                        attempts.add("incomplete-owner")
                        assertTrue(worker.isAlive, "Incomplete cleanup evidence requires the actual live worker")
                        assertTrue(owner.isOpen, "A physically live worker must still own its acquisition")
                        throw incomplete
                    }
                    own {
                        attempts.add("interruption")
                        Thread.currentThread().interrupt()
                        throw interrupted
                    }
                    throw primary
                }
            }
            assertSame(primary, observed)
            assertTrue(observed.suppressed.any { it === interrupted })
            assertTrue(observed.suppressed.any { it === incomplete })
            assertEquals(listOf("interruption", "incomplete-owner", "physical-retirement"), attempts)
            assertTrue(!worker.isAlive)
            assertTrue(!owner.isOpen)
            assertTrue(Thread.currentThread().isInterrupted, "Deferred interrupt must be restored")
        } finally {
            val restore = Thread.interrupted()
            try {
                release.countDown()
                // Rescue does not count as success of the boundary under test.
                val remaining = deadline + TimeUnit.SECONDS.toNanos(50) - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining)
                assertTrue(!worker.isAlive, "Control cannot escape a physically live worker")
                owner.close()
            } finally {
                if (restore) Thread.currentThread().interrupt()
            }
        }
    }

    fun verifyExploded(rootName: String) {
        val root = Path.of(rootName)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            DeploymentNativeProbe.run(loader.loadClass("fixture.Operation"), arrayOf("native", rootName))
        }
    }
}

object DeploymentNativeProbe {
    @JvmStatic
    fun run(
        selected: Class<*>,
        arguments: Array<String>,
    ) {
        val deployment = Path.of(arguments[1])
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(deployment)
        // The probe's external ordinary loader is fixture infrastructure, not implicit parent widening.
        System
            .getProperty("task8.probe.classpath", "")
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .forEach { builder.allowFsRead(Path.of(it)) }
        val policy = builder.build()
        val release = CountDownLatch(1)
        DeploymentFixtureBoundary.run(release = { release.countDown() }) {
            val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
            own { resources.close() }
            val binding = OperationBinding("deployment-native", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
            val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
            val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("external-fixture-review", document.sha256(), emptySet()), binding)
            val target = selected.getConstructor().newInstance()
            val call = selected.getMethod("call")
            val entered = CountDownLatch(2)
            val timeout = Duration.ofSeconds(10)
            val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
            own { runtime.close() }
            val domain = runtime.prepare(reviewed, DomainLimits(2, 2), resources)
            val descriptors = opathOwners(deployment)
            assertTrue(descriptors.size >= if (Files.isDirectory(deployment)) 5 else 2, "Real deployment acquisitions must be retained")
            val rulesets = rulesetOwners()
            assertTrue(rulesets.isNotEmpty(), "Real prepared Landlock ruleset must be owned")
            resources.close() // successful transfer cannot revoke physically live worker resources
            val handles = (1..2).map {
                runtime.submit(
                    domain,
                    Callable {
                    assertEquals(42, call.invoke(target))
                    assertFailsWith<AccessDeniedException> { Files.readString(deployment.parent.resolve("private.txt")) }
                    val resource = checkNotNull(selected.classLoader.getResourceAsStream("application.properties")).use { it.readAllBytes().toString(Charsets.UTF_8) }
                    assertEquals("fixture=reviewed\n", resource)
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS), "Fixture must release actual workers")
                    42
                },
                )
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Both actual installed workers must execute")
            assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(20)))
            (descriptors + rulesets).forEach { assertTrue(Files.exists(it), "Incomplete drain cannot discard genuine owner: $it") }
            release.countDown()
            handles.forEach { assertEquals(42, it.result().toCompletableFuture().get(10, TimeUnit.SECONDS)) }
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            (descriptors + rulesets).forEach { assertTrue(!Files.exists(it), "Physical retirement must close actual owner: $it") }
            assertFailsWith<IllegalStateException> { resources.validate() }
            println("NATIVE_RETIRED")
        }
    }

    private fun opathOwners(deployment: Path): List<Path> =
        DeploymentOwnerAssertions.pathsBelow(deployment.toRealPath()).filter { path ->
        val flags = Files
            .readAllLines(Path.of("/proc/self/fdinfo", path.fileName.toString()))
            .single { it.startsWith("flags:") }
            .substringAfter(':')
            .trim()
            .toLong(8)
        flags and NativeConstants.O_PATH.toLong() != 0L
    }

    private fun rulesetOwners(): List<Path> =
        Files.list(Path.of("/proc/self/fd")).use { paths ->
        paths.filter { Files.exists(it) && Files.readSymbolicLink(it).toString().contains("landlock") }.toList()
    }
}

/** Actual native-fixture boundary: retain all failures and defer interruption through owned cleanup. */
private class DeploymentFixtureBoundary {
    private val cleanup = mutableListOf<() -> Unit>()

    fun own(close: () -> Unit) {
        cleanup.add(0, close)
    }

    companion object {
        fun <T> run(
            release: () -> Unit = {},
            body: DeploymentFixtureBoundary.() -> T,
        ): T {
            val boundary = DeploymentFixtureBoundary()
            val result = runCatching { boundary.body() }
            var failure = result.exceptionOrNull()
            var interrupted = failure is InterruptedException
            try {
                interrupted = Thread.interrupted() || interrupted

                fun attempt(close: () -> Unit) {
                    try {
                        runCatching { close() }.exceptionOrNull()?.let { problem ->
                            failure = CleanupFailures.merge(failure, problem)
                            if (problem is InterruptedException) interrupted = true
                        }
                    } finally {
                        // A close may restore its flag before throwing. Later physical joins
                        // must run with it cleared; restore only after every owned attempt.
                        interrupted = Thread.interrupted() || interrupted
                    }
                }
                attempt(release)
                boundary.cleanup.forEach { attempt(it) }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            failure?.let { throw it }
            return result.getOrThrow()
        }
    }
}
