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
import io.mazewall.ffi.memory.ManagedSegment
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
                    val res =
                    LinuxNative.process.prctl(
                        PrctlCommand.SetName(NativeArg.MemoryArg(ManagedSegment.NULL))
                    )

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
    fun `test that mmap with PROT_EXEC is blocked by default and standard mmap is preserved`() {
        val policy =
            Policy
                .builder()
                .block(Syscall.MMAP) // Even if we block MMAP, critical bypass allows it for non-exec
                .build()

        val execResult = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        val readWriteResult = AtomicReference<LinuxNative.SyscallResult<Long, *>>()
        val error = AtomicReference<Throwable>()

        val thread =
            Thread {
                try {
                    ContainedExecutors.installOnCurrentThread(policy)
                    
                    // 1. Calling mmap with PROT_EXEC (7) should be blocked (EPERM)
                    execResult.set(

LinuxNative.fileSystem.mmap(0, 4096, 7, 0x22, -1, 0)

                    )
                    
                    // 2. Calling mmap with PROT_READ | PROT_WRITE (3) should succeed because MMAP is critical
                    readWriteResult.set(

LinuxNative.fileSystem.mmap(0, 4096, 3, 0x22, -1, 0)

                    )
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
        thread.start()
        thread.join()

        if (error.get() != null) throw error.get()

        val execRes = execResult.get()
        assertTrue(execRes is LinuxNative.SyscallResult.Error && execRes.errno == 1, "MMAP with PROT_EXEC should have been blocked with EPERM, got $execRes")

        val rwRes = readWriteResult.get()
        assertTrue(rwRes is LinuxNative.SyscallResult.Success, "Standard MMAP (non-exec) should succeed as it is JVM-critical, got $rwRes")
    }
}
