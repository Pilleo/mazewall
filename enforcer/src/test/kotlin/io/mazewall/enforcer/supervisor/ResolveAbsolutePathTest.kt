package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
    fun `proc and sys paths remain subject to supervisor policy evaluation`() {
        assertFalse(BypassPaths.isBypassPath(Paths.get("/proc/self/environ")))
        assertFalse(BypassPaths.isBypassPath(Paths.get("/sys/kernel/security")))
    }

    @Test
    fun `Maven settings remain subject to supervisor policy evaluation`() {
        val settings = Paths.get(System.getProperty("user.home"), ".m2", "settings.xml")

        assertFalse(BypassPaths.isBypassPath(settings))
    }

    @Test
    fun `resolveAbsolutePath rejects a non-existent absolute path`() {
        val nonExistentPath = "/tmp/this/path/does/not/exist/at/all/12345"

        val result = SupervisorFastPath.resolveAbsolutePath(0, TraceeDirFd(-100), nonExistentPath)

        assertNull(result)
        assertTrue(BypassPaths.resolveForPolicy(Paths.get(nonExistentPath)) is BypassPaths.PathResolution.Unsafe)
    }

    @Test
    fun `resolveAbsolutePath rejects a non-existent path in safeBypassPaths`() {
        val result = SupervisorFastPath.resolveAbsolutePath(0, TraceeDirFd(-100), "build/non-existent-file-12345")
        assertNull(result)
    }

    @Test
    fun `unresolvable relative path is not remapped onto a daemon bypass root`() {
        val resolvedPath = SupervisorFastPath.resolveAbsolutePath(0, TraceeDirFd(999), "build/secret")

        if (resolvedPath == null) {
            return
        }
        assertFalse(BypassPaths.isBypassPath(resolvedPath))
        assertFalse(
            BypassPaths.safeBypassPaths.any { root ->
            resolvedPath.normalize().startsWith(root.normalize())
        },
        )
    }

    @Test
    fun `missing relative bypass path is rejected after tracee dirfd resolution`() {
        val traceeDirectory = Files.createTempDirectory("mazewall-tracee-dir")
        val tracee = ProcessBuilder(
            "bash",
            "-c",
            "exec 9<\"$traceeDirectory\"; echo $$; sleep 30",
        ).redirectErrorStream(true).start()

        try {
            val traceePid = tracee.inputReader().readLine().toInt()
            assertNull(SupervisorFastPath.resolveAbsolutePath(traceePid, TraceeDirFd(9), "build/secret"))
        } finally {
            tracee.destroyForcibly()
            tracee.waitFor()
            Files.deleteIfExists(traceeDirectory)
        }
    }

    @Test
    fun `toRealPathWithFallback correctly canonicalizes existing parent of non-existent files`() {
        // Create a temporary file, get its parent, and resolve a non-existent child.
        val tempFile = Files.createTempFile("existing-parent-test", ".tmp")
        val parentDir = tempFile.parent
        val nonExistentChild = parentDir.resolve("non-existent-child-abc-123")

                val result = BypassPaths.toRealPathWithFallback(nonExistentChild)

        assertNotNull(result)
        assertEquals(nonExistentChild.toAbsolutePath().normalize(), result)

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `toRealPathWithFallback propagates critical exceptions like FileSystemLoopException`() {
                // Create a symlink loop
        val tempDir = Files.createTempDirectory("symlink-loop-test")
        val linkA = tempDir.resolve("linkA")
        val linkB = tempDir.resolve("linkB")

        try {
            Files.createSymbolicLink(linkA, linkB)
            Files.createSymbolicLink(linkB, linkA)

            val exception = assertThrows<java.nio.file.FileSystemException> {
                BypassPaths.toRealPathWithFallback(linkA)
            }
            // Some JDKs throw FileSystemLoopException, others throw FileSystemException
            // Both are acceptable
        } finally {
            Files.deleteIfExists(linkA)
            Files.deleteIfExists(linkB)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `isBypassPath fails closed on symlink loops`() {
        val tempDir = Files.createTempDirectory("bypass-loop")
        val linkA = tempDir.resolve("linkA")
        val linkB = tempDir.resolve("linkB")
        try {
            Files.createSymbolicLink(linkA, linkB)
            Files.createSymbolicLink(linkB, linkA)
            assertTrue(BypassPaths.resolveForPolicy(linkA) is BypassPaths.PathResolution.Unsafe)
            assertFalse(BypassPaths.isBypassPath(linkA))
        } finally {
            Files.deleteIfExists(linkA)
            Files.deleteIfExists(linkB)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `policy resolution follows symlinks before parent traversal`() {
        val root = Files.createTempDirectory("bypass-dotdot")
        val trusted = Files.createDirectories(root.resolve("trusted"))
        val outside = Files.createDirectories(root.resolve("outside"))
        val outsideChild = Files.createDirectories(outside.resolve("child"))
        val trustedSecret = Files.writeString(trusted.resolve("secret"), "trusted")
        val outsideSecret = Files.writeString(outside.resolve("secret"), "outside")
        val link = trusted.resolve("link")
        Files.createSymbolicLink(link, outsideChild)
        try {
            val adversarial = trusted.resolve("link/../secret")
            val resolved = assertInstanceOf(
                BypassPaths.PathResolution.Resolved::class.java,
                BypassPaths.resolveForPolicy(adversarial),
            )

            assertEquals(outsideSecret.toRealPath(), resolved.path)
            assertFalse(resolved.path == trustedSecret.toRealPath())
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(trustedSecret)
            Files.deleteIfExists(outsideSecret)
            Files.deleteIfExists(outsideChild)
            Files.deleteIfExists(trusted)
            Files.deleteIfExists(outside)
            Files.deleteIfExists(root)
        }
    }
}
