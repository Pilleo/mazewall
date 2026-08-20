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
    fun `NO_EXEC is not a JIT-safe process-wide recipe`() {
        assertFalse(Policy.NO_EXEC.allowMmapExec, "raw NO_EXEC still denies PROT_EXEC mmap/mprotect")
        assertFalse(Policy.NO_EXEC.isSyscallAllowed(Syscall.EXECVE))
        assertTrue(Policy.NO_EXEC_HOTSPOT.allowMmapExec)
        assertFalse(Policy.NO_EXEC_HOTSPOT.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(Policy.NO_EXEC_HOTSPOT.isSyscallAllowed(Syscall.EXECVEAT))
        assertFalse(Policy.NO_EXEC_HOTSPOT.isSyscallAllowed(Syscall.MEMFD_CREATE))
    }

    @Test
    fun `ProcessPolicies denyProcessCreation is runtime-aware and inspectable`() {
        val hotspot = ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val native = ProcessPolicies.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)

        assertTrue(hotspot.argumentRules.allowExecutableMappings)
        assertFalse(native.argumentRules.allowExecutableMappings)
        assertTrue(hotspot.argumentRules.inspectNonThreadClone)
        assertTrue(hotspot.argumentRules.inspectUnsafePrctl)
        assertFalse(hotspot.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(native.isSyscallAllowed(Syscall.MEMFD_CREATE))
        assertEquals(Policy.NO_EXEC_NATIVE_IMAGE.allowMmapExec, native.allowMmapExec)
        assertEquals(Policy.NO_EXEC_HOTSPOT.allowMmapExec, hotspot.allowMmapExec)
    }

    @Test
    fun `denyList and allowList modes are inspectable and compose restrictively`() {
        val denied =
            Policy.denyList(RuntimeProfile.HOTSPOT_JIT) {
                denyProcessCreation()
                denyNetwork()
            }
        val extra =
            Policy.denyList(RuntimeProfile.NATIVE_IMAGE) {
                denyProcessCreation()
            }
        assertEquals(PolicyMode.DENY_LIST, denied.mode)
        assertTrue(denied.argumentRules.allowExecutableMappings)
        assertFalse(denied.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(denied.isSyscallAllowed(Syscall.CONNECT))

        val restricted = denied.restrictFurtherWith(extra)
        val combined = Policy.combine(denied, extra)
        assertEquals(combined.allowMmapExec, restricted.allowMmapExec)
        assertEquals(combined.syscallActions, restricted.syscallActions)
        assertFalse(restricted.argumentRules.allowExecutableMappings)

        val allowListed =
            Policy.allowList(RuntimeProfile.NATIVE_IMAGE) {
                allow(Syscall.READ, Syscall.WRITE)
            }
        assertEquals(PolicyMode.ALLOW_LIST, allowListed.mode)
        val customErrno =
            Policy
                .builder()
                .defaultAction(SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES))
                .allow(Syscall.READ)
                .build()
        assertEquals(PolicyMode.ALLOW_LIST, customErrno.mode)
        assertEquals(SeccompAction.ACT_ERRNO, allowListed.defaultAction)
        assertTrue(allowListed.isSyscallAllowed(Syscall.READ))
        assertFalse(allowListed.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(allowListed.argumentRules.allowExecutableMappings)
    }

    @Test
    fun `restrictFurtherWith applies default deny to explicit allows on the other policy`() {
        val denyByDefault =
            Policy.allowList(RuntimeProfile.NATIVE_IMAGE) {
                allow(Syscall.READ)
            }
        val explicitAllow =
            Policy.denyList(RuntimeProfile.HOTSPOT_JIT) {
                advanced { allow(Syscall.CONNECT) }
            }
        val restricted = denyByDefault.restrictFurtherWith(explicitAllow)
        assertFalse(restricted.isSyscallAllowed(Syscall.CONNECT))
        assertTrue(restricted.isSyscallAllowed(Syscall.READ))
    }

    @Test
    fun `Landlock intersection treats empty read grants as deny`() {
        val reads =
            Policy
                .builder()
                .allowFsRead("/secret")
                .build()
        val writesOnly =
            Policy
                .builder()
                .allowFsWrite("/tmp")
                .build()
        val combined = Policy.combine(reads, writesOnly)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
        assertTrue(combined.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `advanced block is applied on denyList and allowList`() {
        val denied =
            Policy.denyList(RuntimeProfile.HOTSPOT_JIT) {
                advanced { block(Syscall.PTRACE) }
            }
        assertFalse(denied.isSyscallAllowed(Syscall.PTRACE))
        val allowed =
            Policy.allowList(RuntimeProfile.NATIVE_IMAGE) {
                advanced { allow(Syscall.GETPID) }
            }
        assertTrue(allowed.isSyscallAllowed(Syscall.GETPID))
    }

    @Test
    fun `forRuntime NATIVE_IMAGE clears a prior HotSpot mmap exec flag`() {
        val policy =
            Policy
                .builder()
                .forRuntime(RuntimeProfile.HOTSPOT_JIT)
                .forRuntime(RuntimeProfile.NATIVE_IMAGE)
                .build()
        assertFalse(policy.allowMmapExec)
    }

    @Test
    fun `ProcessPolicies denyNetwork does not hide W^X on HotSpot`() {
        val hotspot = ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT)
        val native = ProcessPolicies.denyNetwork(RuntimeProfile.NATIVE_IMAGE)
        assertTrue(hotspot.argumentRules.allowExecutableMappings)
        assertFalse(native.argumentRules.allowExecutableMappings)
        assertFalse(hotspot.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(Policy.NO_NETWORK.argumentRules.allowExecutableMappings)
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

    @Test
    fun `io_uring_setup is blocked if open or openat is restricted and Landlock is not active`() {
        val p = Policy.builder()
            .block(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .build()

        assertFalse(p.isSyscallAllowed(Syscall.OPEN))
        assertFalse(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should be blocked to prevent bypass when Landlock is not active")
    }

    @Test
    fun `io_uring_setup remains allowed if open and openat are allowed`() {
        val p = Policy.builder()
            .allow(Syscall.OPEN)
            .allow(Syscall.OPENAT)
            .allow(Syscall.IO_URING_SETUP)
            .build()

        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
        assertTrue(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should remain allowed when open and openat are allowed")
    }

    @Test
    fun `io_uring_setup remains allowed if Landlock is active`() {
        val p = Policy.builder()
            .block(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .allowFsRead("/some/path")
            .build()

        assertTrue(p.enforceLandlock)
        assertTrue(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should remain allowed when Landlock is active to enforce path limits")
    }

    @Test
    fun `io_uring_setup blocking is resolved correctly during policy combination`() {
        val p1 = Policy.builder().block(Syscall.OPEN).allow(Syscall.IO_URING_SETUP).build()
        val p2 = Policy.builder().allow(Syscall.OPEN).allow(Syscall.IO_URING_SETUP).build()

        val combined = Policy.combine(p1, p2)
        assertFalse(combined.isSyscallAllowed(Syscall.IO_URING_SETUP), "combined policy should block io_uring_setup because open is restricted overall and Landlock is not active")
    }
}
