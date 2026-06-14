package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DMI_HARDCODED_ABSOLUTE_FILENAME")
class PolicyTest {
    @Test
    fun `default policy allows everything`() {
        val p = Policy.builder().build()
        assertEquals(SeccompAction.ACT_ALLOW, p.defaultAction)
        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `builder methods correctly set flags`(@TempDir tempDir: java.nio.file.Path) {
        val rPath = tempDir.resolve("r").toFile().apply { createNewFile() }.absolutePath
        val wPath = tempDir.resolve("w").toFile().apply { createNewFile() }.absolutePath

        val policy =
            Policy
                .builder()
                .allowMmapExec()
                .allowNonThreadClone()
                .allowUnsafePrctl()
                .allowFsRead(rPath)
                .allowFsWrite(wPath)
                .build()

        assertTrue(policy.allowMmapExec, "allowMmapExec should be true")
        assertTrue(policy.allowNonThreadClone, "allowNonThreadClone should be true")
        assertTrue(policy.allowUnsafePrctl, "allowUnsafePrctl should be true")
        assertTrue(policy.allowedFsReadPaths.any { it.value == rPath })
        assertTrue(policy.allowedFsWritePaths.any { it.value == wPath })
    }

    @Test
    fun `builder block and allow syscalls`() {
        val p =
            Policy
                .builder()
                .block(Syscall.OPEN)
                .allow(Syscall.CLOSE)
                .build()

        assertFalse(p.isSyscallAllowed(Syscall.OPEN))
        assertTrue(p.isSyscallAllowed(Syscall.CLOSE))
    }

    @Test
    fun `builder unblock syscalls`() {
        val p =
            Policy
                .builder()
                .block(Syscall.OPEN)
                .unblock(Syscall.OPEN)
                .build()
        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `builder allowFsRead with duplicate path`(@TempDir tempDir: java.nio.file.Path) {
        val rPath = tempDir.resolve("r").toFile().apply { createNewFile() }.absolutePath
        val policy =
            Policy
                .builder()
                .allowFsRead(rPath)
                .allowFsRead(rPath)
                .build()
        assertEquals(1, policy.allowedFsReadPaths.size)
    }

    @Test
    fun `builder allowFsWrite rejects invalid paths`() {
        assertFailsWith<java.nio.file.NoSuchFileException> {
            Policy.builder().allowFsWrite("/nonexistent/path/at/all")
        }
    }

    @Test
    fun `builder allowFsRead rejects invalid paths`() {
        assertFailsWith<java.nio.file.NoSuchFileException> {
            Policy.builder().allowFsRead("/nonexistent/path/at/all")
        }
    }

    @Test
    fun `builder base() merges all flags`(@TempDir tempDir: java.nio.file.Path) {
        val rPath = tempDir.resolve("r").toFile().apply { createNewFile() }.absolutePath
        val wPath = tempDir.resolve("w").toFile().apply { createNewFile() }.absolutePath

        val p1 =
            Policy
                .threadLocalBuilder()
                .allowMmapExec()
                .allowNonThreadClone()
                .allowUnsafePrctl()
                .allowFsRead(rPath)
                .allowFsWrite(wPath)
                .block(Syscall.CONNECT)
                .build()

        val p2 = Policy.threadLocalBuilder().base(p1).build()

        assertTrue(p2.allowMmapExec)
        assertTrue(p2.allowNonThreadClone)
        assertTrue(p2.allowUnsafePrctl)
        assertTrue(p2.allowedFsReadPaths.any { it.value == rPath })
        assertTrue(p2.allowedFsWritePaths.any { it.value == wPath })
        assertTrue(!p2.isSyscallAllowed(Syscall.CONNECT))
    }

    @Test
    fun `combine() intersects Landlock paths`() {
        val p1 =
            Policy
                .builder()
                .allowFsRead(SandboxedPath.of("/a", true))
                .allowFsRead(SandboxedPath.of("/common", true))
                .build()
        val p2 =
            Policy
                .builder()
                .allowFsRead(SandboxedPath.of("/b", true))
                .allowFsRead(SandboxedPath.of("/common", true))
                .build()
        val combined = Policy.combine(p1, p2)

        assertEquals(setOf("/common"), combined.allowedFsReadPaths.map { it.value }.toSet(), "Landlock paths should be intersected")
        assertTrue(combined.enforceLandlock, "Should enforce Landlock")
    }

    @Test
    fun `combine() hierarchical Landlock paths yield most restrictive`() {
        val p1 = Policy.builder().allowFsRead(SandboxedPath.of("/var", true)).build()
        val p2 = Policy.builder().allowFsRead(SandboxedPath.of("/var/log", true)).build()
        val combined = Policy.combine(p1, p2)

        assertEquals(setOf("/var/log"), combined.allowedFsReadPaths.map { it.value }.toSet(), "Should yield the more restrictive path")
        assertTrue(combined.enforceLandlock)
    }

    @Test
    fun `combine() path intersection edge cases`() {
        // Test /h1 vs /h2 (should not intersect)
        val p1 = Policy.builder().allowFsRead(SandboxedPath.of("/h1", true)).build()
        val p2 = Policy.builder().allowFsRead(SandboxedPath.of("/h2", true)).build()
        val combined = Policy.combine(p1, p2)
        assertTrue(combined.allowedFsReadPaths.isEmpty(), "/h1 should not intersect with /h2")

        // Test nested paths
        val p3 = Policy.builder().allowFsRead(SandboxedPath.of("/d1", true)).build()
        val p4 = Policy.builder().allowFsRead(SandboxedPath.of("/d1/db", true)).build()
        assertEquals(setOf("/d1/db"), Policy.combine(p3, p4).allowedFsReadPaths.map { it.value }.toSet())
    }

    @Test
    fun `combine() disjoint Landlock paths forces Landlock`() {
        val p1 = Policy.builder().allowFsRead(SandboxedPath.of("/a", true)).build()
        val p2 = Policy.builder().allowFsRead(SandboxedPath.of("/b", true)).build()

        val combined = Policy.combine(p1, p2)
        assertTrue(combined.enforceLandlock)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
    }

    @Test
    fun `combine() with no FS paths results in empty set`() {
        val p1 = Policy.builder().block(Syscall.BIND).build()
        val p2 = Policy.builder().block(Syscall.CONNECT).build()
        val combined = Policy.combine(p1, p2)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
        assertFalse(combined.enforceLandlock)
    }

    @Test
    fun `combineInternal handles empty read-path intersection`() {
        val p1 = Policy.builder().allowFsRead(SandboxedPath.of("/a", true)).build()
        val p2 = Policy.builder().allowFsRead(SandboxedPath.of("/b", true)).build()
        val combined = Policy.combine(p1, p2)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
        assertTrue(combined.enforceLandlock)
    }

    @Test
    fun `plus operator works and resolves types correctly`() {
        val p1 = Policy.NO_EXEC
        val p2 = Policy.NO_NETWORK
        val p3 = Policy.builder().allowFsRead(SandboxedPath.of("/t1", true)).build()

        // P + P -> P
        val combinedPP: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = p1 + p2
        assertFalse(combinedPP.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(combinedPP.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(combinedPP.enforceLandlock)

        // P + T -> T
        val combinedPT: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = p1 + p3
        assertTrue(combinedPT.enforceLandlock)

        // T + P -> T
        val combinedTP: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = p3 + p1
        assertTrue(combinedTP.enforceLandlock)
    }
}
