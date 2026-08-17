package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.BpfFilter
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import java.util.function.Consumer
import java.util.function.Function

/**
 * Represents the verification status of a BPF program.
 */
public sealed interface BpfStatus {
    /** The program has been built but not yet verified by the kernel. */
    public data object Unverified : BpfStatus

    /** The program has been successfully verified and installed by the kernel. */
    public data object Verified : BpfStatus
}

/**
 * A jump target that can only be minted by [BpfBuilder.nextLabel] / [BpfBuilder.createLabel].
 * Equality is builder-identity plus serial, so two builders that pick the same debug name
 * cannot satisfy each other's jumps.
 */
public class BpfLabel private constructor(
    public val name: String,
    private val owner: Any,
    private val serial: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is BpfLabel && owner === other.owner && serial == other.serial

    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + serial

    override fun toString(): String = name

    internal fun issuedBy(owner: Any): Boolean = this.owner === owner

    internal companion object {
        fun issue(name: String, owner: Any, serial: Int): BpfLabel = BpfLabel(name, owner, serial)
    }
}

/**
 * A compiled seccomp policy containing the BPF filter instructions ready for installation.
 *
 * @param S The [BpfStatus] of the program.
 */
public class BpfProgram<out S : BpfStatus>(
    public val instructions: List<BpfInstruction>,
) {
    public companion object {
        @JvmStatic
        public fun builder(): BpfBuilder<BpfState.Uninitialized> = BpfBuilder(mutableListOf())

        /**
         * Declarative entry point for building BPF programs.
         * Enforces that the program ends with a termination instruction.
         */
        @JvmStatic
        public fun dsl(
            arch: Arch,
            block: Function<BpfBuilder<BpfState.Active>, BpfBuilder<BpfState.Terminated>>
        ): BpfProgram<BpfStatus.Unverified> {
            val active = builder()
                .checkArch(arch)
                .loadSyscallNr()
            val terminated = block.apply(active)
            return terminated.build()
        }

        /**
         * Kotlin-friendly declarative entry point for building BPF programs.
         * Enforces that the program ends with a termination instruction.
         */
        public inline fun dsl(
            arch: Arch,
            block: BpfBuilder<BpfState.Active>.() -> BpfBuilder<BpfState.Terminated>
        ): BpfProgram<BpfStatus.Unverified> =
            builder()
                .checkArch(arch)
                .loadSyscallNr()
                .let(block)
                .build()
    }
}

/**
 * Represents the type-safe compile-time states of a [BpfBuilder].
 */
public sealed interface BpfState {
    /** Initial state: Only allows architecture verification. */
    public interface Uninitialized : BpfState

    /** Architecture verified: Only allows loading the syscall number. */
    public interface ArchVerified : BpfState

    /** Syscall number loaded and active filtering logic is being written. */
    public interface Active : BpfState

    /** Terminated state: The BPF program ends with a RET instruction and is ready to be built. */
    public interface Terminated : BpfState
}

/**
 * Type-safe state machine for building BPF programs.
 * Enforces the initialization sequence: Arch Check -> Load NR -> Filtering -> Termination.
 *
 * @param S The current compile-time [BpfState] of the builder.
 */
public class BpfBuilder<out S : BpfState> internal constructor(
    internal val ops: MutableList<BpfMacro>,
    internal val labelCounter: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
    internal val labelOwner: Any = Any(),
) {
    public fun nextLabel(prefix: String): BpfLabel {
        val serial = labelCounter.incrementAndGet()
        return BpfLabel.issue("${prefix}_$serial", labelOwner, serial)
    }

    public fun createLabel(prefix: String = "label"): BpfLabel {
        return nextLabel(prefix)
    }

    internal fun requireIssued(label: BpfLabel, usage: String) {
        require(label.issuedBy(labelOwner)) {
            "BPF label '${label.name}' was not issued by this builder ($usage)"
        }
    }

    internal fun <T : BpfState> continueAs(): BpfBuilder<T> =
        BpfBuilder(ops, labelCounter, labelOwner)
}

/**
 * Emits code to verify the current architecture and transitions to [BpfState.ArchVerified].
 */
public fun BpfBuilder<BpfState.Uninitialized>.checkArch(arch: Arch): BpfBuilder<BpfState.ArchVerified> {
    ops.add(BpfMacro.LoadAbsolute(BpfFilter.SECCOMP_DATA_ARCH_OFFSET))
    val archOkLabel = nextLabel("arch_ok")
    ops.add(BpfMacro.JumpIfEqual(arch.audit, jt = archOkLabel))
    ops.add(BpfMacro.Ret(NativeConstants.SECCOMP_RET_KILL_PROCESS))
    ops.add(BpfMacro.Label(archOkLabel))
    return continueAs()
}

/**
 * Emits code to load the syscall number and transitions to [BpfState.Active].
 */
