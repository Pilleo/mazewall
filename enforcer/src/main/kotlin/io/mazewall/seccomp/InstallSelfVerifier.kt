package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.core.Arch
import io.mazewall.core.NativeArg
import io.mazewall.ffi.NativeConstants
import java.util.concurrent.ConcurrentHashMap

/**
 * Post-install self-verification (issue-20260823-172003).
 *
 * After a filter is installed, probes the REAL kernel with a handful of syscalls and asserts the
 * observed outcomes match [BpfSimulator] predictions for the same program. This turns test-time
 * guarantees into runtime guarantees: encoding bugs like issue-20260823-140500 would fail loudly
 * here instead of corrupting process behavior.
 *
 * Safety of probing:
 * - Only DENIED verdicts are invoked (predicted `SECCOMP_RET_ERRNO`): the kernel rejects them in
 *   seccomp before any syscall side effect occurs.
 * - ALLOW-verdict probes are NOT invoked (arbitrary syscalls could have side effects); liveness is
 *   asserted once via getpid.
 * - KILL_* groups are skipped entirely (issue-20260823-172000).
 *
 * Results are memoized per program identity so repeated installs of an identical program (e.g.
 * wrapped-executor tasks) probe only once. Failures throw per operator fallback policy.
 */
internal object InstallSelfVerifier {
    /**
     * Gate (issue-20260823-172003 / issue-20260823-190000):
     *
     * - `-Dio.mazewall.selfVerify=false` opts out entirely.
     * - `-Dio.mazewall.selfVerify=true` forces verification even under mock engines (unit tests).
     * - DEFAULT (property unset): ON whenever the real engine is active, OFF for mocks — mock
     *   verdicts are meaningless and would break fault-injection tests.
     *
     * Default-ON rationale: the original corruption blocker is closed — ALLOW_LIST DSL floors now
     * seed `JvmFloorPresets.fullJvmFloor()` by construction (PREAD64 included), so bootstrap
     * lazy-reads are reliable for preset users. For hand-rolled narrow floors, deterministic
     * early failure (SelfVerificationException) is strictly better than the silent mid-read
     * corruption this verifier exists to catch. Mock-engine installs never verify.
     */
    private const val ENABLED_PROPERTY = "io.mazewall.selfVerify"

    private val verifiedPrograms = ConcurrentHashMap<List<BpfInstruction>, Unit>()

    fun isEnabled(): Boolean {
        when (System.getProperty(ENABLED_PROPERTY)?.lowercase()) {
            "false" -> return false
            "true" -> return true
        }
        return LinuxNative.isRealEngineActive()
    }


    /**
     * Pre-loads every class/method self-verification touches (including Kotlin `buildList`
     * machinery and its transitive JDK exceptions) so nothing is lazily classloaded AFTER a
     * restrictive filter exists — under jvmFloor-style policies such lazy loads read corrupted
     * class bytes and fail with ClassFormatError (issue-20260823-172003).
     */
    fun warmup() {
        val arch = Arch.current()
        BpfSimulator.simulate(emptyList(), 0, arch)
        SyscallProbeMatrix.structural(arch)
        deniedProbeNrs(emptyList(), arch)
    }

    /** Test seam: forget memoized program verifications. */
    internal fun reset() {
        verifiedPrograms.clear()
    }

