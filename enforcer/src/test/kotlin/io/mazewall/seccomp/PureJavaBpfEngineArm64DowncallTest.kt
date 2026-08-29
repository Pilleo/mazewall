package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeProcess
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import io.mazewall.core.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Test: Support ARM64 BPF Downcall Compilation
 *
 * Tests that PureJavaBpfEngine correctly performs BPF downcall compilation
 * on ARM64 (AARCH64) architecture using FFM downcalls.
 */
class PureJavaBpfEngineArm64DowncallTest {

    private var savedOsArch: String? = null

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
        // Restore the original os.arch property
        if (savedOsArch != null) {
            System.setProperty("os.arch", savedOsArch)
        } else {
            System.clearProperty("os.arch")
        }
        savedOsArch = null
    }

    @Test
    fun `test ARM64 architecture constants`() {
        // Verify ARM64 (AARCH64) architecture has correct syscall numbers
        assertEquals(277, Arch.AARCH64.seccompSyscallNumber)
        assertEquals(0xC00000B7.toInt(), Arch.AARCH64.audit)
        assertEquals(Arch.Companion.AUDIT_ARCH_AARCH64, Arch.AARCH64.audit)
    }

    @Test
    fun `test ARM64 BPF downcall compilation succeeds`() {
        savedOsArch = System.getProperty("os.arch")
        try {
            // Set system property to simulate ARM64 architecture
            System.setProperty("os.arch", "arm64")
            
            val mockProcess = MockNativeProcess()
            val mockMemory = MockNativeMemory()
            
            // Mock successful syscall
            val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
            mockEngine.onSyscall = { nr, a1, a2, a3, a4, a5, a6 ->
                // Verify ARM64 seccomp syscall number (277)
                assertEquals(277L, nr, "Expected ARM64 seccomp syscall number 277")
                LinuxNative.SyscallResult.Success(0L)
            }
            mockProcess.onPrctl = { command ->
                if (command is PrctlCommand.GetSeccomp) {
                    LinuxNative.SyscallResult.Success(2L) // SECCOMP_MODE_FILTER
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
            LinuxNative.setEngine(mockEngine)

            val policy = Policy.builder().build()
            val compiled = policy.definition.compile(Arch.AARCH64)
            
            // This should trigger the downcall compilation path for ARM64
            PureJavaBpfEngine.install(compiled)
        } finally {
            // Property will be restored in tearDown
        }
    }

    @Test
    fun `test ARM64 BPF downcall with prctl fallback`() {
        savedOsArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.arch", "arm64")
            
            val mockProcess = MockNativeProcess()
            val mockMemory = MockNativeMemory()
            
            // Mock seccomp(2) syscall as unsupported to trigger prctl fallback
            val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
            mockEngine.onSyscall = { _, _, _, _, _, _, _ ->
                LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1L)
            }
            
            var prctlSetSeccompCalled = false
            mockProcess.onPrctl = { command ->
                if (command is PrctlCommand.SetSeccomp) {
                    prctlSetSeccompCalled = true
                    LinuxNative.SyscallResult.Success(0L)
                } else if (command is PrctlCommand.GetSeccomp) {
                    LinuxNative.SyscallResult.Success(2L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
            LinuxNative.setEngine(mockEngine)

            val policy = Policy.builder().build()
            val compiled = policy.definition.compile(Arch.AARCH64)
            
            PureJavaBpfEngine.install(compiled)
            
            // Verify that prctl fallback was used
            assertTrue(prctlSetSeccompCalled, "prctl SetSeccomp should be called as fallback on ARM64")
        } finally {
            // Property will be restored in tearDown
        }
    }
}
