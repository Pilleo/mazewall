package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.NativeArg
import io.mazewall.core.PrctlCommand
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpfHardeningTest : BaseIntegrationTest() {
    @Test
    fun `test that prctl can be blocked when inspection is explicitly disabled`() {
        val policy =
            Policy
                .builder()
                .allowUnsafePrctl() // Disable argument inspection (previously would cause PRCTL to be skipped in block list)
                .block(Syscall.PRCTL) // Explicitly block it
                .build()

        val result = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        val error = AtomicReference<Throwable>()

        val thread =
            Thread {
                try {
                    ContainedExecutors.installOnCurrentThread(policy)
                    // PR_SET_NAME = 15
                    val res = LinuxNative.withTransaction {
                        LinuxNative.process.prctl(
                            PrctlCommand.SetName(NativeArg.MemoryArg(MemorySegment.NULL))
                        )
                    }
                    result.set(res)
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        thread.start()
        thread.join()

        if (error.get() != null) throw error.get()

        val res = result.get()
        assertTrue(res is LinuxNative.SyscallResult.Error && res.errno == 1, "PRCTL should have been blocked with EPERM, got $res")
    }

    @Test
    fun `test that mmap with PROT_EXEC can be blocked when inspection is explicitly disabled`() {
        val policy =
            Policy
                .builder()
                .allowMmapExec() // Disable argument inspection
                .block(Syscall.MMAP) // Explicitly block it
                .build()

        val result = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        val error = AtomicReference<Throwable>()

        val thread =
            Thread {
                try {
                    ContainedExecutors.installOnCurrentThread(policy)
                    val res =
                        LinuxNative.withTransaction {
                            LinuxNative.fileSystem.mmap(0, 4096, 7, 0x22, -1, 0)
                        }
                    result.set(res)
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        thread.start()
        thread.join()

        if (error.get() != null) throw error.get()

        val res = result.get()
        assertTrue(res is LinuxNative.SyscallResult.Error && res.errno == 1, "MMAP should have been blocked with EPERM, got $res")
    }
}
