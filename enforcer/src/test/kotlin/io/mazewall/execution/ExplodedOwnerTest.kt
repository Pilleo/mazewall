package io.mazewall.enforcer.api.execution

import io.mazewall.ffi.internal.ImplementationArtifact
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExplodedOwnerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `fatal tree close attempts all independent genuine descendants and root`() {
        val root = DeploymentPackagingFixture.compile(directory)
        val real = LinuxNative.snapshot()
        val fatal = AssertionError("actual tree close failure")
        val closed = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val engine = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closed.add(fd)
                    val result = real.fileSystem.close(fd)
                    if (closed.size == 1) {
                        result.getOrThrow("genuine first close")
                        throw fatal
                    }
                    return result
                }
            }
        }
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val owner = ImplementationArtifact.resolve(loader.loadClass("fixture.Operation"), engine) { true }
            assertTrue(assertFailsWith<AssertionError> { owner.close() } === fatal)
            assertEquals(4, closed.size)
            assertTrue(closed.none { it.isLiveForIo() })
            assertTrue(DeploymentOwnerAssertions.pathsBelow(root).isEmpty())
            owner.close()
            assertEquals(4, closed.size, "Retired actual tokens cannot be closed twice")
        }
    }

    @Test
    fun `fatal leaf rollback preserves replacement failure and releases earlier genuine tree owners`() {
        val root = DeploymentPackagingFixture.compile(directory)
        val real = LinuxNative.snapshot()
        val fatal = AssertionError("actual leaf rollback close failure")
        var opened = 0
        val closed = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val engine = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val result = real.fileSystem.open(path, flags)
                    if (++opened == 4) {
                        val selected = root.resolve("fixture/Operation.class")
                        val bytes = Files.readAllBytes(selected)
                        Files.move(selected, directory.resolve("retired.class"))
                        Files.write(selected, bytes)
                    }
                    return result
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closed.add(fd)
                    val result = real.fileSystem.close(fd)
                    if (closed.size == 1) {
                        result.getOrThrow("genuine rollback close")
                        throw fatal
                    }
                    return result
                }
            }
        }
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val failure = assertFailsWith<AssertionError> { ImplementationArtifact.resolve(loader.loadClass("fixture.Operation"), engine) { true } }
            assertTrue(failure === fatal)
            assertTrue(failure.suppressed.any { it is IllegalStateException })
            assertEquals(4, closed.size)
            assertTrue(closed.none { it.isLiveForIo() })
            assertTrue(DeploymentOwnerAssertions.pathsBelow(root).isEmpty())
        }
    }
}
