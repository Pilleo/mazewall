package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for verifier plumbing (probe selection, current evidence, opt-out, failure mapping).
 * The mock engine derives its verdicts from the shared oracle, so these tests do not re-assert
 * filter semantics — kernel-vs-oracle truth is covered by SeccompDifferentialVerdictTest.
 */
class InstallSelfVerifierTest {
    private val arch = Arch.AMD64

    @BeforeEach
    fun enableUnderMock() {
        // Verification logic is exercised against mocks by forcing the gate on.
        System.setProperty("io.mazewall.selfVerify", "true")
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("io.mazewall.selfVerify")
        LinuxNative.resetToDefault()
    }

    private fun blacklistProgram(): BpfProgram<BpfStatus.Verified> =
        BpfFilter.build(
            arch,
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ALLOW)
                .block(Syscall.CONNECT)
                .build()
                .definition,
        )

    /** Kernel emulation: oracle verdict → ALLOW becomes success; ERRNO-class becomes its errno. */
    private fun oracleMock(
        program: BpfProgram<BpfStatus.Verified>,
        override: (Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? = { null },
    ): MockNativeEngine {
        val engine = MockNativeEngine()
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            override(nr.toInt()) ?: run {
                val action = BpfSimulator.simulate(program.instructions, nr.toInt(), arch)
                when {
                    action == NativeConstants.SECCOMP_RET_ALLOW -> LinuxNative.SyscallResult.Success(4321)
                    action != null && (action ushr 16) == (NativeConstants.SECCOMP_RET_ERRNO ushr 16) ->
                        LinuxNative.SyscallResult.Error(action and 0xFFFF, -1)
                    else -> LinuxNative.SyscallResult.Success(0)
                }
            }
        }
        LinuxNative.setEngine(engine)
        return engine
    }

    @Test
    fun `matching kernel passes verification`() {
        val program = blacklistProgram()
        oracleMock(program)
        InstallSelfVerifier.verify(program, arch) // must not throw
    }

    @Test
    fun `liveness callback cannot redirect denied probes to another backend`() {
        val program = blacklistProgram()
        val foreign = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
        }
        oracleMock(program) { nr ->
            if (nr == arch.getpid) LinuxNative.setEngine(foreign)
            null
        }

        InstallSelfVerifier.verify(program, arch)
    }

    @Test
    fun `installation callback cannot disable previously selected self verification`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build()
        val program = BpfFilter.build(Arch.current(), policy.definition)
        val engine = oracleMock(program) { nr ->
            if (nr == Arch.current().seccompSyscallNumber) LinuxNative.SyscallResult.Success(0L) else null
        }
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.GetSeccomp) {
                System.setProperty("io.mazewall.selfVerify", "false")
                engine.onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
            }
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun getLandlockAbiVersion(): Int = 8

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
        try {
            val failure = assertFailsWith<PartialInstallationException> {
                ContainedExecutors.installOnCurrentThread(policy).use { it.requireInstalled() }
            }
            assertTrue(failure.progress.seccompApplied)
            assertTrue(failure.cause!!.message!!.contains("liveness"))
        } finally {
            Platform.resetToDefault()
            io.mazewall.enforcer.state.ContainmentStateRegistry.threadState = io.mazewall.enforcer.state
                .ContainerState()
            io.mazewall.enforcer.state.ContainmentStateRegistry.processState = io.mazewall.enforcer.state
                .ContainerState()
        }
    }

    @Test
    fun `diverging kernel verdict throws with program attached`() {
        val program = blacklistProgram()
        val connectNr = Syscall.CONNECT.numberFor(arch)
        oracleMock(program) { nr -> if (nr == connectNr) LinuxNative.SyscallResult.Success(0) else null }

        val ex = assertFailsWith<InstallSelfVerifier.SelfVerificationException> {
            InstallSelfVerifier.verify(program, arch)
        }
        assertEquals(program.instructions, ex.program)
        assertTrue(ex.message!!.contains("nr=$connectNr"))
    }

    @Test
    fun `failed liveness aborts with clear message`() {
        val program = blacklistProgram()
        oracleMock(program) { nr ->
            if (nr == arch.getpid) LinuxNative.SyscallResult.Error(13, -1) else null
        }

        val ex = assertFailsWith<IllegalStateException> { InstallSelfVerifier.verify(program, arch) }
        assertTrue(ex.message!!.contains("liveness"))
    }

    @Test
    fun `each verification probes the current installation`() {
        val program = blacklistProgram()
        var probeCount = 0
        oracleMock(program) { _ ->
            probeCount++
            null
        }

        InstallSelfVerifier.verify(program, arch)
        val afterFirst = probeCount
        assertTrue(afterFirst > 0, "expected probes on first verification")

        // Identical instructions cannot attest the current installation.
        InstallSelfVerifier.verify(program, arch)
        InstallSelfVerifier.verify(BpfProgram(program.instructions), arch)

        assertTrue(probeCount > afterFirst)
    }

    @Test
    fun `prior successful program cannot conceal a divergent current backend`() {
        val program = blacklistProgram()
        oracleMock(program)
        InstallSelfVerifier.verify(program, arch)
        oracleMock(program) { nr -> if (nr == Syscall.CONNECT.numberFor(arch)) LinuxNative.SyscallResult.Success(0L) else null }

        assertFailsWith<InstallSelfVerifier.SelfVerificationException> { InstallSelfVerifier.verify(program, arch) }
    }

    @Test
    fun `prior successful program cannot attest a different merged filter state`() {
        val program = blacklistProgram()
        oracleMock(program)
        InstallSelfVerifier.verify(program, arch)
        val merged = io.mazewall.enforcer.state
            .ContainerState(syscallActions = mapOf(Syscall.GETPID to SeccompAction.ACT_ERRNO(1)))

        assertFailsWith<InstallSelfVerifier.SelfVerificationException> {
            InstallSelfVerifier.verify(program, arch, priorFilterDepth = 1, mergedState = merged)
        }
    }

    @Test
    fun `stacked verification requires merged filter evidence`() {
        val program = blacklistProgram()
        oracleMock(program)

        assertFailsWith<IllegalArgumentException> { InstallSelfVerifier.verify(program, arch, priorFilterDepth = 1) }
    }

    @Test
    fun `arg-dependent decisions are excluded from probing`() {
        val program = blacklistProgram()
        val engine = oracleMock(program)
        // Force a mismatch on EVERY probe; verification passes only because arg-dependent NRs
        // (prctl et al.) are never selected.
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == arch.getpid.toLong()) {
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(4321)
            } else {
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(
                    NativeConstants.ENOSYS,
                    -1L,
                )
            }
        }
        val ex = assertFailsWith<InstallSelfVerifier.SelfVerificationException> {
            InstallSelfVerifier.verify(program, arch)
        }
        // prctl is arg-inspected; the verifier must have skipped it despite predicting EPERM
        // for zero-filled args.
        assertTrue(!ex.message!!.contains("nr=${arch.prctl}"))
    }

    @Test
    fun `opt-out property disables probing`() {
        System.setProperty("io.mazewall.selfVerify", "false")
        var probeCount = 0
        val engine = MockNativeEngine()
        engine.onSyscall = { _, _, _, _, _, _, _ ->
            probeCount++
            LinuxNative.SyscallResult.Success(1)
        }
        LinuxNative.setEngine(engine)

        InstallSelfVerifier.verify(blacklistProgram(), arch)
        assertEquals(0, probeCount)
    }
}
