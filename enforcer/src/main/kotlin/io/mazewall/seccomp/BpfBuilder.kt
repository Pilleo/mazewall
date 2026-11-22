package io.mazewall.seccomp

import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.util.function.Function

/** Identity tokens prevent one builder or fragment scope from satisfying another's jumps. */
internal class BpfLabel private constructor(
    val name: String,
    private val owner: BpfBuilder,
    private val scopeId: Int,
) {
    internal fun issuedBy(owner: BpfBuilder): Boolean = this.owner === owner

    internal fun issuedInScope(
        owner: BpfBuilder,
        scopeId: Int,
    ): Boolean = this.owner === owner && this.scopeId == scopeId

    override fun toString(): String = name

    companion object {
        internal fun issue(
            name: String,
            owner: BpfBuilder,
            scopeId: Int,
        ): BpfLabel = BpfLabel(name, owner, scopeId)
    }
}

/** Append-only, locally owned assembler. Concrete stages expose only valid operations. */
internal class BpfBuilder private constructor() {
    private val ops = mutableListOf<BpfMacro>()
    private var labelCounter = 0
    private var nextScopeId = 0
    private var currentScopeId = 0
    private var stage = Stage.INITIAL

    private enum class Stage { INITIAL, ARCH_CHECKED, ACTIVE }

    private fun nextLabel(prefix: String): BpfLabel = BpfLabel.issue("${prefix}_${++labelCounter}", this, currentScopeId)

    private fun requireIssued(label: BpfLabel) {
        require(label.issuedBy(this)) { "BPF label '${label.name}' was not issued by this builder" }
        require(label.issuedInScope(this, currentScopeId)) {
            "BPF label '${label.name}' belongs to an enclosing or completed scope and cannot cross an expect fragment boundary"
        }
    }

    private fun validateExpectFragment(
        fragmentStart: Int,
        fragmentEnd: Int,
    ) {
        val fragment = compile(ops.subList(fragmentStart, fragmentEnd))
        BpfStaticVerifier.verify(fragment)
        requireAllInstructionsReachable(fragment.instructions)
    }

    class Uninitialized internal constructor(
        private val owner: BpfBuilder,
    ) {
        fun checkArch(arch: Arch): ArchVerified {
            check(owner.stage == Stage.INITIAL) { "Architecture has already been checked" }
            val accepted = owner.nextLabel("arch_ok")
            owner.ops.add(BpfMacro.Instruction(BpfInstruction.Ld(SECCOMP_DATA_ARCH_OFFSET)))
            owner.ops.add(BpfMacro.Conditional(BpfInstruction.Comparison.EQUAL, arch.audit, accepted, null))
            owner.ops.add(BpfMacro.Instruction(BpfInstruction.Ret(SeccompAction.ACT_KILL_PROCESS)))
            owner.ops.add(BpfMacro.Label(accepted))
            owner.stage = Stage.ARCH_CHECKED
            return ArchVerified(owner, arch)
        }
    }

    class ArchVerified internal constructor(
        private val owner: BpfBuilder,
        private val arch: Arch,
    ) {
        fun loadSyscallNr(): Active {
            check(owner.stage == Stage.ARCH_CHECKED) { "Syscall number has already been loaded" }
            owner.ops.add(BpfMacro.Instruction(BpfInstruction.Ld(0)))
            owner.stage = Stage.ACTIVE
            val active = Active(owner)
            if (arch.audit == Arch.AUDIT_ARCH_X86_64) active.rejectX32()
            return active
        }
    }

