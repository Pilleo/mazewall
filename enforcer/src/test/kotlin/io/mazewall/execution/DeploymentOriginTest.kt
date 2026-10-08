package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeploymentOriginTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `exploded complete tree bytes and required entry identity are retained`() {
        val root = DeploymentPackagingFixture.compile(directory)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet())
            try {
                resources.validate()
                val generation = resources.resourceGeneration
                Files.writeString(root.resolve("application.properties"), "fixture=changed\n")
                assertFailsWith<IllegalStateException> { resources.validate() }
                assertEquals(generation, resources.resourceGeneration)
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { replaced ->
                    assertNotEquals(resources.implementationSha256, replaced.implementationSha256)
                }
            } finally {
                resources.close()
            }
            assertFailsWith<IllegalStateException> { resources.validate() }
        }
    }

    @Test
    fun `exploded selected file replacement with identical bytes rejects`() {
        val root = DeploymentPackagingFixture.compile(directory)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resources ->
                val selected = root.resolve("fixture/Operation.class")
                val bytes = Files.readAllBytes(selected)
                Files.move(selected, directory.resolve("retired.class"))
                Files.write(selected, bytes)
                assertFailsWith<IllegalStateException> { resources.validate() }
            }
        }
    }

    @Test
    fun `exploded descendant symlinks reject instead of widening root closure`() {
        val root = DeploymentPackagingFixture.compile(directory)
        Files.createSymbolicLink(root.resolve("escape"), Files.writeString(directory.resolve("secret.txt"), "secret"))
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()) }
        }
    }

    @Test
    fun `exploded file only grants cannot silently authorize implementation root`() {
        val root = DeploymentPackagingFixture.compile(directory)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root.resolve("fixture/Operation.class")).build()
            assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()) }
        }
    }

    @Test
    fun `exploded added required resource invalidates complete tree snapshot`() {
        val root = DeploymentPackagingFixture.compile(directory)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resources ->
                Files.writeString(root.resolve("new.properties"), "added")
                assertFailsWith<IllegalStateException> { resources.validate() }
            }
        }
    }

    @Test
    fun `exploded removed resource invalidates complete tree snapshot`() {
        val root = DeploymentPackagingFixture.compile(directory)
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resources ->
                Files.delete(root.resolve("application.properties"))
                assertFailsWith<IllegalStateException> { resources.validate() }
            }
        }
    }

    @Test
    fun `exploded root symlink switch rejects and all real deployment descriptors retire`() {
        val root = DeploymentPackagingFixture.compile(directory)
        val alias = Files.createSymbolicLink(directory.resolve("current"), root)
        URLClassLoader(arrayOf(alias.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(alias).build()
            val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet())
            val owners = DeploymentOwnerAssertions.pathsBelow(root)
            assertTrue(owners.size >= 5, "Actual root, directory and leaf descriptors must be owned")
            Files.delete(alias)
            Files.createSymbolicLink(alias, Files.createDirectory(directory.resolve("replacement")))
            assertFailsWith<IllegalStateException> { resources.validate() }
            resources.close()
            owners.forEach { assertTrue(!Files.exists(it), "Actual descriptor must retire: $it") }
        }
    }

    @Test
    fun `exploded oversized sparse resource rejects and rolls back genuine owners`() {
        val root = DeploymentPackagingFixture.compile(directory)
        FileChannel.open(root.resolve("oversized"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.position(256L * 1024 * 1024)
            it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
            assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()) }
            assertTrue(DeploymentOwnerAssertions.pathsBelow(root).isEmpty(), "Failed capture must close all actual deployment owners")
        }
    }

    @Test
    fun `oversized ordinary artifact rejects before parsing unbounded jar input`() {
        val root = DeploymentPackagingFixture.compile(directory)
        val jar = directory.resolve("ordinary.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(java.util.jar.JarEntry("fixture/Operation.class"))
            output.write(Files.readAllBytes(root.resolve("fixture/Operation.class")))
            output.closeEntry()
        }
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("fixture.Operation")
            FileChannel.open(jar, StandardOpenOption.WRITE).use {
                it.position(256L * 1024 * 1024)
                it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
            }
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            val failure = assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()) }
            assertTrue(checkNotNull(failure.message).contains("bounded identity budget"))
        }
    }

    @Test
    fun `real executable Boot application class uses actual finished outer jar`() {
        val jar = DeploymentPackagingFixture.boot(directory, false)
        assertTrue(DeploymentPackagingFixture.launch(jar, "resolve", jar.toString()).contains("RESOLVED"))
    }

    @Test
    fun `real executable Boot nested library binds outer artifact and selected entry`() {
        val jar = DeploymentPackagingFixture.boot(directory, true)
        assertTrue(DeploymentPackagingFixture.launch(jar, "resolve", jar.toString()).contains("RESOLVED"))
    }

    @Test
    fun `real Boot outer content mutation invalidates snapshot`() {
        val jar = DeploymentPackagingFixture.boot(directory, false)
        assertTrue(DeploymentPackagingFixture.launch(jar, "mutate", jar.toString()).contains("REJECTED_MUTATION"))
    }

    @Test
    fun `real Boot nested library outer replacement with identical bytes rejects`() {
        val jar = DeploymentPackagingFixture.boot(directory, true)
        assertTrue(DeploymentPackagingFixture.launch(jar, "replace", jar.toString()).contains("REJECTED_REPLACEMENT"))
    }

    @Test
    fun `real Boot outer symlink switch rejects`() {
        val jar = DeploymentPackagingFixture.boot(directory, true)
        val alias = Files.createSymbolicLink(directory.resolve("current.jar"), jar)
        assertTrue(DeploymentPackagingFixture.launch(alias, "symlink", alias.toString()).contains("REJECTED_SYMLINK"))
    }

    @Test
    fun `real Boot origins preserve URI escaped deployment path`() {
        val root = Files.createDirectory(directory.resolve("with spaces"))
        val jar = DeploymentPackagingFixture.boot(root, false)
        assertTrue(DeploymentPackagingFixture.launch(jar, "resolve", jar.toString()).contains("RESOLVED"))
    }

    @Test
    fun `real Boot same named executable loader with modified class bytes rejects`() {
        val jar = DeploymentPackagingFixture.boot(directory, false, true)
        assertTrue(DeploymentPackagingFixture.launch(jar, "reject-loader", jar.toString()).contains("REJECTED_LOADER"))
    }

    @Test
    fun `real Boot nested archive traversal entry rejects despite executable selected class`() {
        val jar = DeploymentPackagingFixture.boot(directory, true, nestedMutation = "traversal")
        assertTrue(DeploymentPackagingFixture.launch(jar, "reject-traversal", jar.toString()).contains("REJECTED_ENTRY"))
    }

    @Test
    fun `real Boot duplicate nested class entry rejects despite matching selected bytes`() {
        val jar = DeploymentPackagingFixture.boot(directory, true, nestedMutation = "duplicate")
        assertTrue(DeploymentPackagingFixture.launch(jar, "reject-duplicate", jar.toString()).contains("REJECTED_ENTRY"))
    }

    @Test
    fun `real Boot inherited method from different nested artifact cannot use outer digest alone`() {
        val jar = DeploymentPackagingFixture.boot(directory, false, inherited = true)
        assertTrue(DeploymentPackagingFixture.launch(jar, "reject-member", jar.toString()).contains("REJECTED_MEMBER"))
    }

    @Test
    fun `real Boot central only duplicate resource names reject despite unique local names`() = rejectCentral("central-duplicate")

    @Test
    fun `real Boot central only traversal resource name rejects despite legal local names`() = rejectCentral("central-traversal")

    @Test
    fun `real Boot central local resource name inconsistency rejects`() = rejectCentral("central-name")

    @Test
    fun `real Boot central entries cannot alias another local header offset`() = rejectCentral("central-offset")

    @Test
    fun `real Boot central CRC cannot disagree with valid local content CRC`() = rejectCentral("central-crc")

    @Test
    fun `real Boot central size cannot disagree with local data descriptor`() = rejectCentral("central-size")

    @Test
    fun `real Boot central declared expansion cannot exceed bounded byte budget`() = rejectCentral("central-budget")

    @Test
    fun `real Boot central count cannot hide an additional record`() = rejectCentral("central-count")

    private fun rejectCentral(mutation: String) {
        val jar = DeploymentPackagingFixture.boot(directory, true, nestedMutation = mutation)
        assertTrue(DeploymentPackagingFixture.launch(jar, mutation, jar.toString()).contains("REJECTED_CENTRAL"))
    }
}

