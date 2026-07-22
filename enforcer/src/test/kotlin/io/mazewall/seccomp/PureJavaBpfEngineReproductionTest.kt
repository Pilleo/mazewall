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
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.ManagedSegment
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PureJavaBpfEngineReproductionTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
    }

    @Test
    fun `reproduce no_new_privs being set before filter building failure`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()
        val prctlCommands = mutableListOf<PrctlCommand>()
        mockProcess.onPrctl = { _, command ->
            prctlCommands.add(command)
            mockProcess.prctlResult
        }
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena)
            override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                throw RuntimeException("Simulated filter building failure")
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        assertFailsWith<RuntimeException> {
            PureJavaBpfEngine.install(compiled)
        }

        // After the fix, this should be null because buildFilter() is called before setNoNewPrivs()
        val calledSetNoNewPrivs = prctlCommands.any { it is PrctlCommand.SetNoNewPrivs }
        assertFalse(calledSetNoNewPrivs, "PR_SET_NO_NEW_PRIVS should NOT have been called after the fix")
    }

    @Test
    fun `test error propagation and state logging on JVM Error`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena)
            override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                throw AssertionError("Simulated fatal AssertionError")
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        assertFailsWith<AssertionError> {
            PureJavaBpfEngine.install(compiled)
        }

        val currentState = io.mazewall.enforcer.ThreadStateRegistry.state.engineState
        assertTrue(currentState is SeccompInstallationState.Failed, "Engine state should transition to Failed under fatal JVM Error")
        kotlin.test.assertEquals("buildFilter", currentState.step)
        assertTrue(currentState.error is AssertionError, "Wrapped error must be the AssertionError")
    }

    @Test
    fun `test seccomp installation retries on EINTR`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()
        mockProcess.onPrctl = { _, command ->
            if (command is PrctlCommand.GetSeccomp) {
                LinuxNative.SyscallResult.Success(2L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        var syscallCalls = 0
        val mockEngine = MockNativeEngine(process = mockProcess)
        mockEngine.onSyscall = { _, nr, _, _, _, _, _, _ ->
            syscallCalls++
            if (syscallCalls == 1) {
                LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        // If the retry isn't implemented, this test will fail because PureJavaBpfEngine will try to fall back to prctl,
        // which eventually succeeds but doesn't retry the first syscall, or it throws an exception.
        // Once the fix is applied, it should retry the first syscall on EINTR.
        kotlin.test.assertEquals(2, syscallCalls, "Should retry seccomp syscall on EINTR")
    }

    @Test
    fun `test fallback prctl retries on EINTR`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { _, command ->
            if (command is PrctlCommand.SetSeccomp) {
                prctlCalls++
                if (prctlCalls == 1) {
                    LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            } else if (command is PrctlCommand.GetSeccomp) {
                LinuxNative.SyscallResult.Success(2L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        val mockEngine = MockNativeEngine(process = mockProcess)
        // Make the main seccomp syscall return ENOSYS so it triggers the prctl fallback
        mockEngine.onSyscall = { _, _, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1L)
        }
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry prctl seccomp on EINTR")
    }

    @Test
    fun `test setNoNewPrivs retries on EINTR`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { _, command ->
            if (command is PrctlCommand.SetNoNewPrivs) {
                prctlCalls++
                if (prctlCalls == 1) {
                    LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            } else if (command is PrctlCommand.GetSeccomp) {
                LinuxNative.SyscallResult.Success(2L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        val mockEngine = MockNativeEngine(process = mockProcess)
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry setNoNewPrivs on EINTR")
    }

    @Test
    fun `test verifyInstallation retries on EINTR`() {
        PureJavaBpfEngine.clearCache()
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { _, command ->
            if (command is PrctlCommand.GetSeccomp) {
                prctlCalls++
                if (prctlCalls == 1) {
                    LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(2L)
                }
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        val mockEngine = MockNativeEngine(process = mockProcess)
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry GetSeccomp on EINTR")
    }
}
