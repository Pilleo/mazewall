package io.mazewall.seccomp

import io.mazewall.core.Arch

/**
 * Reference interpreter for compiled seccomp-BPF programs.
 *
 * This is the SINGLE ORACLE for filter semantics: unit tests, the differential
 * kernel-vs-simulator integration suite (issue-20260823-171500), and future runtime
 * self-verification all derive expected verdicts from here. It lives in production code so that
 * `:profiler` and any self-check feature share exactly one semantic definition with the tests.
 *
 * Semantics implemented (classic BPF as consumed by seccomp(2)):
 * - `LD_ABS` reads from the seccomp_data buffer (offset 0: nr, offset 4: arch).
 * - `JEQ`/`JSET`/`JGT` compare against K; jump offsets live in jt/jf.
 * - `JA` jumps by **K** (NOT by jt — see issue-20260823-140500).
 * - `RET` returns the seccomp action.
 */
public object BpfSimulator {
    public const val SECCOMP_DATA_NR_OFFSET: Int = 0
    public const val SECCOMP_DATA_ARCH_OFFSET: Int = 4

    /**
     * Executes [program] against a synthetic seccomp_data containing [syscallNr] and [auditToken].
     *
     * @return the resulting seccomp action constant, or null if execution ran past the end of the
     *         program without a RET (a kernel verifier would have rejected such a program; the
     *         simulator surfaces it instead of guessing).
     */
    public fun simulate(
        program: List<BpfInstruction>,
        syscallNr: Int,
        auditToken: Int,
        args: LongArray = LongArray(6),
    ): Int? {
        var pc = 0
        var accumulator = 0
        while (pc < program.size) {
            val inst = program[pc]
            when (inst.code) {
                0x20.toShort() -> { // BPF_LD | BPF_W | BPF_ABS
                    accumulator = when {
                        inst.k == SECCOMP_DATA_NR_OFFSET -> syscallNr
                        inst.k == SECCOMP_DATA_ARCH_OFFSET -> auditToken
                        inst.k >= SECCOMP_DATA_ARGS_OFFSET && inst.k < SECCOMP_DATA_ARGS_OFFSET + 48 -> {
                            // seccomp_data.args[i] are native-endian u64 at 8-byte strides;
                            // hi/lo word selection matches the emitting compiler's layout.
                            val idx = (inst.k - SECCOMP_DATA_ARGS_OFFSET) / 8
                            val shift = if ((inst.k - SECCOMP_DATA_ARGS_OFFSET) % 8 == 4) 32 else 0
                            ((args.getOrElse(idx) { 0L } ushr shift) and 0xFFFFFFFFL).toInt()
                        }
                        else -> 0
                    }
                    pc++
                }

                0x15.toShort() -> { // BPF_JMP | BPF_JEQ | BPF_K
                    pc += if (accumulator == inst.k) inst.jt + 1 else inst.jf + 1
                }

                0x25.toShort() -> { // BPF_JMP | BPF_JGT | BPF_K (unsigned)
                    val accUnsigned = accumulator.toLong() and 0xFFFFFFFFL
                    val kUnsigned = inst.k.toLong() and 0xFFFFFFFFL
                    pc += if (accUnsigned > kUnsigned) inst.jt + 1 else inst.jf + 1
                }

                0x45.toShort() -> { // BPF_JMP | BPF_JSET | BPF_K
                    val accUnsigned = accumulator.toLong() and 0xFFFFFFFFL
                    val kUnsigned = inst.k.toLong() and 0xFFFFFFFFL
                    pc += if ((accUnsigned and kUnsigned) != 0L) inst.jt + 1 else inst.jf + 1
                }

                0x05.toShort() -> { // BPF_JMP | BPF_JA: skip count is in K
                    pc += inst.k + 1
                }

                0x54.toShort() -> { // BPF_ALU | BPF_AND | BPF_K
                    accumulator = accumulator and inst.k
                    pc++
                }

                0x06.toShort() -> return inst.k // BPF_RET | BPF_K

                else -> throw IllegalStateException(
                    "BpfSimulator does not implement opcode 0x${inst.code.toUByte().toString(16)} at pc=$pc",
                )
            }
        }
        return null
    }

    /** Convenience overload resolving the audit token from [arch]. */
    public fun simulate(program: List<BpfInstruction>, syscallNr: Int, arch: Arch): Int? =
        simulate(program, syscallNr, arch.audit)


    public const val SECCOMP_DATA_ARGS_OFFSET: Int = 16
}

/**
 * Canonical syscall probe matrix for differential filter verification (issue-20260823-171500 /
 * issue-20260823-172100). Shared by `:enforcer` kernel-differential tests and `:profiler`
 * verdict-mapping tests so both modules probe identical NRs.
 */
public object SyscallProbeMatrix {
    public data class Probe(val nr: Int, val label: String)

    /** Beyond any real syscall number on supported architectures: guaranteed-unmatched probes. */
    public const val SYNTHETIC_MID_NR: Int = 999
    public const val SYNTHETIC_HIGH_NR: Int = 8888

    /**
     * Architecture-independent structural probes every compiled filter must decide on.
     *
     * @param excludedNrs probes whose NR is named by the policy under test (or force-manipulated,
     *        e.g. ioctl in profiling mode) must be skipped here — structural probes are only
     *        meaningful as *unmatched* decisions.
     */
    public fun structural(
        arch: Arch,
        excludedNrs: Set<Int> = emptySet(),
    ): List<Probe> = buildList {
        add(Probe(0, "nr-zero-edge"))
        add(Probe(arch.getpid, "low-real"))
        add(Probe(SYNTHETIC_MID_NR, "synthetic-mid"))
        add(Probe(arch.pidfdGetFd, "high-real"))
        add(Probe(SYNTHETIC_HIGH_NR, "synthetic-high"))
    }.filter { it.nr !in excludedNrs }

    /** Probes for every syscall explicitly named by the policy (matched decisions). */
    public fun matched(arch: Arch, nrs: Collection<Int>): List<Probe> =
        nrs.filter { it >= 0 }.map { Probe(it, "policy-nr-$it") }
}