/** Invoked through real Boot startup, with actual defining loader and selected Class. */
object DeploymentOriginProbe {
    @JvmStatic
    fun run(
        selected: Class<*>,
        arguments: Array<String>,
    ) {
        val jar = Path.of(arguments[1])
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
        if (arguments[0].startsWith("central-")) {
            assertEquals(42, selected.getMethod("call").invoke(selected.getConstructor().newInstance()))
            assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()) }
            println("REJECTED_CENTRAL")
            return
        }
        if (arguments[0] == "reject-loader") {
            val failure = assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()) }
            assertTrue(checkNotNull(failure.message).contains("Unsupported Boot loader implementation bytes"))
            println("REJECTED_LOADER")
            return
        }
        if (arguments[0] == "reject-traversal" || arguments[0] == "reject-duplicate") {
            val failure = assertFailsWith<IllegalArgumentException> { PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()) }
            val message = checkNotNull(failure.message)
            assertTrue(if (arguments[0] == "reject-traversal") message.contains("traverses") else message.contains("Ambiguous"))
            println("REJECTED_ENTRY")
            return
        }
        val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
        try {
            assertEquals(42, selected.getMethod("call").invoke(selected.getConstructor().newInstance()))
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))), resources.implementationSha256)
            val inherited = OperationBinding("boot-fixture", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "hashCode()I"))
            assertFailsWith<IllegalArgumentException> { resources.claim(inherited, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 8) }
            val binding = OperationBinding("boot-fixture", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
            if (arguments[0] == "reject-member") {
                val failure = assertFailsWith<IllegalArgumentException> { resources.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 8) }
                assertTrue(checkNotNull(failure.message).contains("outside the selected artifact"))
                println("REJECTED_MEMBER")
                return
            }
            resources.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 8)
            resources.validate()
            when (arguments[0]) {
                "mutate" -> {
                    Files.newOutputStream(jar, StandardOpenOption.APPEND).use { it.write(1) }
                    assertFailsWith<IllegalStateException> { resources.validate() }
                    println("REJECTED_MUTATION")
                }
                "replace" -> {
                    val bytes = Files.readAllBytes(jar)
                    Files.move(jar, jar.resolveSibling("retired.jar"))
                    Files.write(jar, bytes)
                    assertFailsWith<IllegalStateException> { resources.validate() }
                    println("REJECTED_REPLACEMENT")
                }
                "symlink" -> {
                    val replacement = Files.write(jar.resolveSibling("replacement.jar"), Files.readAllBytes(jar))
                    Files.delete(jar)
                    Files.createSymbolicLink(jar, replacement)
                    assertFailsWith<IllegalStateException> { resources.validate() }
                    println("REJECTED_SYMLINK")
                }
                "resolve" -> println("RESOLVED ${resources.implementationSha256} ${resources.resourceGeneration}")
                else -> error("Unknown deployment fixture mode")
            }
        } finally {
            resources.retire()
        }
        assertFailsWith<IllegalStateException> { resources.validate() }
    }
}

/** Observations of real kernel owners only; never converts an observed integer into a closeable FD. */
internal object DeploymentOwnerAssertions {
    fun pathsBelow(root: Path): List<Path> =
        Files.list(Path.of("/proc/self/fd")).use { paths ->
        paths.filter { Files.exists(it) && Files.readSymbolicLink(it).toString().startsWith(root.toString()) }.toList()
    }
}
