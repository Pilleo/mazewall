package io.mazewall.enforcer.api

import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedScopeBuilderTest {
    @Test
    fun `imperative filesystem mutations retain the rule on the same worker builder`() {
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        assertSame(builder, builder.allowFsRead("/input"))
        assertSame(builder, builder.allowFsWrite("/output"))

        val policy = builder.build()
        assertEquals(setOf(SandboxedPath.of("/input", allowNonExistent = true)), policy.allowedFsReadPaths)
        assertEquals(setOf(SandboxedPath.of("/output", allowNonExistent = true)), policy.allowedFsWritePaths)
        assertTrue(policy.definition.enforceLandlock)
    }

    @Test
    fun `fluent and apply construction have identical effective restrictions`() {
        val fluent = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .block(Syscall.EXECVE)
            .allowFsRead("/input")
            .allowFsWrite("/output")
            .build()
        val imperative = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .apply {
            block(Syscall.EXECVE)
            allowFsRead("/input")
            allowFsWrite("/output")
        }.build()

        assertEquals(fluent.definition, imperative.definition)
    }

    @Test
    fun `explicit deny all differs from unrestricted and survives template reconstruction`() {
        val unrestricted = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val denied = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .filesystem(FileSystemPolicy.DenyAll)
            .build()
        val rebuilt = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).base(denied).build()

        assertFalse(unrestricted.definition.enforceLandlock)
        assertTrue(denied.definition.enforceLandlock)
        assertTrue(rebuilt.definition.enforceLandlock)
        assertTrue(rebuilt.allowedFsReadPaths.isEmpty())
        assertTrue(rebuilt.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `filesystem assignment and later mutation cannot alter an earlier snapshot`() {
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead("/input")
        val first = builder.build()
        builder.filesystem(FileSystemPolicy.DenyAll)
        val denied = builder.build()
        builder.filesystem(FileSystemPolicy.Unrestricted)

        assertEquals(1, first.allowedFsReadPaths.size)
        assertTrue(first.definition.enforceLandlock)
        assertTrue(denied.allowedFsReadPaths.isEmpty())
        assertTrue(denied.definition.enforceLandlock)
        assertFalse(builder.build().definition.enforceLandlock)
    }

    @Test
    fun `explicit list modes preserve JVM floor and runtime mapping settings`() {
        val process = ProcessPolicyBuilder.allowList(RuntimeProfile.HOTSPOT_JIT).build()
        val worker = WorkerPolicyBuilder.allowList(RuntimeProfile.NATIVE_IMAGE).build()
        assertEquals(PolicyMode.ALLOW_LIST, process.mode)
        assertEquals(PolicyMode.ALLOW_LIST, worker.mode)
        assertTrue(process.isSyscallAllowed(Syscall.PREAD64))
        assertTrue(worker.isSyscallAllowed(Syscall.PREAD64))
        assertTrue(process.allowMmapExec)
        assertFalse(worker.allowMmapExec)
        assertEquals(PolicyMode.DENY_LIST, ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build().mode)
    }

    @Test
    fun `Boolean argument settings assign state and preserve built snapshots`() {
        val builder = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec(true)
            .allowNonThreadClone(true)
            .allowUnsafePrctl(true)
        val enabled = builder.build()
        val disabled = builder
            .allowMmapExec(false)
            .allowNonThreadClone(false)
            .allowUnsafePrctl(false)
            .build()
        assertTrue(enabled.allowMmapExec)
        assertTrue(enabled.allowNonThreadClone)
        assertTrue(enabled.allowUnsafePrctl)
        assertFalse(disabled.allowMmapExec)
        assertFalse(disabled.allowNonThreadClone)
        assertFalse(disabled.allowUnsafePrctl)
    }

    @Test
    fun `explicit resource intent freezes caller sets and empty intent remains active`() {
        val reads = mutableSetOf(SandboxedPath.of("/input", allowNonExistent = true))
        val intent = FileSystemPolicy.AllowResources(reads, emptySet())
        reads.clear()
        val allowed = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(intent).build()
        val empty = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .filesystem(FileSystemPolicy.AllowResources(emptySet(), emptySet()))
            .build()

        assertEquals(1, allowed.allowedFsReadPaths.size)
        assertTrue(empty.definition.enforceLandlock)
        assertTrue(empty.allowedFsReadPaths.isEmpty())
    }
}
