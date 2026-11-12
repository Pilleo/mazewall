package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@Isolated
class PreparedResolutionOwnerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `observation leaves real unconsumed resources usable and retirement settles them`() {
        val root = Files.createDirectory(directory.resolve("classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val owner = PreparedResolutionOwner.create()
            val result = owner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
            assertIs<PreparedResolutionRetirement.Pending>(owner.retirement())
            result.validate()
            assertIs<PreparedResolutionRetirement.Complete>(owner.retryRetirement())
            assertEquals(PreparedResolutionRetirement.Complete(null), owner.retirement())
            result.close()
        }
    }

    @Test
    fun `cleanup Error is delivered once and both public close paths preserve its identity`() {
        val root = Files.createDirectory(directory.resolve("error-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val original = LinuxNative.snapshot().engineIdentity as NativeEngine
        val error = AssertionError("close failed after dispatch")
        val attempts = AtomicInteger()
        val delegate = object : NativeEngine by original {
            override val fileSystem = object : NativeFileSystem by original.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.incrementAndGet()
                    original.fileSystem.close(fd)
                    throw error
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(delegate)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = PreparedResolutionOwner.create()
                val result = owner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
                assertSame(error, assertFailsWith<AssertionError> { result.close() })
                val first = assertFailsWith<PreparedResolutionUnknownException> { owner.close() }
                val second = assertFailsWith<PreparedResolutionUnknownException> { result.close() }
                assertNotSame(first, second)
                assertSame(error, first.cause)
                assertSame(error, second.cause)
                assertSame(error, assertIs<PreparedResolutionRetirement.Unknown>(owner.retirement()).history)
                assertSame(error, assertIs<PreparedResolutionRetirement.Unknown>(owner.retryRetirement()).history)
                assertEquals(1, attempts.get())
            }
        } finally {
            LinuxNative.setEngine(original)
        }
    }
}
