package io.mazewall.ffi.internal

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.UserDefinedFileAttributeView
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@EnabledOnOs(OS.LINUX)
class ExtendedAttributeTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `engines without extended attribute capability fail explicitly unsupported`() {
        NativeArena.ofConfined().use { arena ->
            val result = io.mazewall.enforcer.api.MockNativeFileSystem().removeExtendedAttribute(
                arena.allocateFrom(root.resolve("owned.tmp").toString()),
                arena.allocateFrom("system.posix_acl_access"),
            )
            assertEquals(NativeConstants.ENOSYS, (result as LinuxNative.SyscallResult.Error<*>).errno)
        }
    }

    @Test
    fun `native removal clears attribute and captures absent and missing path errno`() {
        val file = Files.createTempFile(root, "xattr", ".tmp")
        val view = Files.getFileAttributeView(file, UserDefinedFileAttributeView::class.java)
        view.write("mazewall-test", ByteBuffer.wrap(byteArrayOf(1)))
        NativeArena.ofConfined().use { arena ->
            val path = arena.allocateFrom(file.toString())
            val name = arena.allocateFrom("user.mazewall-test")
            assertEquals(0L, RealNativeEngine.fileSystem.removeExtendedAttribute(path, name).getOrThrow("remove xattr"))
            assertFalse(view.list().contains("mazewall-test"))
            assertEquals(
                NativeConstants.ENODATA,
                (RealNativeEngine.fileSystem.removeExtendedAttribute(path, name) as LinuxNative.SyscallResult.Error<*>).errno,
            )
            Files.delete(file)
            assertEquals(
                NativeConstants.ENOENT,
                (RealNativeEngine.fileSystem.removeExtendedAttribute(path, name) as LinuxNative.SyscallResult.Error<*>).errno,
            )
        }
    }
}
