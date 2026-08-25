package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.RealPlatformProvider
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.PolicyState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InstallationReceiptMatrixTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    data class MatrixScenario(
        val name: String,
        val policy: Policy<*, PolicyState.Uncompiled>,
        val seccompError: Int? = null,
        val fallback: String? = null,
        val expectedInstalled: Boolean? = null,
        val expectedLandlockApplied: Boolean? = null,
        val expectThrows: Boolean = false,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun matrixScenarios(): Stream<MatrixScenario> {
            val seccompOnlyPolicy = Policy.builder().block(Syscall.EXECVE).build()
            val landlockPolicy = Policy.builder().allowFsRead("/tmp").build()
            val emptyPolicy = Policy.builder().build()

            return Stream.of(
                MatrixScenario(
                    name = "case A - seccomp only policy with seccomp mock success",
                    policy = seccompOnlyPolicy,
                    expectedInstalled = true,
                    expectedLandlockApplied = false,
                ),
                MatrixScenario(
                    name = "case B - Landlock and seccomp policy both mock success",
                    policy = landlockPolicy,
                    expectedInstalled = true,
                ),
                MatrixScenario(
                    name = "case D - Landlock apply succeeds seccomp fails with FAIL fallback",
                    policy = landlockPolicy,
                    seccompError = 22,
                    fallback = "FAIL",
                    expectThrows = true,
                ),
                MatrixScenario(
                    name = "case E - Landlock apply succeeds seccomp fails with WARN_AND_BYPASS",
                    policy = landlockPolicy,
                    seccompError = 22,
                    fallback = "WARN_AND_BYPASS",
                    expectedInstalled = false,
                ),
                MatrixScenario(
                    name = "case F - no Landlock seccomp fails with WARN_AND_BYPASS",
                    policy = seccompOnlyPolicy,
                    seccompError = 22,
                    fallback = "WARN_AND_BYPASS",
                    expectedInstalled = false,
                    expectedLandlockApplied = false,
                ),
                MatrixScenario(
                    name = "case G - policy with no Landlock and no extra blocks",
                    policy = emptyPolicy,
                    expectedLandlockApplied = false,
                ),
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrixScenarios")
    fun `verify installation receipt decision matrix`(scenario: MatrixScenario) {
        if (scenario.fallback != null) {
            System.setProperty("io.mazewall.fallback", scenario.fallback)
        }

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.process.onPrctl = { command ->
            if (scenario.seccompError != null) {
                if (command is io.mazewall.core.PrctlCommand.SetSeccomp) {
                    LinuxNative.SyscallResult.Error(scenario.seccompError, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            } else {
                if (command is io.mazewall.core.PrctlCommand.GetSeccomp) {
                    LinuxNative.SyscallResult.Success(2L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        }
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (scenario.seccompError != null && nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(scenario.seccompError, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        if (scenario.expectThrows) {
            assertThrows<Exception> {
                ContainedExecutors.installOnCurrentThread(scenario.policy)
            }
        } else {
            val receipt = ContainedExecutors.installOnCurrentThread(scenario.policy)
            if (scenario.expectedInstalled != null) {
                assertEquals(scenario.expectedInstalled, receipt.installed, "receipt.installed mismatch")
            }
            if (scenario.expectedLandlockApplied != null) {
                assertEquals(scenario.expectedLandlockApplied, receipt.landlockApplied, "receipt.landlockApplied mismatch")
            }
        }
    }
}
