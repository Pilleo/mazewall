package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class ResolveAbsolutePathTest {

    @Test
    fun `resolveAbsolutePath returns null for non-existent absolute path`() {
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

        // Current behavior (VULNERABLE): returns normalized path even if it doesn't exist
        // Desired behavior (FIXED): returns null if toRealPath() fails
        val result = method.invoke(handler, 0, -100, nonExistentPath) as Path?

        // This assertion is expected to FAIL currently
        assertNull(result, "Should return null for non-existent absolute path to prevent TOCTOU")
    }

    @Test
    fun `resolveAbsolutePath returns null for non-existent path in safeBypassPaths`() {
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

        // We can't easily mock safeBypassPaths because it's a private companion object property.
        // But we know it contains "build".
        // If we try to resolve "build/non-existent-file-12345", it might hit the fallback.

        val result = method.invoke(handler, 0, -100, "build/non-existent-file-12345") as Path?
        assertNull(result, "Should return null for non-existent file in bypass paths")
    }
}
