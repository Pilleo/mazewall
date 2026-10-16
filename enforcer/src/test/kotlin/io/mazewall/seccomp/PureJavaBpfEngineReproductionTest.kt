package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.enforcer.api.MockNativeProcess
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.compile
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PureJavaBpfEngineReproductionTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
    }

    @Test
    fun `reproduce no_new_privs being set before filter building failure`() {
        val mockProcess = MockNativeProcess()
        val prctlCommands = mutableListOf<PrctlCommand>()
        mockProcess.onPrctl = { command ->
            prctlCommands.add(command)
            mockProcess.prctlResult
        }
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena) override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                throw IllegalStateException("Simulated filter building failure")
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        assertFailsWith<RuntimeException> {
            PureJavaBpfEngine.install(compiled)
        }

        // After the fix, this should be null because buildFilter() is called before privilege locking.
        val calledSetNoNewPrivs = prctlCommands.any { it is PrctlCommand.SetNoNewPrivs }
        assertFalse(calledSetNoNewPrivs, "PR_SET_NO_NEW_PRIVS should NOT have been called after the fix")
    }

    @Test
    fun `test error propagation and state logging on JVM Error`() {
        val mockProcess = MockNativeProcess()
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena) override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                throw AssertionError("Simulated fatal AssertionError")
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        assertFailsWith<AssertionError> {
            PureJavaBpfEngine.install(compiled)
        }

        val currentState = io.mazewall.enforcer.state.ContainmentStateRegistry.threadState.engineState
        assertTrue(currentState is SeccompInstallationState.Failed, "Engine state should transition to Failed under fatal JVM Error")
        kotlin.test.assertEquals("buildFilter", currentState.step)
        assertTrue(currentState.error is AssertionError, "Wrapped error must be the AssertionError")
    }

    @Test
    fun `test seccomp installation retries on EINTR`() {
        val mockProcess = MockNativeProcess()
        mockProcess.onPrctl = { command ->
            if (command is PrctlCommand.GetSeccomp) {
                LinuxNative.SyscallResult.Success(2L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        var syscallCalls = 0
        val mockEngine = MockNativeEngine(process = mockProcess)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            syscallCalls++
            if (syscallCalls == 1) {
                LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        // If the retry isn't implemented, this test will fail because PureJavaBpfEngine will try to fall back to prctl,
        // which eventually succeeds but doesn't retry the first syscall, or it throws an exception.
        // Once the fix is applied, it should retry the first syscall on EINTR.
        kotlin.test.assertEquals(2, syscallCalls, "Should retry seccomp syscall on EINTR")
    }

    @Test
    fun `test fallback prctl retries on EINTR`() {
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { command ->
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
        mockEngine.onSyscall = { _, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1L)
        }
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry prctl seccomp on EINTR")
    }

    @Test
    fun `test setNoNewPrivs retries on EINTR`() {
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { command ->
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

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry setNoNewPrivs on EINTR")
    }

    @Test
    fun `test verifyInstallation retries on EINTR`() {
        val mockProcess = MockNativeProcess()

        var prctlCalls = 0
        mockProcess.onPrctl = { command ->
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

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.definition.compile(Arch.current())

        PureJavaBpfEngine.install(compiled)

        kotlin.test.assertEquals(2, prctlCalls, "Should retry GetSeccomp on EINTR")
    }
}