public fun BpfBuilder<BpfState.ArchVerified>.loadSyscallNr(): BpfBuilder<BpfState.Active> {
    ops.add(BpfMacro.LoadAbsolute(BpfFilter.SECCOMP_DATA_NR_OFFSET))
    return continueAs()
}

/** Returns ACT_ALLOW immediately. */
public fun BpfBuilder<BpfState.Active>.allow(): BpfBuilder<BpfState.Terminated> {
    return ret(SeccompAction.ACT_ALLOW.nativeCode)
}

/** Returns ACT_ERRNO with the given [errno]. */
public fun BpfBuilder<BpfState.Active>.deny(errno: Int): BpfBuilder<BpfState.Terminated> {
    return ret(SeccompAction.ACT_ERRNO.nativeCode or (errno and 0xFFFF))
}

/** Returns SECCOMP_RET_KILL_THREAD. */
public fun BpfBuilder<BpfState.Active>.killThread(): BpfBuilder<BpfState.Terminated> {
    return ret(NativeConstants.SECCOMP_RET_KILL_THREAD)
}

/** Returns SECCOMP_RET_USER_NOTIF (for profiling or complex rules). */
public fun BpfBuilder<BpfState.Active>.notifyUser(): BpfBuilder<BpfState.Terminated> {
    return ret(NativeConstants.SECCOMP_RET_USER_NOTIF)
}

/**
 * Expects a specific syscall number and executes the [block] if matched.
 * Skips the block if the syscall number does not match.
 *
 * Note: The block itself may terminate, but the main sequence continues
 * after the block's end.
 */
public fun BpfBuilder<BpfState.Active>.expect(
    nr: Int,
    block: BpfBuilder<BpfState.Active>.() -> Unit
): BpfBuilder<BpfState.Active> {
    val skipLabel = nextLabel("skip")
    jumpIfEqual(nr, jf = skipLabel)
    this.block()
    mark(skipLabel)
    return this
}

/** Java-compatible version of [expect]. */
public fun BpfBuilder<BpfState.Active>.expect(
    nr: Int,
    block: Consumer<BpfBuilder<BpfState.Active>>
): BpfBuilder<BpfState.Active> {
    val skipLabel = nextLabel("skip")
    jumpIfEqual(nr, jf = skipLabel)
    block.accept(this)
    mark(skipLabel)
    return this
}

/** Expects a specific [syscall] for the given [arch]. */
public fun BpfBuilder<BpfState.Active>.expect(
    syscall: Syscall,
    arch: Arch,
    block: BpfBuilder<BpfState.Active>.() -> Unit
): BpfBuilder<BpfState.Active> {
    val nr = syscall.numberFor(arch)
    if (nr >= 0) expect(nr, block)
    return this
}

/** Java-compatible version of [expect] using [Syscall]. */
public fun BpfBuilder<BpfState.Active>.expect(
    syscall: Syscall,
    arch: Arch,
    block: Consumer<BpfBuilder<BpfState.Active>>
): BpfBuilder<BpfState.Active> {
    val nr = syscall.numberFor(arch)
    if (nr >= 0) expect(nr, block)
    return this
}

public fun BpfBuilder<BpfState.Active>.loadAbsolute(offset: Int): BpfBuilder<BpfState.Active> {
    ops.add(BpfMacro.LoadAbsolute(offset))
    return this
}

public fun BpfBuilder<BpfState.Active>.jumpIfEqual(
    k: Int,
    jt: BpfLabel? = null,
    jf: BpfLabel? = null
): BpfBuilder<BpfState.Active> {
    jt?.let { requireIssued(it, "jumpIfEqual jt") }
    jf?.let { requireIssued(it, "jumpIfEqual jf") }
    ops.add(BpfMacro.JumpIfEqual(k, jt, jf))
    return this
}

public fun BpfBuilder<BpfState.Active>.jumpIfSet(
    k: Int,
    jt: BpfLabel? = null,
    jf: BpfLabel? = null
): BpfBuilder<BpfState.Active> {
    jt?.let { requireIssued(it, "jumpIfSet jt") }
    jf?.let { requireIssued(it, "jumpIfSet jf") }
    ops.add(BpfMacro.JumpIfSet(k, jt, jf))
    return this
}

public fun BpfBuilder<BpfState.Active>.jumpIfGreaterThan(
    k: Int,
    jt: BpfLabel? = null,
    jf: BpfLabel? = null
): BpfBuilder<BpfState.Active> {
    jt?.let { requireIssued(it, "jumpIfGreaterThan jt") }
    jf?.let { requireIssued(it, "jumpIfGreaterThan jf") }
    ops.add(BpfMacro.JumpIfGreaterThan(k, jt, jf))
    return this
}

public fun BpfBuilder<BpfState.Active>.and(k: Int): BpfBuilder<BpfState.Active> {
    ops.add(BpfMacro.And(k))
    return this
}

public fun BpfBuilder<BpfState.Active>.jmp(label: BpfLabel): BpfBuilder<BpfState.Active> {
    return jumpIfEqual(0, jt = label, jf = label)
}

