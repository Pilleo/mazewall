package io.mazewall.lockdown

import io.mazewall.lockdown.internal.enforcer.internal.seccomp.BpfFilter
import io.mazewall.lockdown.internal.platform.syscall.Arch
import io.mazewall.lockdown.internal.platform.syscall.SeccompAction
import io.mazewall.lockdown.internal.platform.syscall.Syscall
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProcessPolicyTest {
    @Test
    fun `denyProcessCreation configures exact syscalls and runtime executable mapping rules`() {
        val hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val nativeImage = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)

        val expectedBlocked =
            setOf(
                Syscall.EXECVE,
                Syscall.EXECVEAT,
                Syscall.MEMFD_CREATE,
                Syscall.IO_URING_SETUP,
                Syscall.IO_URING_ENTER,
            )

        assertEquals(expectedBlocked, ProcessFilterInstaller.DENY_PROCESS_CREATION_BLOCKED_SYSCALLS)
        assertEquals(SeccompAction.ACT_ALLOW, hotspot.definition.defaultAction)
        assertEquals(SeccompAction.ACT_ALLOW, nativeImage.definition.defaultAction)
        assertEquals(expectedBlocked, hotspot.definition.syscallActions.keys)
        assertEquals(expectedBlocked, nativeImage.definition.syscallActions.keys)
        expectedBlocked.forEach { syscall ->
            assertEquals(SeccompAction.ACT_ERRNO(), hotspot.definition.syscallActions[syscall])
            assertEquals(SeccompAction.ACT_ERRNO(), nativeImage.definition.syscallActions[syscall])
        }

        assertTrue(hotspot.definition.allowMmapExec)
        assertFalse(nativeImage.definition.allowMmapExec)
        assertFalse(hotspot.definition.enforceLandlock)
        assertFalse(nativeImage.definition.enforceLandlock)
        assertEquals(hotspot, ProcessPolicy.denyProcessCreation())
    }

    @Test
    fun `elasticsearch openSearch mazewall and strict presets map expected blocked syscalls`() {
        val elastic = ProcessPolicy.elasticsearch()
        val openSearch = ProcessPolicy.openSearch()
        val mazewall = ProcessPolicy.fromPreset(LockdownPreset.MAZEWALL)
        val strict = ProcessPolicy.fromPreset(LockdownPreset.MAZEWALL_STRICT, RuntimeProfile.NATIVE_IMAGE)

        assertEquals(
            setOf(
                LockdownSyscall.EXECVE,
                LockdownSyscall.EXECVEAT,
                LockdownSyscall.FORK,
                LockdownSyscall.VFORK,
            ),
            elastic.blockedSyscalls(),
        )
        assertEquals(
            setOf(
                LockdownSyscall.EXECVE,
                LockdownSyscall.EXECVEAT,
                LockdownSyscall.FORK,
                LockdownSyscall.VFORK,
                LockdownSyscall.PTRACE,
                LockdownSyscall.PROCESS_VM_READV,
                LockdownSyscall.PROCESS_VM_WRITEV,
                LockdownSyscall.PIDFD_GETFD,
            ),
            openSearch.blockedSyscalls(),
        )
        assertEquals(ProcessPolicy.denyProcessCreation(), mazewall)
        assertEquals(
            ProcessFilterInstaller.ELASTICSEARCH_BLOCKED_SYSCALLS,
            elastic.definition.syscallActions.keys,
        )
        assertEquals(
            ProcessFilterInstaller.OPENSEARCH_BLOCKED_SYSCALLS,
            openSearch.definition.syscallActions.keys,
        )
        assertEquals(
            ProcessFilterInstaller.MAZEWALL_STRICT_BLOCKED_SYSCALLS,
            strict.definition.syscallActions.keys,
        )
        assertEquals(RuntimeProfile.HOTSPOT_JIT, elastic.runtimeProfile())
        assertEquals(RuntimeProfile.NATIVE_IMAGE, strict.runtimeProfile())
        assertFalse(strict.definition.allowMmapExec)
    }

    @Test
    fun `block and unblock customize policies immutably without aliasing prior views`() {
        val base = ProcessPolicy.elasticsearch(RuntimeProfile.HOTSPOT_JIT)
        val exposed = base.blockedSyscalls()

        assertFailsWith<UnsupportedOperationException> {
            (exposed as MutableSet<LockdownSyscall>).add(LockdownSyscall.BPF)
        }

        val hardened =
            base.block(
                LockdownSyscall.PTRACE,
                LockdownSyscall.PROCESS_VM_READV,
                LockdownSyscall.PROCESS_VM_WRITEV,
                LockdownSyscall.PIDFD_GETFD,
            )
        assertEquals(ProcessPolicy.openSearch(RuntimeProfile.HOTSPOT_JIT), hardened)
        assertEquals(4, base.blockedSyscalls().size)
        assertEquals(8, hardened.blockedSyscalls().size)

        val customized =
            hardened
                .unblock(LockdownSyscall.PTRACE, LockdownSyscall.VFORK)
                .block(listOf(LockdownSyscall.BPF, LockdownSyscall.USERFAULTFD))
                .unblock(listOf(LockdownSyscall.USERFAULTFD))

        assertFalse(LockdownSyscall.PTRACE in customized.blockedSyscalls())
        assertFalse(LockdownSyscall.VFORK in customized.blockedSyscalls())
        assertTrue(LockdownSyscall.BPF in customized.blockedSyscalls())
        assertFalse(LockdownSyscall.USERFAULTFD in customized.blockedSyscalls())
        assertEquals(
            customized.blockedSyscalls().map { it.internalSyscall }.toSet(),
            customized.definition.syscallActions.keys,
        )
        assertEquals(8, hardened.blockedSyscalls().size)
    }

    @Test
    fun `no LockdownSyscall overlaps critical JVM coordination syscalls on supported architectures`() {
        listOf(Arch.AMD64, Arch.AARCH64).forEach { arch ->
            val criticalNrs = BpfFilter.getJvmCriticalNrs(arch)
            LockdownSyscall.entries.forEach { syscall ->
                val nr = syscall.internalSyscall.numberFor(arch)
                if (nr >= 0) {
                    assertFalse(
                        nr in criticalNrs,
                        "LockdownSyscall.$syscall (nr=$nr) must never overlap JVM critical syscalls on ${arch.name}",
                    )
                }
            }
        }
    }

    @Test
    fun `equality hashCode and toString reflect runtime profile and definition`() {
        val first = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val second = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val different = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, different)
        assertEquals("ProcessPolicy(runtime=HOTSPOT_JIT)", first.toString())
        assertEquals("ProcessPolicy(runtime=NATIVE_IMAGE)", different.toString())
    }
}
