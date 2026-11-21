package io.mazewall.lockdown

import io.mazewall.lockdown.internal.platform.syscall.SeccompAction
import io.mazewall.lockdown.internal.platform.syscall.Syscall
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
