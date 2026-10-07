package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
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
}
