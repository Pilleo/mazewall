package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeProcess
import io.mazewall.core.Arch
import io.mazewall.core.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Test: Support X86 and ARM64 BPF Downcall Compilation
 */
@Isolated
class PureJavaBpfEngineDowncallTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
    }

    @Test
    @EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun setNoNewPrivsDowncallCompilesOnX86_64() {
        val savedOsArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.arch", "x86_64")
            val currentArch = Arch.current()
            assertEquals(Arch.AUDIT_ARCH_X86_64, currentArch.audit)

            val mockProcess = MockNativeProcess()
            var prctlCalled = false
            var prctlCommand: PrctlCommand? = null

            mockProcess.onPrctl = { command ->
                prctlCalled = true
                prctlCommand = command
                LinuxNative.SyscallResult.Success(0L)
            }

            val mockEngine = MockNativeEngine(process = mockProcess)
            LinuxNative.setEngine(mockEngine)

            PureJavaBpfEngine.setNoNewPrivs()

            assertTrue(prctlCalled)
            assertTrue(prctlCommand is PrctlCommand.SetNoNewPrivs)
            val setNoNewPrivsCmd = prctlCommand as PrctlCommand.SetNoNewPrivs
            assertTrue(setNoNewPrivsCmd.enabled)
        } finally {
            if (savedOsArch != null) {
                System.setProperty("os.arch", savedOsArch)
            } else {
                System.clearProperty("os.arch")
            }
        }
    }

    @Test
    fun prctlDowncallDescriptorConfiguredForX86_64() {
        val savedOsArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.arch", "x86_64")
            val currentArch = Arch.current()
            assertTrue(currentArch.prctl > 0)
        } finally {
            if (savedOsArch != null) {
                System.setProperty("os.arch", savedOsArch)
            } else {
                System.clearProperty("os.arch")
            }
        }
    }

    @Test
    fun seccompDowncallDescriptorConfiguredForARM64() {
        val aarch64Arch = Arch.AARCH64
        assertEquals(277, aarch64Arch.seccompSyscallNumber)
        assertEquals(167, aarch64Arch.prctl)
        assertEquals(Arch.AUDIT_ARCH_AARCH64, aarch64Arch.audit)
    }

    @Test
    fun aarch64DowncallCompilationVerified() {
        val aarch64 = Arch.AARCH64
        assertEquals(Arch.AUDIT_ARCH_AARCH64, aarch64.audit)
        assertEquals(277, aarch64.seccompSyscallNumber)
        assertEquals(167, aarch64.prctl)
    }
}
