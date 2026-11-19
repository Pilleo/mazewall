package io.mazewall.seccomp

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.platform.syscall.SeccompAction

/**
 * Supported argument checks for BPF inspections.
 */
internal sealed interface ArgCheck {
    /** Checks if the bitwise AND of the argument and the mask matches the expected value. */
    data class MaskEquals(
        val mask: Long,
        val expected: Long,
    ) : ArgCheck {
        init {
            require(expected and mask == expected) { "Expected bits must be contained in the argument mask" }
        }
    }

    /** Checks if the argument is exactly equal to one of the allowed values. */
    class EqualsAny(
        allowedValues: List<Long>,
    ) : ArgCheck {
        val allowedValues: List<Long> = java.util.List.copyOf(allowedValues)
    }

    /**
     * Checks ONLY the low 32-bit word of the argument against one of the allowed values
     * (issue-075 problem 3: "register garbage"). For syscall arguments that the kernel ABI
     * defines as `int` (prctl option, socket family, ...), the upper half of the u64 slot is
     * unspecified and may hold garbage; comparing it produces spurious denials.
     */
    class EqualsAny32(
        allowedValues: List<Int>,
    ) : ArgCheck {
        val allowedValues: List<Int> = java.util.List.copyOf(allowedValues)
    }
}

/** The six syscall argument slots in the Linux seccomp_data ABI. */
internal enum class SyscallArgument(
    val index: Int,
) {
    FIRST(0),
    SECOND(1),
    THIRD(2),
    FOURTH(3),
    FIFTH(4),
    SIXTH(5),
}

/**
 * Represents a declarative check for a specific syscall argument.
 */
internal data class SyscallInspection(
    val syscallNumber: Int,
    val argument: SyscallArgument,
    val check: ArgCheck,
    val ifMatched: SeccompAction,
    val ifNotMatched: SeccompAction,
) {
    init {
        require(syscallNumber >= 0) { "Invalid inspected syscall number: $syscallNumber" }
    }
}
