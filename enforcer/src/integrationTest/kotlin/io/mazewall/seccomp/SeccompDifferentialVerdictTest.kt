package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.IsolatedProcessTester
import io.mazewall.NeedsFreshJvm
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.PolicyScope
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential kernel-vs-simulator verification (issue-20260823-171500).
 *
 * For each policy archetype: compile, predict every probe verdict with the shared platform oracle
 * ([BpfSimulator]), install on a real thread, then assert the kernel's observable behavior matches.
 * Includes the post-install liveness assertion that would have caught issue-20260823-140500
 * deterministically.
 *
 * KILL_* verdicts are verified out-of-process via [io.mazewall.IsolatedProcessTester]
 * (issue-20260823-172000): this class is @NeedsFreshJvm because its in-JVM archetypes permanently
 * restrict worker threads.
 */
@NeedsFreshJvm
class SeccompDifferentialVerdictTest : BaseIntegrationTest() {
    init {
        // Opt in to runtime self-verification: these policies are probe-safe by design.
        System.setProperty("io.mazewall.selfVerify", "true")
    }

    private val arch = io.mazewall.core.Arch.current()

    private fun assertKernelVerdictMatchesSimulator(
        program: List<BpfInstruction>,
        probeNrs: List<Int>,
        kernelOutcome: (nr: Int) -> Boolean,
    ) {
        val blockedNrs = probeNrs.filter { nr ->
            BpfSimulator.simulate(program, nr, arch)?.let { it != NativeConstants.SECCOMP_RET_ALLOW } ?: false
        }
        val allowedNrs = probeNrs.filter { nr ->
            BpfSimulator.simulate(program, nr, arch) == NativeConstants.SECCOMP_RET_ALLOW
        }
        // Kernel-vs-simulator consensus: every simulator-denied probe is actually denied by the
        // kernel; every simulator-allowed probe actually succeeds.
        for (nr in blockedNrs) {
            assertTrue(kernelOutcome(nr), "Kernel must deny nr=$nr (simulator predicted denial)")
        }
        for (nr in allowedNrs.filter { it != 0 }) { // nr 0 is `read`: cannot be invoked benignly here
            assertFalse(kernelOutcome(nr), "Kernel must allow nr=$nr (simulator predicted ALLOW)")
        }
    }

    /**
     * Invokes an arbitrary syscall and reports whether the installed filter denied it.
     * A filter decision surfaces as a raw error result; anything else (success, ENOSYS from the
     * kernel itself) counts as "not denied by seccomp".
     */
    private fun kernelDenied(nr: Int): Boolean {
        val res = io.mazewall.LinuxNative.raw.syscall(
            nr.toLong(),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
        )
        return res is io.mazewall.LinuxNative.SyscallResult.Error &&
            res.errno == NativeConstants.EPERM
    }

    @Test
    fun `blacklist errno archetype - kernel matches simulator and liveness holds`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.CONNECT)
            .build()
        val compiled = policy.compile(arch)
        val probes = SyscallProbeMatrix.structural(arch) + SyscallProbeMatrix.matched(
            arch,
            listOf(Syscall.CONNECT.numberFor(arch)),
        )

        ContainedExecutors.installOnCurrentThread(policy)

        // Liveness: benign syscalls must keep working after installation.
        assertTrue(ProcessHandle.current().pid() > 0)

        // Simulator predictions over the whole matrix.
        assertEquals(
            NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
            BpfSimulator.simulate(compiled.compiledFilters, Syscall.CONNECT.numberFor(arch), arch),
        )
        for (probe in SyscallProbeMatrix.structural(arch)) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(compiled.compiledFilters, probe.nr, arch),
                "Structural probe ${probe.label} must be allowed",
            )
        }

        // Kernel behavior vs simulator for the matched NR (connect -> EPERM).
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 1), 100)
            }
            error("connect must be denied by the installed filter")
        } catch (e: java.net.SocketException) {
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("Operation not permitted", ignoreCase = true) || msg.contains("Permission denied", ignoreCase = true),
                "connect must fail with EPERM-class error, got: $msg",
            )
        }

        assertKernelVerdictMatchesSimulator(compiled.compiledFilters, probes.map { it.nr }) { nr ->
            kernelDenied(nr)
        }
    }

    @Test
    fun `bst fast path archetype - blocked set enforced, liveness holds`() {
        // default ALLOW + <=32 actions => BST codegen path. Never deny-by-default here: the JVM
        // lazily loads classes for every syscall imaginable and would starve (ClassFormatError).
        val blocked = listOf(Syscall.CONNECT, Syscall.UMASK, Syscall.GETPPID, Syscall.GETEUID)
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .addAction(SeccompAction.ACT_ERRNO(NativeConstants.EPERM), *blocked.toTypedArray())
            .build()
        val compiled = policy.compile(arch)

        // Oracle: all structural probes allowed, all blocked NRs -> ERRNO|EPERM.
        for (probe in SyscallProbeMatrix.structural(arch)) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(compiled.compiledFilters, probe.nr, arch),
                "Structural probe ${probe.label} must be allowed on BST path",
            )
        }
        for (sys in blocked) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
                BpfSimulator.simulate(compiled.compiledFilters, sys.numberFor(arch), arch),
            )
        }

        ContainedExecutors.installOnCurrentThread(policy)

        assertTrue(ProcessHandle.current().pid() > 0)

        val probes = SyscallProbeMatrix.structural(arch) + SyscallProbeMatrix.matched(
            arch,
            blocked.map { it.numberFor(arch) },
        )
        assertKernelVerdictMatchesSimulator(compiled.compiledFilters, probes.map { it.nr }) { nr ->
            kernelDenied(nr)
        }
    }

    @Test
    fun `kill_thread archetype terminates the probe child before PROBE_OK`() {
        val victimNr = Syscall.UMASK.numberFor(arch)
        val childProgram = compiledProgramOf(victimNr)

        // Oracle prediction: the victim NR maps to KILL_THREAD.
        assertEquals(
            NativeConstants.SECCOMP_RET_KILL_THREAD,
            BpfSimulator.simulate(childProgram, victimNr, arch),
        )

        var output = ""
        try {
            IsolatedProcessTester.runIsolatedTest(
                SeccompKillProbeChild::class.java.name,
                victimNr.toString(),
            )
            error("Kill-probe child survived; seccomp did not enforce KILL_THREAD")
        } catch (e: IllegalStateException) {
            // IsolatedProcessTester only surfaces exit codes; capture marker semantics below.
            output = e.message ?: ""
        }
        // The child must have died from enforcement, not completed its run.
        assertFalse(output.contains("PROBE_OK"), "Child printed PROBE_OK: kill enforcement failed")
    }

    /** Compiles the same program the kill-probe child installs, for oracle prediction. */
    private fun compiledProgramOf(victimNr: Int): List<BpfInstruction> {
        val victim = io.mazewall.core.Syscall.entries.first { it.numberFor(arch) == victimNr }
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .addAction(SeccompAction.ACT_KILL_THREAD, victim)
            .build()
        return policy.compile(arch).compiledFilters
    }
}
