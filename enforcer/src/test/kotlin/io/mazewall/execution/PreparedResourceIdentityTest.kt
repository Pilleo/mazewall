package io.mazewall.enforcer.api.execution

import io.mazewall.ffi.internal.PreparedPath
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparedResourceIdentityTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `genuine descriptor retains old object across replacement and retires exactly once`() {
        val path = Files.writeString(directory.resolve("reviewed.txt"), "original")
        val pinned = PreparedPath.open(path, LinuxNative.snapshot())
        val descriptorPath = pinned.descriptorPath()
        try {
            assertTrue(pinned.descriptor.isLiveForIo())
            Files.move(path, directory.resolve("old.txt"))
            Files.writeString(path, "replacement")
            assertEquals("original", Files.readString(descriptorPath))
            assertFailsWith<IllegalStateException> { pinned.validate() }
        } finally {
            pinned.close()
        }
        assertFalse(pinned.descriptor.isLiveForIo())
        assertFalse(Files.exists(descriptorPath))
        pinned.close()
    }

    @Test
    fun `same-content symlink switch is an object generation change`() {
        val first = Files.writeString(directory.resolve("first.txt"), "identical")
        val second = Files.writeString(directory.resolve("second.txt"), "identical")
        val link = Files.createSymbolicLink(directory.resolve("selected.txt"), first)
        PreparedPath.open(link, LinuxNative.snapshot()).use { pinned ->
            Files.delete(link)
            Files.createSymbolicLink(link, second)
            assertFailsWith<IllegalStateException> { pinned.validate() }
            assertEquals("identical", Files.readString(pinned.descriptorPath()))
        }
    }

    @Test
    fun `EPERM and EACCES from acquisition are failures not empty grants`() {
        val path = Files.writeString(directory.resolve("required.txt"), "required")
        val real = LinuxNative.snapshot()
        listOf(1, 13).forEach { errno ->
            val engine = object : NativeEngine by real {
                override val fileSystem = object : NativeFileSystem by real.fileSystem {
                    override fun open(
                        path: ManagedSegment,
                        flags: OpenFlags,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(errno, -1L)

                    override fun open(
                        path: ManagedSegment,
                        flags: OpenFlags,
                        mode: Int,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(errno, -1L)
                }
            }
            val failure = assertFailsWith<IllegalStateException> { PreparedPath.open(path, engine) }
            assertTrue(failure.message!!.contains("errno=$errno"))
        }
    }

    @Test
    fun `directory identity allows future output but rejects directory replacement`() {
        val output = Files.createDirectory(directory.resolve("output"))
        PreparedPath.open(output, LinuxNative.snapshot()).use { pinned ->
            Files.writeString(output.resolve("new-report.txt"), "report")
            pinned.validate()
            Files.move(output, directory.resolve("old-output"))
            Files.createDirectory(output)
            assertFailsWith<IllegalStateException> { pinned.validate() }
        }
    }

    @Test
    fun `fatal rollback failure is primary and original replacement rejection is retained`() {
        val path = Files.writeString(directory.resolve("required.txt"), "required")
        val real = LinuxNative.snapshot()
        val fatal = AssertionError("fatal close")
        val engine = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun open(
                    nativePath: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val opened = real.fileSystem.open(nativePath, flags)
                    Files.move(path, directory.resolve("old.txt"))
                    Files.writeString(path, "replaced")
                    return opened
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    real.fileSystem.close(fd).getOrThrow("fixture close")
                    throw fatal
                }
            }
        }
        val failure = assertFailsWith<AssertionError> { PreparedPath.open(path, engine) }
        assertTrue(failure === fatal)
        assertTrue(failure.suppressed.any { it is IllegalStateException })
    }
}
