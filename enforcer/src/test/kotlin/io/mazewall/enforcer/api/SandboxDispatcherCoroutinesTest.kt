package io.mazewall.enforcer.api

import io.mazewall.Policy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import io.mazewall.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import io.mazewall.MockPlatformProvider
import io.mazewall.MockNativeEngine
import io.mazewall.LinuxNative

class SandboxDispatcherCoroutinesTest {

    @BeforeEach
    fun setup() {
        val mockProvider = MockPlatformProvider()
        mockProvider.mockOsName = "Linux"
        mockProvider.mockKernelSeccompSupport = true
        mockProvider.mockSeccompMode = io.mazewall.SeccompMode.Filter
        Platform.setProvider(mockProvider)

        val mockProcess = object : io.mazewall.MockNativeProcess() {
            override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (command is io.mazewall.core.PrctlCommand.GetSeccomp) {
                    return LinuxNative.SyscallResult.Success(2L)
                }
                return super.prctl(command)
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess)
        LinuxNative.setEngine(mockEngine)
    }

    @AfterEach
    fun teardown() {
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
    }

    @Test
    @io.mazewall.NeedsFreshJvm
    fun `test executeSuspend with coroutines`() = runBlocking {
        val policy = Policy.builder().build()
        val result = SandboxDispatcher.executeSuspend(policy) {
            "success"
        }
        assertEquals("success", result)
    }

    @Test
    @io.mazewall.NeedsFreshJvm
    fun `test executeBlock with callables`() {
        val policy = Policy.builder().build()
        val result = SandboxDispatcher.executeBlock(policy) {
            "success-callable"
        }
        assertEquals("success-callable", result)
    }
}
