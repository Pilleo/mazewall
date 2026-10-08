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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
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
import kotlin.test.assertTrue

/** Native restrictions belong only to disposable actual packaging children. */
class DeploymentOriginNativeTest : BaseIntegrationTest() {
    @TempDir lateinit var directory: Path

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
        val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
        val binding = OperationBinding("deployment-native", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
        val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("external-fixture-review", document.sha256(), emptySet()), binding)
        val target = selected.getConstructor().newInstance()
        val call = selected.getMethod("call")
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val timeout = Duration.ofSeconds(10)
        val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
        try {
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
        } finally {
            release.countDown()
            runtime.close()
            resources.close()
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