    /**
     * Verifies the freshly-installed [program] on the current thread.
     *
     * @throws SelfVerificationException when kernel behavior diverges from the oracle, or when the
     *         post-install liveness probe fails.
     */
    fun verify(program: BpfProgram<BpfStatus.Verified>, arch: Arch, priorFilterDepth: Int = 0) {
        val instructions = program.instructions
        if (!isEnabled()) return
        if (priorFilterDepth > 0) {
            // Stacked filters: the kernel enforces the UNION of all programs, so a single-program
            // oracle cannot predict verdicts (an earlier layer may deny what this layer allows).
            // Union-aware simulation is future work (issue-20260824-011900).
            return
        }
        if (verifiedPrograms.putIfAbsent(instructions, Unit) != null) return

        val livenessNr = arch.getpid
        val predictedLiveness = BpfSimulator.simulate(instructions, livenessNr, arch)
        if (predictedLiveness != NativeConstants.SECCOMP_RET_ALLOW) {
            // The policy denies getpid: skip liveness (it would be a false failure), but still
            // verify the DENIED probes below, which do not depend on thread health.
            verifyDeniedProbes(instructions, arch)
            return
        }

        val pid = LinuxNative.raw.syscall(
            livenessNr.toLong(),
            NativeArg.LongArg(0), NativeArg.LongArg(0), NativeArg.LongArg(0),
            NativeArg.LongArg(0), NativeArg.LongArg(0), NativeArg.LongArg(0),
        )
        check(pid is LinuxNative.SyscallResult.Success && pid.value > 0) {
            "Post-install liveness failed: $pid"
        }

        verifyDeniedProbes(instructions, arch)
        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
            io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                passed = true,
                detail = "program=${instructions.size} insns",
            ),
        )
    }

    private fun verifyDeniedProbes(instructions: List<BpfInstruction>, arch: Arch) {
        val deniedNrs = deniedProbeNrs(instructions, arch)
        for ((nr, expectedErrno) in deniedNrs) {
            val res = LinuxNative.raw.syscall(
                nr.toLong(),
                NativeArg.LongArg(0), NativeArg.LongArg(0), NativeArg.LongArg(0),
                NativeArg.LongArg(0), NativeArg.LongArg(0), NativeArg.LongArg(0),
            )
            val actualErrno = (res as? LinuxNative.SyscallResult.Error)?.errno
            if (actualErrno != expectedErrno) {
                io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
                    io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                        passed = false,
                        detail = "nr=$nr expected=$expectedErrno actual=$actualErrno",
                    ),
                )
                throw SelfVerificationException(
                    "Kernel verdict diverges from oracle for nr=$nr: " +
                        "expected errno=$expectedErrno, got result=$res",
                    instructions,
                )
            }
        }
    }

    /**
     * Selects up to [MAX_PROBES] syscall NRs whose simulator-predicted verdict is ERRNO-class,
     * preferring structural edge cases (nr 0, synthetic highs) then matched policy NRs.
     */
    internal fun deniedProbeNrs(
        instructions: List<BpfInstruction>,
        arch: Arch,
        maxProbes: Int = MAX_PROBES,
    ): List<Pair<Int, Int>> {
        val candidates = LinkedHashSet<Int>()
        SyscallProbeMatrix.structural(arch).forEach { candidates.add(it.nr) }
        candidates.add(SyscallProbeMatrix.SYNTHETIC_HIGH_NR)

        // Policy-matched NRs are the JEQ comparands of the emitted program. Restrict to the
        // plausible syscall-NR range and exclude architecture audit tokens.
        val auditTokens = setOf(Arch.AMD64.audit, Arch.AARCH64.audit)
        for (inst in instructions) {
            if (inst is BpfInstruction.Jmp && inst.code == JEQ_OPCODE &&
                inst.k in 0..MAX_PLAUSIBLE_NR && inst.k !in auditTokens
            ) {
                candidates.add(inst.k)
            }
        }

        val out = mutableListOf<Pair<Int, Int>>()
        for (nr in candidates) {
            val action = BpfSimulator.simulate(instructions, nr, arch) ?: continue
            // Arg-inspected syscalls (e.g. prctl) decide on runtime arguments; probing them
            // with fabricated arguments would assert a verdict the real workload may never hit.
            // Skip any NR whose matched instruction section reads seccomp_data.args.
            if (isArgInspected(instructions, nr)) continue
            // Class-exact check: ALLOW (0x7fff0000) contains the ERRNO bits as a subset, so a
            // plain AND would misclassify allowed probes as denied.
            if ((action ushr 16) == (NativeConstants.SECCOMP_RET_ERRNO ushr 16)) {
                out += nr to (action and 0xFFFF)
                if (out.size >= maxProbes) return out
            }
        }
        return out
    }

    private const val MAX_PROBES = 4
    private const val MAX_PLAUSIBLE_NR = 9_999
    private const val JEQ_OPCODE: Short = 0x15
    private const val LD_ABS_OPCODE: Short = 0x20

    /**
     * True when the decision section following the `JEQ nr` comparison for [nr] reads
     * seccomp_data.args — i.e. the filter inspects syscall arguments, so a zero-arg probe would
     * fabricate a verdict.
     */
    internal fun isArgInspected(instructions: List<BpfInstruction>, nr: Int): Boolean {
        val idx = instructions.indexOfFirst {
            it is BpfInstruction.Jmp && it.code == JEQ_OPCODE && it.k == nr
        }
        if (idx < 0) return false
        for (i in idx + 1 until instructions.size) {
            val inst = instructions[i]
            when {
                inst is BpfInstruction.Ret -> return false // end of this NR's decision section
                inst is BpfInstruction.Ld &&
                    inst.k >= BpfSimulator.SECCOMP_DATA_ARGS_OFFSET &&
                    inst.k < BpfSimulator.SECCOMP_DATA_ARGS_OFFSET + 48 -> return true
            }
        }
        return false
    }

    class SelfVerificationException(
        message: String,
        val program: List<BpfInstruction>,
    ) : IllegalStateException(message + "\nprogram=${program.joinToString { it.toString() }}")
}
