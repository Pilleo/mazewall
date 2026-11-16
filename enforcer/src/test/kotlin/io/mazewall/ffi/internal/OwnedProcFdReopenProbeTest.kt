package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readString
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.getFdOrThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal class OwnedProcFdReopenProbeTest {
    private enum class Kind { REGULAR, DIRECTORY, SYMLINK, FIFO, SOCKET, DEVICE }

    private class Attributes(
        private val kind: Kind = Kind.REGULAR,
        private val key: Any? = "dev=1,ino=2",
    ) : BasicFileAttributes {
        override fun isRegularFile() = kind == Kind.REGULAR

        override fun isDirectory() = kind == Kind.DIRECTORY

        override fun isSymbolicLink() = kind == Kind.SYMLINK

        override fun isOther() = kind in setOf(Kind.FIFO, Kind.SOCKET, Kind.DEVICE)

        override fun fileKey() = key

        override fun size() = 8L

        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)

        override fun creationTime(): FileTime = FileTime.fromMillis(0)
    }

    private class Fixture : AutoCloseable {
        val native = MockNativeEngine()
        val events = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        val root = NativeArena.ofConfined().use { arena ->
            native.fileSystem.openResult = LinuxNative.SyscallResult.Success(100L)
            native.fileSystem
                .open(arena.allocateFrom("root"), OpenFlags(NativeConstants.O_PATH))
                .getFdOrThrow("mock root acquisition", FileDescriptorRole.OPath)
        }
        val proc = owner(101)
        val pin = owner(102)
        var metadata: (Path) -> BasicFileAttributes = { Attributes() }
        val probe = OwnedProcFdReopen(native) { path ->
            events += "metadata:${path.fileName}"
            metadata(path)
        }

        init {
            native.fileSystem.onClose = { fd ->
                closed += fd.value
                LinuxNative.SyscallResult.Success(0L)
            }
            native.fileSystem.onOpenat = { dir, path, flags, mode ->
                events += "reopen"
                assertEquals(proc.handle.value, dir.value)
                assertEquals(pin.handle.value.toString(), path.readString(0))
                assertEquals(NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC, flags.value)
                assertEquals(0, mode)
                LinuxNative.SyscallResult.Success(103L)
            }
        }

        private fun owner(value: Int): SupervisorOwnedFd =
            NativeArena.ofConfined().use { arena ->
            native.fileSystem.openResult = LinuxNative.SyscallResult.Success(value.toLong())
            val result = native.fileSystem.open(arena.allocateFrom("mock-$value"), OpenFlags(NativeConstants.O_PATH))
            (SupervisorDescriptorResult.from(result, native) as SupervisorDescriptorResult.Opened).descriptor
        }

        override fun close() {
            pin.close()
            proc.close()
            if (root.isLiveForIo()) native.fileSystem.close(root).getOrThrow("retire mock root")
        }
    }

    @Test
    fun `fixed acquisition and trusted reopen arguments`() =
        Fixture().use { f ->
        f.native.fileSystem.onOpenat2 = { root, path, how, size ->
            f.events += "acquire"
            assertEquals(f.root.value, root.value)
            assertEquals("sub/file", path.readString(0))
            assertEquals(24L, size)
            assertEquals(24L, how.byteSize())
            assertEquals((NativeConstants.O_PATH or NativeConstants.O_CLOEXEC).toLong(), how.readLong(Layouts.OPEN_HOW_FLAGS_OFFSET))
            assertEquals(0L, how.readLong(Layouts.OPEN_HOW_MODE_OFFSET))
            assertEquals(0x0fL, how.readLong(Layouts.OPEN_HOW_RESOLVE_OFFSET))
            LinuxNative.SyscallResult.Success(102L)
        }
        // Retire the setup pin before reusing the mock's acquisition number.
        f.pin.close()
        val acquired = (f.probe.acquire(f.root, "sub/file") as SupervisorDescriptorResult.Opened).descriptor
        acquired.use { pin ->
            val result = f.probe.reopenRegular(pin, f.proc) as SupervisorDescriptorResult.Opened
            result.descriptor.use { assertTrue(pin.handle.isLiveForIo()) }
        }
        assertEquals(listOf("acquire", "metadata:102", "reopen", "metadata:102", "metadata:103"), f.events)
    }

    @Test
    fun `nonregular or missing identity never attempts readable open`() {
        Kind.entries.filter { it != Kind.REGULAR }.forEach { kind ->
            Fixture().use { f ->
                f.metadata = { Attributes(kind) }
                assertThrows(IllegalArgumentException::class.java) { f.probe.reopenRegular(f.pin, f.proc) }
                assertFalse("reopen" in f.events)
            }
        }
        Fixture().use { f ->
            f.metadata = { Attributes(key = null) }
            assertThrows(IllegalArgumentException::class.java) { f.probe.reopenRegular(f.pin, f.proc) }
            assertFalse("reopen" in f.events)
        }
        Fixture().use { f ->
            val failure = IOException("pre-metadata")
            f.metadata = { throw failure }
            assertSame(failure, assertThrows(IOException::class.java) { f.probe.reopenRegular(f.pin, f.proc) })
            assertFalse("reopen" in f.events)
        }
        Fixture().use { f ->
            f.pin.close()
            assertThrows(IllegalStateException::class.java) { f.probe.reopenRegular(f.pin, f.proc) }
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun `permission and unsupported failures never retry`() {
        listOf(1, 13, 38, 22, 18).forEach { errno ->
            Fixture().use { f ->
                var acquisitions = 0
                f.native.fileSystem.onOpenat2 = { _, _, _, _ ->
                    acquisitions++
                    LinuxNative.SyscallResult.Error(errno, -1L)
                }
                assertEquals(SupervisorDescriptorResult.Denied(errno), f.probe.acquire(f.root, "file"))
                assertEquals(1, acquisitions)
                assertTrue(f.events.isEmpty())
                f.native.fileSystem.onOpenat = { _, _, _, _ ->
                    f.events += "reopen"
                    LinuxNative.SyscallResult.Error(errno, -1L)
                }
                assertEquals(SupervisorDescriptorResult.Denied(errno), f.probe.reopenRegular(f.pin, f.proc))
                assertEquals(listOf("metadata:102", "reopen"), f.events)
                assertTrue(f.closed.isEmpty())
                assertTrue(f.pin.handle.isLiveForIo())
            }
        }
    }

    @Test
    fun `post-open mismatch and metadata failure retire only the new owner`() {
        listOf(false, true).forEach { throws ->
            Fixture().use { f ->
                val failure = IOException("post-metadata")
                f.metadata = { path ->
                    if (path.fileName.toString() == "103") {
                        if (throws) throw failure
                        Attributes(key = "different")
                    } else {
                        Attributes()
                    }
                }
                val observed = assertThrows(Exception::class.java) { f.probe.reopenRegular(f.pin, f.proc) }
                if (throws) assertSame(failure, observed) else assertInstanceOf(IllegalArgumentException::class.java, observed)
                assertEquals(listOf(103), f.closed)
                assertTrue(f.pin.handle.isLiveForIo())
            }
        }
    }

    @Test
    fun `identity match retains owners until caller retirement`() =
        Fixture().use { f ->
        val readable = (f.probe.reopenRegular(f.pin, f.proc) as SupervisorDescriptorResult.Opened).descriptor
        assertTrue(readable.handle.isLiveForIo())
        assertTrue(f.pin.handle.isLiveForIo())
        assertTrue(f.closed.isEmpty())
        readable.close()
        readable.close()
        f.pin.close()
        assertEquals(listOf(103, 102), f.closed)
    }

    @Test
    fun `cleanup failure cannot return success or conceal metadata failure`() =
        Fixture().use { f ->
        val failure = IOException("metadata failed")
        f.metadata = { path -> if (path.fileName.toString() == "103") throw failure else Attributes() }
        f.native.fileSystem.onClose = { fd ->
            f.closed += fd.value
            LinuxNative.SyscallResult.Error(5, -1L)
        }
        val observed = assertThrows(IOException::class.java) { f.probe.reopenRegular(f.pin, f.proc) }
        assertSame(failure, observed)
        assertEquals(1, observed.suppressed.size)
        assertEquals(listOf(103), f.closed)
        f.native.fileSystem.onClose = { fd ->
            f.closed += fd.value
            LinuxNative.SyscallResult.Success(0L)
        }
    }

    @Test
    fun `invalid suffixes fail before acquisition`() =
        Fixture().use { f ->
        f.native.fileSystem.onOpenat2 = { _, _, _, _ -> fail("invalid suffix reached native acquisition") }
        listOf("", "/file", "..", "a/../file", "../outside").forEach { suffix ->
            assertThrows(IllegalArgumentException::class.java) { f.probe.acquire(f.root, suffix) }
        }
    }
}
