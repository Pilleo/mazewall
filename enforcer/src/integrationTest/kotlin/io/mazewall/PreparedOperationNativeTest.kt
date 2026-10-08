package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
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
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Kernel restrictions run exclusively in a disposable child, never the test worker. */
class PreparedOperationNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `actual reviewed implementation owns exact recipes through physical retirement`() {
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
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyBoundOperation", root.toString())
        } finally {
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
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
