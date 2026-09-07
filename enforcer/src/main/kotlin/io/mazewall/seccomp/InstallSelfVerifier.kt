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
        SelfVerificationPlan.create(emptyList(), arch)
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

        val plan = SelfVerificationPlan.create(instructions, arch)
        if (!plan.probeLiveness) {
            // The policy denies getpid: skip liveness (it would be a false failure), but still
            // verify the DENIED probes below, which do not depend on thread health.
            verifyDeniedProbes(instructions, plan.deniedProbes)
            markVerified(instructions)
            return
        }

        val pid = LinuxNative.raw.syscall(
            plan.livenessNr.toLong(),
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

        verifyDeniedProbes(instructions, plan.deniedProbes)
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
        deniedNrs: List<Pair<Int, Int>>,
    ) {
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
        val plan = SelfVerificationPlan.create(instructions, arch, mergedState = mergedState)
        if (plan.probeLiveness) {
            // Liveness probe should succeed under merged policy
            val pid = LinuxNative.raw.syscall(
                plan.livenessNr.toLong(),
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
            verifyDeniedProbes(instructions, plan.deniedProbes)
            markVerified(instructions)
            return
        }

        // 2. Verify denied probes based on union semantics
        verifyDeniedProbes(instructions, plan.deniedProbes)

        markVerified(instructions)
        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
            io.mazewall.enforcer.diagnostics.MazewallEvents.SelfVerificationResult(
                passed = true,
                detail = "union-aware program=${instructions.size} insns depth=$priorFilterDepth",
            ),
        )
    }

    class SelfVerificationException(
        message: String,
        val program: List<BpfInstruction>,
    ) : IllegalStateException(message + "\nprogram=${program.joinToString { it.toString() }}")
}
