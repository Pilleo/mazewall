package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ResolveAbsolutePathTest {

    @Test
    fun `resolveAbsolutePath returns path even for non-existent absolute path`() {
        val handler = SupervisorSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(-1),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(-1)
        )

        val method = SupervisorSessionHandler::class.java.getDeclaredMethod(
            "resolveAbsolutePath",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true

        val nonExistentPath = "/tmp/this/path/does/not/exist/at/all/12345"

        // FIXED BEHAVIOR: resolveAbsolutePath now returns a securely canonicalized non-null Path using toRealPathWithFallback,
        // resolving the existing parent hierarchy first to prevent directory traversal / symlink escapes.
        val result = method.invoke(handler, 0, -100, nonExistentPath) as Path?

        assertNotNull(result)
        assertEquals(Paths.get(nonExistentPath).normalize(), result)
    }

    @Test
    fun `resolveAbsolutePath returns path even for non-existent path in safeBypassPaths`() {
        val handler = SupervisorSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(-1),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(-1)
        )

        val method = SupervisorSessionHandler::class.java.getDeclaredMethod(
            "resolveAbsolutePath",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true

        val result = method.invoke(handler, 0, -100, "build/non-existent-file-12345") as Path?
        assertNotNull(result)
        // With AT_FDCWD, the code queries baseDir = /proc/0/cwd, which points to the current working directory.
        // So the resolved path's prefix will be /proc/0/cwd/build/non-existent-file-12345.
        // Let's verify that this normalized relative path resolution is correctly resolved.
        assertEquals(Paths.get("/proc/0/cwd/build/non-existent-file-12345").normalize(), result)
    }

    @Test
    fun `toRealPathWithFallback correctly canonicalizes existing parent of non-existent files`() {
        // Create a temporary file, get its parent, and resolve a non-existent child.
        val tempFile = Files.createTempFile("existing-parent-test", ".tmp")
        val parentDir = tempFile.parent
        val nonExistentChild = parentDir.resolve("non-existent-child-abc-123")

        val companionClass = Class.forName("io.mazewall.enforcer.supervisor.SupervisorSessionHandler\$Companion")
        val companionInstance = SupervisorSessionHandler.Companion
        val method = companionClass.getDeclaredMethod("toRealPathWithFallback", Path::class.java)
        method.isAccessible = true

        val result = method.invoke(companionInstance, nonExistentChild) as Path

        assertNotNull(result)
        assertEquals(nonExistentChild.toAbsolutePath().normalize(), result)

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `toRealPathWithFallback propagates critical exceptions like FileSystemLoopException`() {
        val companionClass = Class.forName("io.mazewall.enforcer.supervisor.SupervisorSessionHandler\$Companion")
        val companionInstance = SupervisorSessionHandler.Companion
        val method = companionClass.getDeclaredMethod("toRealPathWithFallback", Path::class.java)
        method.isAccessible = true

        // Create a symlink loop
        val tempDir = Files.createTempDirectory("symlink-loop-test")
        val linkA = tempDir.resolve("linkA")
        val linkB = tempDir.resolve("linkB")

        try {
            Files.createSymbolicLink(linkA, linkB)
            Files.createSymbolicLink(linkB, linkA)

            val exception = assertThrows<java.lang.reflect.InvocationTargetException> {
                method.invoke(companionInstance, linkA)
            }
            assertTrue(exception.cause is FileSystemLoopException || exception.cause is java.io.IOException)
        } finally {
            Files.deleteIfExists(linkA)
            Files.deleteIfExists(linkB)
            Files.deleteIfExists(tempDir)
        }
    }
}
