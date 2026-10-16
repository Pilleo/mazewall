package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyTest {
    @Test
    fun `default policy allows everything`() {
        val p = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        assertEquals(SeccompAction.ACT_ALLOW, p.defaultAction)
        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `builder defaults retain the three argument-safety restrictions`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()

        assertFalse(policy.allowMmapExec)
        assertFalse(policy.allowNonThreadClone)
        assertFalse(policy.allowUnsafePrctl)
    }

    @Test
    fun `builder methods correctly set flags`(
        @TempDir tempDir: java.nio.file.Path,
    ) {
        val rPath = tempDir
            .resolve("r")
            .toFile()
            .apply { createNewFile() }
            .absolutePath
        val wPath = tempDir
            .resolve("w")
            .toFile()
            .apply { createNewFile() }
            .absolutePath

        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
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
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.OPEN)
                .allow(Syscall.CLOSE)
                .build()

        assertFalse(p.isSyscallAllowed(Syscall.OPEN))
        assertTrue(p.isSyscallAllowed(Syscall.CLOSE))
    }

    @Test
    fun `builder unblock syscalls`() {
        val p =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.OPEN)
                .unblock(Syscall.OPEN)
                .build()
        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `builder allowFsRead with duplicate path`(
        @TempDir tempDir: java.nio.file.Path,
    ) {
        val rPath = tempDir
            .resolve("r")
            .toFile()
            .apply { createNewFile() }
            .absolutePath
        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead(rPath)
                .allowFsRead(rPath)
                .build()
        assertEquals(1, policy.allowedFsReadPaths.size)
    }

    @Test
    fun `builder base() merges all flags`(
        @TempDir tempDir: java.nio.file.Path,
    ) {
        val rPath = tempDir
            .resolve("r")
            .toFile()
            .apply { createNewFile() }
            .absolutePath
        val wPath = tempDir
            .resolve("w")
            .toFile()
            .apply { createNewFile() }
            .absolutePath

        val p1 =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowMmapExec()
                .allowNonThreadClone()
                .allowUnsafePrctl()
                .allowFsRead(rPath)
                .allowFsWrite(wPath)
                .block(Syscall.CONNECT)
                .build()

        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).base(p1).build()

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
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead(SandboxedPath.of("/a", true))
                .allowFsRead(SandboxedPath.of("/common", true))
                .build()
        val p2 =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead(SandboxedPath.of("/b", true))
                .allowFsRead(SandboxedPath.of("/common", true))
                .build()
        val combined = Policy.combine(p1, p2)

        assertEquals(setOf("/common"), combined.allowedFsReadPaths.map { it.value }.toSet(), "Landlock paths should be intersected")
        assertTrue(combined.enforceLandlock, "Should enforce Landlock")
    }

    @Test
    fun `combine() hierarchical Landlock paths yield most restrictive`() {
        val p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/var", true)).build()
        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/var/log", true)).build()
        val combined = Policy.combine(p1, p2)

        assertEquals(setOf("/var/log"), combined.allowedFsReadPaths.map { it.value }.toSet(), "Should yield the more restrictive path")
        assertTrue(combined.enforceLandlock)
    }

    @Test
    fun `combine() path intersection edge cases`() {
        // Test /h1 vs /h2 (should not intersect)
        val p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/h1", true)).build()
        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/h2", true)).build()
        val combined = Policy.combine(p1, p2)
        assertTrue(combined.allowedFsReadPaths.isEmpty(), "/h1 should not intersect with /h2")

        // Test nested paths
        val p3 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/d1", true)).build()
        val p4 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/d1/db", true)).build()
        assertEquals(
            setOf("/d1/db"),
            Policy
            .combine(p3, p4)
            .allowedFsReadPaths
            .map { it.value }
            .toSet(),
        )
    }

    @Test
    fun `combine() disjoint Landlock paths forces Landlock`() {
        val p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/a", true)).build()
        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/b", true)).build()

        val combined = Policy.combine(p1, p2)
        assertTrue(combined.enforceLandlock)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
    }

    @Test
    fun `combine() with no FS paths results in empty set`() {
        val p1 = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.BIND).build()
        val p2 = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.CONNECT).build()
        val combined = Policy.combine(p1, p2)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
        assertFalse(combined.enforceLandlock)
    }

    @Test
    fun `combineInternal handles empty read-path intersection`() {
        val p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/a", true)).build()
        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/b", true)).build()
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
        assertFalse(Policy.NO_EXEC.isSyscallAllowed(Syscall.IO_URING_SETUP))
        assertFalse(Policy.NO_EXEC.isSyscallAllowed(Syscall.IO_URING_ENTER))
        assertFalse(Policy.NO_EXEC_HOTSPOT.isSyscallAllowed(Syscall.IO_URING_SETUP))
        assertFalse(Policy.NO_EXEC_HOTSPOT.isSyscallAllowed(Syscall.IO_URING_ENTER))
    }

    @Test
    fun `ProcessPolicies denyProcessCreation is runtime-aware and inspectable`() {
        val hotspot = ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val native = ProcessPolicies.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)
        assertFalse(hotspot.isSyscallAllowed(Syscall.IO_URING_SETUP))
        assertFalse(hotspot.isSyscallAllowed(Syscall.IO_URING_ENTER))

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
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .base(ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
                .base(ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT))
                .build()
        val extra =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .base(ProcessPolicies.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE))
                .build()
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
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.NATIVE_IMAGE)
                .apply {
                allow(Syscall.READ, Syscall.WRITE)
            }.build()
        assertEquals(PolicyMode.ALLOW_LIST, allowListed.mode)
        val customErrno =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES))
                .allow(Syscall.READ)
                .build()
        assertEquals(PolicyMode.ALLOW_LIST, customErrno.mode)
        assertEquals(SeccompAction.ACT_ERRNO(), allowListed.defaultAction)
        assertTrue(allowListed.isSyscallAllowed(Syscall.READ))
        assertFalse(allowListed.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(allowListed.argumentRules.allowExecutableMappings)
    }

    @Test
    fun `restrictFurtherWith applies default deny to explicit allows on the other policy`() {
        val denyByDefault =
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.NATIVE_IMAGE)
                .apply {
                allow(Syscall.READ)
            }.build()
        val explicitAllow =
            ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allow(Syscall.CONNECT).build()
        val restricted = denyByDefault.restrictFurtherWith(explicitAllow)
        assertFalse(restricted.isSyscallAllowed(Syscall.CONNECT))
        assertTrue(restricted.isSyscallAllowed(Syscall.READ))
    }

    @Test
    fun `worker denyList produces thread-local policy with Landlock paths`() {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            .allowFsRead("/tmp")
            .build()
        assertTrue(policy.enforceLandlock)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp" })
        assertFalse(policy.isSyscallAllowed(Syscall.EXECVE))
    }

    @Test
    fun `Landlock intersection treats empty read grants as deny`() {
        val reads =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/secret")
                .build()
        val writesOnly =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsWrite("/tmp")
                .build()
        val combined = Policy.combine(reads, writesOnly)
        assertTrue(combined.allowedFsReadPaths.isEmpty())
        assertTrue(combined.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `Landlock composition keeps explicit OPEN denials`() {
        val landlock =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/secret")
                .build()
        val denyOpen =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
                .build()
        val combined = Policy.combine(landlock, denyOpen)
        assertFalse(combined.isSyscallAllowed(Syscall.OPEN))
        assertFalse(combined.isSyscallAllowed(Syscall.OPENAT))
        assertFalse(combined.isSyscallAllowed(Syscall.OPENAT2))
    }

    @Test
    fun `Landlock path intersection is not confused by lexicographic siblings`() {
        val child =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/foo/baz")
                .build()
        val parents =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/foo")
                .allowFsRead("/foo-bar")
                .build()
        val combined = Policy.combine(child, parents)
        assertTrue(
            combined.allowedFsReadPaths.any { it.value == "/foo/baz" },
            combined.allowedFsReadPaths.toString(),
        )
    }

    @Test
    fun `Landlock path intersection keeps descendants past lexicographic siblings`() {
        val parent =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/foo")
                .build()
        val mixed =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/foo-bar")
                .allowFsRead("/foo/bar")
                .build()
        val combined = Policy.combine(parent, mixed)
        assertTrue(
            combined.allowedFsReadPaths.any { it.value == "/foo/bar" },
            combined.allowedFsReadPaths.toString(),
        )
        assertTrue(
            combined.allowedFsReadPaths.none { it.value == "/foo-bar" },
            combined.allowedFsReadPaths.toString(),
        )
    }

    @Test
    fun `direct syscall configuration is applied on denyList and allowList`() {
        val denied =
            ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.PTRACE).build()
        assertFalse(denied.isSyscallAllowed(Syscall.PTRACE))
        val allowed =
            ProcessPolicyBuilder.allowList(RuntimeProfile.NATIVE_IMAGE).allow(Syscall.GETPID).build()
        assertTrue(allowed.isSyscallAllowed(Syscall.GETPID))
    }

    @Test
    fun `forRuntime NATIVE_IMAGE clears a prior HotSpot mmap exec flag`() {
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
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
    fun `ProcessPolicies workerFilesystem is Landlock classpath plus java home only`() {
        val hotspot = ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT)
        val native = ProcessPolicies.workerFilesystem(RuntimeProfile.NATIVE_IMAGE)
        val javaHome = System.getProperty("java.home")
        assertTrue(hotspot.enforceLandlock)
        assertTrue(hotspot.allowedFsReadPaths.any { it.value == javaHome })
        assertTrue(hotspot.allowedFsReadPaths.none { it.value == "/etc" || it.value == "/etc/passwd" })
        assertTrue(hotspot.argumentRules.allowExecutableMappings)
        assertFalse(native.argumentRules.allowExecutableMappings)
    }

    @Test
    fun `plus operator works and resolves types correctly`() {
        val p1 = Policy.NO_EXEC
        val p2 = Policy.NO_NETWORK
        val p3 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead(SandboxedPath.of("/t1", true)).build()

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
        val p = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .build()

        assertFalse(p.isSyscallAllowed(Syscall.OPEN))
        assertFalse(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should be blocked to prevent bypass when Landlock is not active")
    }

    @Test
    fun `io_uring_setup remains allowed if open and openat are allowed`() {
        val p = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allow(Syscall.OPEN)
            .allow(Syscall.OPENAT)
            .allow(Syscall.IO_URING_SETUP)
            .build()

        assertTrue(p.isSyscallAllowed(Syscall.OPEN))
        assertTrue(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should remain allowed when open and openat are allowed")
    }

    @Test
    fun `io_uring_setup remains allowed if Landlock is active`() {
        val p = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .allowFsRead("/some/path")
            .build()

        assertTrue(p.enforceLandlock)
        assertTrue(p.isSyscallAllowed(Syscall.IO_URING_SETUP), "io_uring_setup should remain allowed when Landlock is active to enforce path limits")
    }

    @Test
    fun `io_uring_setup blocking is resolved correctly during policy combination`() {
        val p1 = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .build()
        val p2 = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allow(Syscall.OPEN)
            .allow(Syscall.IO_URING_SETUP)
            .build()

        val combined = Policy.combine(p1, p2)
        assertFalse(combined.isSyscallAllowed(Syscall.IO_URING_SETUP), "combined policy should block io_uring_setup because open is restricted overall and Landlock is not active")
    }

    @Test
    fun `hasSupervisedSyscalls is true when defaultAction is ACT_NOTIFY`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_NOTIFY)
            .build()
        assertTrue(policy.definition.hasSupervisedSyscalls, "hasSupervisedSyscalls should be true when defaultAction is ACT_NOTIFY")
    }

    @Test
    fun `hasSupervisedSyscalls is false when defaultAction is ACT_ALLOW`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .build()
        assertFalse(policy.definition.hasSupervisedSyscalls, "hasSupervisedSyscalls should be false when defaultAction is ACT_ALLOW and no syscalls use ACT_NOTIFY")
    }

    @Test
    fun `enforceLandlock is true when Landlock paths are specified`() {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowFsRead("/tmp")
            .build()
        assertTrue(policy.enforceLandlock, "enforceLandlock should be true when Landlock paths are specified")
    }

    @Test
    fun `enforceLandlock is false when no Landlock paths are specified`() {
        val emptyPolicy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        assertFalse(emptyPolicy.enforceLandlock, "enforceLandlock should be false when no Landlock paths are specified")
    }

    @Test
    fun `combine prefers block companion ERRNO over TRACE regardless of order`() {
        val deny =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.OPEN)
                .build()
        val trace =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .addAction(SeccompAction.ACT_TRACE(1), Syscall.OPEN)
                .build()

        assertEquals(SeccompAction.ACT_ERRNO(), deny.syscallActions[Syscall.OPEN])
        assertEquals(SeccompAction.ACT_ERRNO(), Policy.combine(deny, trace).syscallActions[Syscall.OPEN])
        assertEquals(SeccompAction.ACT_ERRNO(), Policy.combine(trace, deny).syscallActions[Syscall.OPEN])
        assertEquals(SeccompAction.ACT_ERRNO(), Policy.restrictFurtherWith(trace, deny).syscallActions[Syscall.OPEN])

        val openNr = Syscall.OPEN.numberFor(Arch.current())
        assertEquals(
            SeccompAction.ACT_ERRNO(),
            Policy.combine(trace, deny).syscallActionNumbers(Arch.current())[openNr],
        )
    }

    @Test
    fun `combine prefers ACT_ERRNO instance over TRACE for defaults`() {
        val errnoDefault =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES))
                .build()
        val traceDefault =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_TRACE(1))
                .build()

        assertEquals(
            SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES),
            Policy.combine(errnoDefault, traceDefault).defaultAction,
        )
        assertEquals(
            SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES),
            Policy.combine(traceDefault, errnoDefault).defaultAction,
        )
    }

    @Test
    fun `worker filesystem mutation does not alias an already built policy`(
        @TempDir tempDir: java.nio.file.Path,
    ) {
        // Process builders cannot acquire filesystem state; packaged compile fixtures enforce
        // that boundary. Worker mutation must preserve previously frozen definitions.
        val rPath = tempDir.resolve("read").toString()
        val base = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
        val untouched = base.build()

        base.allowFsRead(rPath)
        assertTrue(base.build().allowedFsReadPaths.isNotEmpty(), "Ignored worker mutator result must retain the grant")
        assertFalse(untouched.definition.allowedFsReadPaths.isNotEmpty(), "Already built policy must not gain FS paths")
        assertTrue(untouched.definition.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `fixed worker builder carries fs state and stays thread-local typed`(
        @TempDir tempDir: java.nio.file.Path,
    ) {
        val rPath = tempDir.resolve("r").toString()
        val wPath = tempDir.resolve("w").toString()

        val promoted: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ALLOW)
                .allowFsRead(rPath)
                .allowFsWrite(wPath)
                .build()

        assertTrue(promoted.definition.allowedFsReadPaths.any { it.value == SandboxedPath.of(rPath, true).value })
        assertTrue(promoted.definition.allowedFsWritePaths.any { it.value == SandboxedPath.of(wPath, true).value })
    }
}