    class Active internal constructor(
        private val owner: BpfBuilder,
    ) {
        fun nextLabel(prefix: String): BpfLabel = owner.nextLabel(prefix)

        fun createLabel(prefix: String = "label"): BpfLabel = nextLabel(prefix)

        internal fun rejectX32() {
            val native = nextLabel("native_abi")
            val kill = nextLabel("x32_rejected")
            jumpIfSet(X32_SYSCALL_BIT, jt = kill)
            jumpIfGreaterThan(LEGACY_X32_LAST_NR, jt = native)
            jumpIfGreaterThan(LEGACY_X32_FIRST_NR - 1, jt = kill, jf = native)
            mark(kill)
            ret(SeccompAction.ACT_KILL_PROCESS)
            mark(native)
        }

        fun allow(): Terminated = ret(SeccompAction.ACT_ALLOW)

        fun deny(errno: Int): Terminated = ret(SeccompAction.ACT_ERRNO(errno))

        fun killThread(): Terminated = ret(SeccompAction.ACT_KILL_THREAD)

        fun notifyUser(): Terminated = ret(SeccompAction.ACT_NOTIFY)

        /** Reload nr so preceding argument loads cannot change what this predicate compares. */
        fun expect(
            nr: Int,
            block: Active.() -> Terminated,
        ): Active {
            require(nr >= 0) { "Invalid syscall number: $nr" }
            val skip = nextLabel("skip")
            loadAbsolute(0)
            jumpIfEqual(nr, jf = skip)
            val fragmentStart = owner.ops.size
            val previousScopeId = owner.currentScopeId
            owner.currentScopeId = ++owner.nextScopeId
            val terminated =
                try {
                    block()
                } finally {
                    owner.currentScopeId = previousScopeId
                }
            terminated.requireCurrent(owner)
            owner.validateExpectFragment(fragmentStart, owner.ops.size)
            mark(skip)
            return this
        }

        fun expect(
            nr: Int,
            block: Function<Active, Terminated>,
        ): Active = expect(nr) { block.apply(this) }

        fun expect(
            syscall: Syscall,
            arch: Arch,
            block: Active.() -> Terminated,
        ): Active {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) expect(nr, block)
            return this
        }

        fun expect(
            syscall: Syscall,
            arch: Arch,
            block: Function<Active, Terminated>,
        ): Active = expect(syscall, arch) { block.apply(this) }

        fun loadAbsolute(offset: Int): Active = emit(BpfInstruction.Ld(offset))

        fun and(k: Int): Active = emit(BpfInstruction.Alu(k))

        fun jumpIfEqual(
            k: Int,
            jt: BpfLabel? = null,
            jf: BpfLabel? = null,
        ): Active = conditional(BpfInstruction.Comparison.EQUAL, k, jt, jf)

        fun jumpIfSet(
            k: Int,
            jt: BpfLabel? = null,
            jf: BpfLabel? = null,
        ): Active = conditional(BpfInstruction.Comparison.BITS_SET, k, jt, jf)

        fun jumpIfGreaterThan(
            k: Int,
            jt: BpfLabel? = null,
            jf: BpfLabel? = null,
        ): Active = conditional(BpfInstruction.Comparison.GREATER_THAN, k, jt, jf)

        private fun conditional(
            comparison: BpfInstruction.Comparison,
            k: Int,
            jt: BpfLabel?,
            jf: BpfLabel?,
        ): Active {
            jt?.let(owner::requireIssued)
            jf?.let(owner::requireIssued)
            owner.ops.add(BpfMacro.Conditional(comparison, k, jt, jf))
            return this
        }

        fun jumpUnconditional(label: BpfLabel): Active {
            owner.requireIssued(label)
            owner.ops.add(BpfMacro.Jump(label))
            return this
        }

        fun jmp(label: BpfLabel): Active = jumpUnconditional(label)

        fun jmpIfTrue(label: BpfLabel): Active = jumpIfEqual(0, jf = label)

        fun jmpIfFalse(label: BpfLabel): Active = jumpIfEqual(0, jt = label)

        fun mark(label: BpfLabel): Active {
            owner.requireIssued(label)
            owner.ops.add(BpfMacro.Label(label))
            return this
        }

        fun ret(action: SeccompAction): Terminated {
            emit(BpfInstruction.Ret(action))
            return Terminated(owner)
        }

        private fun emit(instruction: BpfInstruction): Active {
            owner.ops.add(BpfMacro.Instruction(instruction))
            return this
        }

