package io.mazewall.lockdown

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.lockdown.internal.ffi.NativeConstants
import io.mazewall.lockdown.internal.platform.sys.LinuxNative
import io.mazewall.lockdown.internal.platform.syscall.Arch
import io.mazewall.lockdown.internal.platform.syscall.NativeArg
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@NeedsFreshJvm
@Tag("needs-fresh-jvm")
class MazewallLockdownPresetIntegrationTest {
    @Test
    fun `openSearch preset with unblock and block enforces exact kernel verdicts`() {
        val arch = Arch.current()
        val policy =
            ProcessPolicy
                .openSearch(RuntimeProfile.HOTSPOT_JIT)
                .unblock(LockdownSyscall.PTRACE)
                .block(LockdownSyscall.BPF)

        val installation = Mazewall.installOnProcess(policy)
        assertEquals(1, installation.filterDepth)

        fun invokeRaw(
            nr: Int,
            a0: Long = 0L,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.raw.syscall(
                nr.toLong(),
                NativeArg.LongArg(a0),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
            )

        // Child creation via JVM ProcessBuilder and raw execve/execveat must be blocked with EPERM.
        val pbFailure =
            assertFailsWith<IOException>("ProcessBuilder must fail under customized OpenSearch preset") {
                ProcessBuilder("/bin/echo", "blocked-opensearch").start()
            }
        assertTrue(
            pbFailure.message.orEmpty().let { msg ->
                msg.contains("Operation not permitted", ignoreCase = true) ||
                    msg.contains("error=1") ||
                    msg.contains("errno=1")
            },
            "Expected EPERM from ProcessBuilder, got: ${pbFailure.message}",
        )

        val expectedEperm =
            listOf(
                "execve" to arch.execve,
                "execveat" to arch.execveat,
                "fork" to arch.fork,
                "vfork" to arch.vfork,
                "process_vm_readv" to arch.processVmReadv,
                "process_vm_writev" to arch.processVmWritev,
                "pidfd_getfd" to arch.pidfdGetFd,
                "bpf" to arch.bpf,
            ).filter { (_, nr) -> nr >= 0 }

        expectedEperm.forEach { (name, nr) ->
            val res = invokeRaw(nr)
            val err = assertIs<LinuxNative.SyscallResult.Error<*>>(res, "$name (nr=$nr) must be denied")
            assertEquals(NativeConstants.EPERM, err.errno, "$name (nr=$nr) must fail with EPERM")
        }

        // Unblocked ptrace must reach the kernel (invalid request -1 returns EIO/EINVAL rather than seccomp EPERM).
        val ptraceRes = invokeRaw(arch.ptrace, a0 = -1L)
        val ptraceErr = assertIs<LinuxNative.SyscallResult.Error<*>>(ptraceRes)
        assertNotEquals(NativeConstants.EPERM, ptraceErr.errno, "Unblocked ptrace must not be denied by seccomp EPERM")
    }
}
