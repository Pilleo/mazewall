package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.core.Arch
import io.mazewall.core.NativeArg
import io.mazewall.core.SeccompAction
import io.mazewall.enforcer.state.ContainerState
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
     * @param program The BPF program to verify
     * @param arch The architecture
     * @param priorFilterDepth The number of existing filters on this thread (0 for first install)
     * @param mergedState The merged container state for union-aware verification (used when priorFilterDepth > 0)
     * @throws SelfVerificationException when kernel behavior diverges from the oracle, or when the
     *         post-install liveness probe fails.
     */
    fun verify(
        program: BpfProgram<BpfStatus.Verified>,
        arch: Arch,
        priorFilterDepth: Int = 0,
        mergedState: ContainerState? = null,
    ) {
        val instructions = program.instructions
        if (!isEnabled()) return

        // Memoize only after every check passes: a cached entry from a failed
        // verification would turn all later installs of the same program into an
        // unchecked path (fail-closed rule).
        if (verifiedPrograms.containsKey(instructions)) return

        if (priorFilterDepth > 0) {
            // Stacked filters: the kernel enforces the UNION of all programs.
            // Use union-aware verification if merged state is provided.
            if (mergedState != null) {
                verifyWithUnion(instructions, arch, mergedState, priorFilterDepth)
                return
            } else {
                // Fallback: skip verification (legacy behavior)
                // Union-aware simulation is implemented in verifyWithUnion() (issue-20260824-011900).
                return
            }
        }

        val livenessNr = arch.getpid
        val predictedLiveness = BpfSimulator.simulate(instructions, livenessNr, arch)
        if (predictedLiveness != NativeConstants.SECCOMP_RET_ALLOW) {
            // The policy denies getpid: skip liveness (it would be a false failure), but still
            // verify the DENIED probes below, which do not depend on thread health.
            verifyDeniedProbes(instructions, arch)
            markVerified(instructions)
            return
        }

        val pid = LinuxNative.raw.syscall(
            livenessNr.toLong(),
            NativeArg.LongArg(0),
            NativeArg.LongArg(0),
            NativeArg.LongArg(0),
            NativeArg.LongArg(0),
            NativeArg.LongArg(0),
            NativeArg.LongArg(0),
        )
        check(pid is LinuxNative.SyscallResult.Success && pid.value > 0) {
            "Post-install liveness failed: $pid"
        }

        verifyDeniedProbes(instructions, arch)
        markVerified(instructions)
        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
            io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                passed = true,
                detail = "program=${instructions.size} insns",
            ),
        )
    }

    private fun markVerified(instructions: List<BpfInstruction>) {
        verifiedPrograms.putIfAbsent(instructions, Unit)
    }

    private fun verifyDeniedProbes(
        instructions: List<BpfInstruction>,
        arch: Arch,
    ) {
        val deniedNrs = deniedProbeNrs(instructions, arch)
        for ((nr, expectedErrno) in deniedNrs) {
            val res = LinuxNative.raw.syscall(
                nr.toLong(),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
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
     * Union-aware verification for stacked seccomp filters (issue-20260824-011900).
     *
     * When multiple filters are stacked on a thread, the kernel enforces the UNION of all filters.
     * This method verifies the installed program by checking that:
     * 1. Syscalls denied by ANY layer (including prior filters) remain denied
     * 2. Syscalls allowed by ALL layers (including the new program) are allowed
     *
     * Uses [mergedState] which contains the cumulative effect of all stacked filters.
     *
     * @param instructions The BPF instructions of the newly installed program
     * @param arch The architecture
     * @param mergedState The merged container state representing the union of all stacked filters
     * @param priorFilterDepth The number of existing filters before this install
     */
    private fun verifyWithUnion(
        instructions: List<BpfInstruction>,
        arch: Arch,
        mergedState: ContainerState,
        priorFilterDepth: Int,
    ) {
        // Perform union-aware verification:
        // 1. Liveness probe (getpid) - must be allowed by ALL layers including merged state
        val livenessNr = arch.getpid
        val effectiveLivenessAction = mergedState.getEffectiveAction(livenessNr, arch)
        val expectedLivenessCode = effectiveLivenessAction.toKernelReturnCode()

        if (expectedLivenessCode == NativeConstants.SECCOMP_RET_ALLOW) {
            // Liveness probe should succeed under merged policy
            val pid = LinuxNative.raw.syscall(
                livenessNr.toLong(),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
            )
            check(pid is LinuxNative.SyscallResult.Success && pid.value > 0) {
                "Union-aware liveness failed: $pid (merged state predicted ALLOW)"
            }
        } else {
            // Liveness is denied by merged policy - skip the actual probe but verify denied probes
            verifyDeniedProbesWithUnion(instructions, arch, mergedState)
            markVerified(instructions)
            return
        }

        // 2. Verify denied probes based on union semantics
        verifyDeniedProbesWithUnion(instructions, arch, mergedState)

        markVerified(instructions)
        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
            io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                passed = true,
                detail = "union-aware program=${instructions.size} insns depth=$priorFilterDepth",
            ),
        )
    }

    /**
     * Union-aware denied probe verification.
     *
     * For stacked filters, we verify that syscalls denied by the UNION (merged state)
     * produce the expected errno when probed against the kernel.
     *
     * @param instructions The BPF instructions of the newly installed program
     * @param arch The architecture
     * @param mergedState The merged container state
     */
    private fun verifyDeniedProbesWithUnion(
        instructions: List<BpfInstruction>,
        arch: Arch,
        mergedState: ContainerState,
    ) {
        val deniedNrs = deniedProbeNrsWithUnion(instructions, arch, mergedState)
        for ((nr, expectedErrno) in deniedNrs) {
            val res = LinuxNative.raw.syscall(
                nr.toLong(),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
                NativeArg.LongArg(0),
            )
            val actualErrno = (res as? LinuxNative.SyscallResult.Error)?.errno
            if (actualErrno != expectedErrno) {
                io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
                    io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                        passed = false,
                        detail = "union-aware nr=$nr expected=$expectedErrno actual=$actualErrno",
                    ),
                )
                throw SelfVerificationException(
                    "Union-aware kernel verdict diverges for nr=$nr: " +
                        "expected errno=$expectedErrno, got result=$res",
                    instructions,
                )
            }
        }
    }

    /**
     * Union-aware denied probe NR selection.
     *
     * Selects syscall NRs that should be denied by the UNION of all stacked filters.
     * A syscall is denied if the merged state's effective action is ERRNO-class.
     *
     * @param instructions The BPF instructions of the newly installed program
     * @param arch The architecture
     * @param mergedState The merged container state
     * @return List of (syscall_nr, expected_errno) pairs for denied probes
     */
    internal fun deniedProbeNrsWithUnion(
        instructions: List<BpfInstruction>,
        arch: Arch,
        mergedState: ContainerState,
        maxProbes: Int = MAX_PROBES,
    ): List<Pair<Int, Int>> {
        val candidates = LinkedHashSet<Int>()
        SyscallProbeMatrix.structural(arch).forEach { candidates.add(it.nr) }
        candidates.add(SyscallProbeMatrix.SYNTHETIC_HIGH_NR)

        // Policy-matched NRs from the current program
        val auditTokens = setOf(Arch.AMD64.audit, Arch.AARCH64.audit)
        for (inst in instructions) {
            if (inst is BpfInstruction.Jmp &&
                inst.code == JEQ_OPCODE &&
                inst.k in 0..MAX_PLAUSIBLE_NR &&
                inst.k !in auditTokens
            ) {
                candidates.add(inst.k)
            }
        }

        val out = mutableListOf<Pair<Int, Int>>()
        for (nr in candidates) {
            // Use merged state to get the effective action for union-aware verification
            val effectiveAction = mergedState.getEffectiveAction(nr, arch)
            val actionCode = effectiveAction.toKernelReturnCode()

            // Skip arg-inspected syscalls (same reasoning as non-union verification)
            if (isArgInspected(instructions, nr)) continue

            // Check if this is an ERRNO-class action (denied)
            if ((actionCode ushr 16) == (NativeConstants.SECCOMP_RET_ERRNO ushr 16)) {
                out += nr to (actionCode and 0xFFFF)
                if (out.size >= maxProbes) return out
            }
        }
        return out
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
            if (inst is BpfInstruction.Jmp &&
                inst.code == JEQ_OPCODE &&
                inst.k in 0..MAX_PLAUSIBLE_NR &&
                inst.k !in auditTokens
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
    internal fun isArgInspected(
        instructions: List<BpfInstruction>,
        nr: Int,
    ): Boolean {
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