public fun BpfBuilder<BpfState.Active>.jmpIfTrue(label: BpfLabel): BpfBuilder<BpfState.Active> {
    return jumpIfEqual(0, jf = label)
}

public fun BpfBuilder<BpfState.Active>.jmpIfFalse(label: BpfLabel): BpfBuilder<BpfState.Active> {
    return jumpIfEqual(0, jt = label)
}

/**
 * Ends the instruction sequence with a RET instruction.
 * Transitions the builder to the [BpfState.Terminated] state.
 */
public fun BpfBuilder<BpfState.Active>.ret(action: Int): BpfBuilder<BpfState.Terminated> {
    ops.add(BpfMacro.Ret(action))
    return continueAs()
}

public fun BpfBuilder<BpfState.Active>.mark(label: BpfLabel): BpfBuilder<BpfState.Active> {
    requireIssued(label, "mark")
    ops.add(BpfMacro.Label(label))
    return this
}

/**
 * Compiles the high-level instructions into raw seccomp-bpf opcodes.
 */
public fun BpfBuilder<BpfState.Terminated>.build(): BpfProgram<BpfStatus.Unverified> {
    val labelPositions = mutableMapOf<BpfLabel, Int>()
    val filteredOps = mutableListOf<BpfMacro>()

    // First pass: locate all labels and strip them from the instruction stream
    var currentPos = 0
    for (op in ops) {
        if (op is BpfMacro.Label) {
            if (labelPositions.containsKey(op.label)) {
                throw IllegalArgumentException("Duplicate label marked: ${op.label.name}")
            }
            labelPositions[op.label] = currentPos
        } else {
            filteredOps.add(op)
            currentPos++
        }
    }

    // Second pass: compile instructions and resolve labels
    val bpfInstructions = filteredOps.mapIndexed { index, op ->
        when (op) {
            is BpfMacro.LoadAbsolute -> BpfInstruction.Ld(BPF_LD_ABS, op.offset)
            is BpfMacro.And -> BpfInstruction.Alu(BPF_ALU_AND, op.k)
            is BpfMacro.Ret -> BpfInstruction.Ret(BPF_RET, op.action)
            is BpfMacro.JumpIfEqual -> compileJump(BPF_JMP_JEQ, op.k, op.jt, op.jf, index, labelPositions)
            is BpfMacro.JumpIfSet -> compileJump(BPF_JMP_JSET, op.k, op.jt, op.jf, index, labelPositions)
            is BpfMacro.JumpIfGreaterThan -> compileJump(BPF_JMP_JGT, op.k, op.jt, op.jf, index, labelPositions)
            is BpfMacro.Label -> throw IllegalStateException("Label found in filtered ops")
        }
    }

    return BpfProgram(bpfInstructions)
}

private fun compileJump(
    code: Short,
    k: Int,
    jtLabel: BpfLabel?,
    jfLabel: BpfLabel?,
    currentIndex: Int,
    labelPositions: Map<BpfLabel, Int>,
): BpfInstruction.Jmp {
    val jt = resolveLabel(jtLabel, currentIndex, labelPositions)
    val jf = resolveLabel(jfLabel, currentIndex, labelPositions)
    return BpfInstruction.Jmp(code, jt, jf, k)
}

private fun resolveLabel(
    label: BpfLabel?,
    currentIndex: Int,
    labelPositions: Map<BpfLabel, Int>,
): Short {
    if (label == null) return 0
    val pos = labelPositions[label] ?: throw IllegalArgumentException("Unknown label: ${label.name}")
    val offset = pos - (currentIndex + 1)
    require(offset >= 0) { "Backward jumps are not allowed: ${label.name}" }
    require(offset <= MAX_BPF_JUMP_OFFSET) { "Jump offset too large for ${label.name}: $offset" }
    return offset.toShort()
}

private const val MAX_BPF_JUMP_OFFSET = 255
private const val BPF_LD_ABS: Short = 0x20
private const val BPF_ALU_AND: Short = 0x54
private const val BPF_RET: Short = 0x06
private const val BPF_JMP_JEQ: Short = 0x15
private const val BPF_JMP_JSET: Short = 0x45
private const val BPF_JMP_JGT: Short = 0x25

/**
 * Intermediate symbolic representation of BPF instructions before label resolution.
 */
internal sealed interface BpfMacro {
    data class LoadAbsolute(val offset: Int) : BpfMacro
    data class JumpIfEqual(val k: Int, val jt: BpfLabel? = null, val jf: BpfLabel? = null) : BpfMacro
    data class JumpIfSet(val k: Int, val jt: BpfLabel? = null, val jf: BpfLabel? = null) : BpfMacro
    data class JumpIfGreaterThan(val k: Int, val jt: BpfLabel? = null, val jf: BpfLabel? = null) : BpfMacro
    data class And(val k: Int) : BpfMacro
    data class Ret(val action: Int) : BpfMacro
    data class Label(val label: BpfLabel) : BpfMacro
}
