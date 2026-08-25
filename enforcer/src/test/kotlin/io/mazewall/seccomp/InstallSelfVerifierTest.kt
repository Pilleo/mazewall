package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the verifier PLUMBING (probe selection, memoization, opt-out, failure mapping).
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
        InstallSelfVerifier.reset()
    }

    private fun blacklistProgram(): BpfProgram<BpfStatus.Verified> =
        BpfFilter.build(
            arch,
            Policy.builder().defaultAction(SeccompAction.ACT_ALLOW).block(Syscall.CONNECT).build().definition,
        )

    /** Kernel emulation: oracle verdict → ALLOW becomes success; ERRNO-class becomes its errno. */
    private fun oracleMock(program: BpfProgram<BpfStatus.Verified>, override: (Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? = { null }): MockNativeEngine {
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
    fun `memoized per program identity`() {
        val program = blacklistProgram()
        var probeCount = 0
        oracleMock(program) { _ -> probeCount++; null }

        InstallSelfVerifier.verify(program, arch)
        val afterFirst = probeCount
        assertTrue(afterFirst > 0, "expected probes on first verification")

        // Same instance AND same instruction list through a fresh wrapper: no re-probing.
        InstallSelfVerifier.verify(program, arch)
        InstallSelfVerifier.verify(BpfProgram(program.instructions), arch)

        assertEquals(afterFirst, probeCount)
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
        engine.onSyscall = { _, _, _, _, _, _, _ -> probeCount++; LinuxNative.SyscallResult.Success(1) }
        LinuxNative.setEngine(engine)

        InstallSelfVerifier.verify(blacklistProgram(), arch)
        assertEquals(0, probeCount)
    }
}
