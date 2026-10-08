package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactClasspathGrantNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `classpath artifact is readable but its sibling remains denied`() {
        val directory = Files.createTempDirectory("exact-classpath-")
        val artifact = directory.resolve("parser.jar")
        val sibling = directory.resolve("private.txt")
        try {
            JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
                jar.putNextEntry(JarEntry("resource.txt"))
                jar.write("public-resource".toByteArray())
                jar.closeEntry()
            }
            Files.writeString(sibling, "private-sibling")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyExactArtifactGrant", artifact.toString(), sibling.toString())
        } finally {
            Files.deleteIfExists(sibling)
            Files.deleteIfExists(artifact)
            Files.deleteIfExists(directory)
        }
    }

    fun verifyExactArtifactGrant(
        artifact: String,
        sibling: String,
    ) {
        System.setProperty("io.mazewall.fallback", "FAIL")
        System.setProperty("java.class.path", System.getProperty("java.class.path") + File.pathSeparator + artifact)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        Executors.newSingleThreadExecutor().use { worker ->
            val contained = ContainedExecutors.wrap(worker, policy)
            assertEquals(
                42,
                contained
                    .submit(
                    Callable {
                assertTrue(Files.readAllBytes(Path.of(artifact)).isNotEmpty())
                assertFailsWith<AccessDeniedException> { Files.readString(Path.of(sibling)) }
                42
            },
                ).get(10, TimeUnit.SECONDS),
            )
        }
        assertEquals("private-sibling", Files.readString(Path.of(sibling)))
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `prepared owned workers grant an exact artifact and reject replacement`() {
        val directory = Files.createTempDirectory("prepared-classpath-")
        try {
            val artifact = Files.writeString(directory.resolve("parser.jar"), "reviewed-bytes")
            val sibling = Files.writeString(directory.resolve("private.txt"), "private-sibling")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyPreparedArtifactGrant", artifact.toString(), sibling.toString())
        } finally {
            Files.walk(directory).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    fun verifyPreparedArtifactGrant(
        artifact: String,
        sibling: String,
    ) {
        val path = Path.of(artifact)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val timeout = Duration.ofSeconds(10)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(policy, DomainLimits(2, 2))
            repeat(2) {
                assertEquals(
                    "reviewed-bytes",
                    Mazewall.callContained(
                        runtime,
                        domain,
                        Callable {
                    assertFailsWith<AccessDeniedException> { Files.readString(Path.of(sibling)) }
                    Files.readString(path)
                },
                    ),
                )
            }
            Files.move(path, path.resolveSibling("old.jar"))
            Files.writeString(path, "replacement")
            var entered = false
            assertFailsWith<IllegalStateException> { runtime.submit(domain, Callable { entered = true }) }
            assertTrue(!entered)
        }
    }
}
