package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PreparedOperationResourcesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `selected loader jar bytes and immutable resource generation are actually measured`() {
        val jar = implementationJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("fixture.Operation")
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { resolved ->
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))), resolved.implementationSha256)
                val generation = resolved.resourceGeneration
                resolved.validate()
                Files.write(jar, byteArrayOf(1, 2, 3))
                assertEquals(generation, resolved.resourceGeneration)
                assertFailsWith<IllegalStateException> { resolved.validate() }
            }
        }
    }

    @Test
    fun `identical jar replacement changes object generation and invalidates prepared owner`() {
        val jar = implementationJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("fixture.Operation")
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { resolved ->
                val bytes = Files.readAllBytes(jar)
                Files.move(jar, directory.resolve("old.jar"))
                Files.write(jar, bytes)
                assertFailsWith<IllegalStateException> { resolved.validate() }
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { replaced ->
                    assertEquals(resolved.implementationSha256, replaced.implementationSha256)
                    assertNotEquals(resolved.resourceGeneration, replaced.resourceGeneration)
                }
            }
        }
    }

    @Test
    fun `configured metadata cannot substitute actual bytes generation or JVM member`() {
        val jar = implementationJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            val worker = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy)
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resolved ->
                val wrongHash = OperationBinding("operation", OperationAdapter.CALLABLE, "a".repeat(64), resolved.resourceGeneration, mapOf("entryPoint" to "call()I"))
                assertFailsWith<IllegalArgumentException> { resolved.claim(wrongHash, worker, LinuxNative.snapshot(), 8) }
                val wrongGeneration = OperationBinding("operation", OperationAdapter.CALLABLE, resolved.implementationSha256, "configured-self-report", mapOf("entryPoint" to "call()I"))
                assertFailsWith<IllegalArgumentException> { resolved.claim(wrongGeneration, worker, LinuxNative.snapshot(), 8) }
                val wrongMember = OperationBinding("operation", OperationAdapter.CALLABLE, resolved.implementationSha256, resolved.resourceGeneration, mapOf("entryPoint" to "call(I)I"))
                assertFailsWith<IllegalArgumentException> { resolved.claim(wrongMember, worker, LinuxNative.snapshot(), 8) }
                resolved.validate()
            }
        }
    }

    @Test
    fun `strict read only recipe rejects ABI lacking truncate enforcement`() {
        val jar = implementationJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), setOf(jar)).use { resolved ->
                val binding = OperationBinding("operation", OperationAdapter.CALLABLE, resolved.implementationSha256, resolved.resourceGeneration, mapOf("entryPoint" to "call()I"))
                assertFailsWith<IllegalArgumentException> { resolved.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 2) }
            }
        }
    }

    @Test
    fun `method implemented outside selected artifact cannot inherit its implementation digest`() {
        val jar = implementationJar()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            val resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet())
            try {
                val binding = OperationBinding("operation", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "hashCode()I"))
                assertFailsWith<IllegalArgumentException> { resources.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 8) }
            } finally {
                resources.retire()
            }
        }
    }

    private fun implementationJar(): Path {
        val source = directory.resolve("Operation.java")
        Files.writeString(source, "package fixture; public class Operation { public int call() { return 42; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val jar = directory.resolve("operation.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/Operation.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/Operation.class")))
            it.closeEntry()
        }
        return jar
    }

    @Test
    fun `mutable strict recipe cleared during acquisition still rejects ABI2 open truncate gap`() {
        val jar = implementationJar()
        val strict = mutableSetOf(jar)
        val real = LinuxNative.snapshot()
        val engine = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    strict.clear()
                    return real.fileSystem.open(path, flags)
                }
            }
        }
        LinuxNative.setEngine(engine)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowFsRead(jar)
                    .block(Syscall.RENAME, Syscall.LINK, Syscall.TRUNCATE, Syscall.FTRUNCATE, Syscall.IOCTL)
                    .build()
                val resolved = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), strict)
                try {
                    assertEquals(0, strict.size)
                    val binding = OperationBinding("operation", OperationAdapter.CALLABLE, resolved.implementationSha256, resolved.resourceGeneration, mapOf("entryPoint" to "call()I"))
                    assertFailsWith<IllegalArgumentException> { resolved.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 2) }
                } finally {
                    resolved.retire()
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