        internal fun complete(terminated: Terminated): BpfProgram<BpfStatus.Unverified> {
            terminated.requireIssued(owner)
            return terminated.build()
        }
    }

    class Terminated internal constructor(
        private val owner: BpfBuilder,
    ) {
        // Only appends are possible: retaining the prefix avoids copying at every branch RET.
        private val end = owner.ops.size

        internal fun requireIssued(owner: BpfBuilder) {
            require(this.owner === owner) { "Terminated program belongs to another builder" }
        }

        internal fun requireCurrent(owner: BpfBuilder) {
            requireIssued(owner)
            require(end == owner.ops.size) { "Expected block must end at its returned termination" }
        }

        fun build(): BpfProgram<BpfStatus.Unverified> = compile(owner.ops.take(end))
    }

    companion object {
        private const val SECCOMP_DATA_ARCH_OFFSET = 4
        private const val X32_SYSCALL_BIT = 0x40000000
        private const val LEGACY_X32_FIRST_NR = 512
        private const val LEGACY_X32_LAST_NR = 547

        internal fun start(): Uninitialized = Uninitialized(BpfBuilder())
    }
}

private sealed interface BpfMacro {
    data class Instruction(
        val instruction: BpfInstruction,
    ) : BpfMacro

    data class Conditional(
        val comparison: BpfInstruction.Comparison,
        val k: Int,
        val jt: BpfLabel?,
        val jf: BpfLabel?,
    ) : BpfMacro

    data class Jump(
        val target: BpfLabel,
    ) : BpfMacro

    data class Label(
        val label: BpfLabel,
    ) : BpfMacro
}

private fun compile(ops: List<BpfMacro>): BpfProgram<BpfStatus.Unverified> {
    val labels = mutableMapOf<BpfLabel, Int>()
    var position = 0
    for (op in ops) {
        when (op) {
            is BpfMacro.Label -> require(labels.put(op.label, position) == null) { "Duplicate label marked: ${op.label.name}" }
            is BpfMacro.Instruction, is BpfMacro.Conditional, is BpfMacro.Jump -> position++
        }
    }
    val instructions = buildList {
        for (op in ops) {
            when (op) {
                is BpfMacro.Instruction -> add(op.instruction)
                is BpfMacro.Conditional -> add(
                    BpfInstruction.Jmp(
                        op.comparison,
                        conditionalOffset(op.jt, size, labels),
                        conditionalOffset(op.jf, size, labels),
                        op.k,
                    ),
                )
                is BpfMacro.Jump -> add(BpfInstruction.Ja(resolveLabel(op.target, size, labels)))
                is BpfMacro.Label -> Unit
            }
        }
    }
    return BpfProgram.unverified(instructions)
}

private fun conditionalOffset(
    label: BpfLabel?,
    index: Int,
    labels: Map<BpfLabel, Int>,
): Short {
    if (label == null) return 0
    val offset = resolveLabel(label, index, labels)
    require(offset <= BpfInstruction.Jmp.MAX_OFFSET) { "Jump offset too large for ${label.name}: $offset" }
    return offset.toShort()
}

private fun resolveLabel(
    label: BpfLabel,
    index: Int,
    labels: Map<BpfLabel, Int>,
): Int {
    val position = labels[label] ?: throw IllegalArgumentException("Unknown label: ${label.name}")
    val offset = position - index - 1
    require(offset >= 0) { "Backward jumps are not allowed: ${label.name}" }
    return offset
}

private fun requireAllInstructionsReachable(instructions: List<BpfInstruction>) {
    val reachable = BooleanArray(instructions.size)
    reachable[0] = true
    for ((index, instruction) in instructions.withIndex()) {
        require(reachable[index]) {
            "Unreachable instruction in BPF inspection fragment at index $index"
        }
        when (instruction) {
            is BpfInstruction.Ld, is BpfInstruction.Alu -> reachable[index + 1] = true
            is BpfInstruction.Ja -> reachable[index + 1 + instruction.k] = true
            is BpfInstruction.Jmp -> {
                reachable[index + 1 + instruction.jt.toInt()] = true
                reachable[index + 1 + instruction.jf.toInt()] = true
            }
            is BpfInstruction.Ret -> Unit
        }
    }
}
