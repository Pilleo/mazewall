package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeProcess
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import io.mazewall.core.PrctlCommand
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Test: Support X86_64 BPF Downcall Compilation
 *
 * Tests that PureJavaBpfEngine correctly performs BPF downcall compilation
 * on X86_64 architecture using downcalls.
 */
@Isolated
class PureJavaBpfEngineX86DowncallTest {

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
    fun `test X86_64 architecture constants`() {
        // Verify X86_64 architecture has correct syscall numbers
        assertEquals(317, Arch.AMD64.seccompSyscallNumber)
        assertEquals(0xC000003E.toInt(), Arch.AMD64.audit)
        assertEquals(Arch.AUDIT_ARCH_X86_64, Arch.AMD64.audit)
    }

    @Test
    fun `test X86_64 BPF downcall compilation succeeds`() {
        savedOsArch = System.getProperty("os.arch")
        try {
            // Set system property to simulate X86_64 architecture
            System.setProperty("os.arch", "x86_64")
            
            val mockProcess = MockNativeProcess()
            val mockMemory = MockNativeMemory()
            
            // Mock successful syscall
            val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
            mockEngine.onSyscall = { nr, a1, a2, a3, a4, a5, a6 ->
                // Verify X86_64 seccomp syscall number (317)
                assertEquals(317L, nr, "Expected X86_64 seccomp syscall number 317")
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
            val compiled = policy.definition.compile(Arch.AMD64)
            
            // This should trigger the downcall compilation path for X86_64
            PureJavaBpfEngine.install(compiled)
        } finally {
            // Property will be restored in tearDown
        }
    }

    @Test
    fun `test X86_64 BPF downcall with prctl fallback`() {
        savedOsArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.arch", "x86_64")
            
            val mockProcess = MockNativeProcess()
            val mockMemory = MockNativeMemory()
            
            // Mock seccomp(2) syscall as unsupported to trigger prctl fallback
            val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
            mockEngine.onSyscall = { _, _, _, _, _, _, _ ->
                LinuxNative.SyscallResult.Error(NativeConstants.ENOSYS, -1L)
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
            val compiled = policy.definition.compile(Arch.AMD64)
            
            PureJavaBpfEngine.install(compiled)
            
            // Verify that prctl fallback was used
            assertTrue(prctlSetSeccompCalled, "prctl SetSeccomp should be called as fallback on X86_64")
        } finally {
            // Property will be restored in tearDown
        }
    }

    @Test
    fun `test setNoNewPrivs downcall compilation on X86_64`() {
        savedOsArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.arch", "x86_64")
            
            val mockProcess = MockNativeProcess()
            var setNoNewPrivsCalled = false
            var prctlCommand: PrctlCommand? = null
            
            mockProcess.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) {
                    setNoNewPrivsCalled = true
                    prctlCommand = command
                    assertTrue(command.enabled, "SetNoNewPrivs should be enabled")
                }
                LinuxNative.SyscallResult.Success(0L)
            }
            
            val mockEngine = MockNativeEngine(process = mockProcess)
            LinuxNative.setEngine(mockEngine)
            
            // Directly call setNoNewPrivs to test downcall compilation
            PureJavaBpfEngine.setNoNewPrivs()
            
            assertTrue(setNoNewPrivsCalled, "setNoNewPrivs should be called")
            assertTrue(prctlCommand is PrctlCommand.SetNoNewPrivs, "Command should be SetNoNewPrivs")
        } finally {
        }
    }
}
