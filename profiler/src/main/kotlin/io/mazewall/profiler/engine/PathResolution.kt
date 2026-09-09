package io.mazewall.profiler.engine

/** Heap-only evidence for a syscall's path operands, in their original operand order. */
public sealed interface PathResolution {
    public val paths: List<String>
    public val failures: List<PathResolutionFailure>

    public data object NotApplicable : PathResolution {
        override val paths: List<String> = emptyList()
        override val failures: List<PathResolutionFailure> = emptyList()
    }

    public class Resolved(
        paths: List<String>,
    ) : PathResolution {
        override val paths: List<String> = java.util.List.copyOf(paths)
        override val failures: List<PathResolutionFailure> = emptyList()

        init {
            require(this.paths.isNotEmpty()) { "Resolved requires a path" }
        }

        override fun equals(other: Any?): Boolean = other is Resolved && paths == other.paths

        override fun hashCode(): Int = paths.hashCode()

        override fun toString(): String = "Resolved(paths=$paths)"
    }

    public class Partial(
        paths: List<String>,
        failures: List<PathResolutionFailure>,
    ) : PathResolution {
        override val paths: List<String> = java.util.List.copyOf(paths)
        override val failures: List<PathResolutionFailure> = java.util.List.copyOf(failures)

        init {
            require(this.paths.isNotEmpty() && this.failures.isNotEmpty()) { "Partial requires paths and failures" }
        }

        override fun equals(other: Any?): Boolean = other is Partial && paths == other.paths && failures == other.failures

        override fun hashCode(): Int = 31 * paths.hashCode() + failures.hashCode()

        override fun toString(): String = "Partial(paths=$paths, failures=$failures)"
    }

    public class Failed(
        failures: List<PathResolutionFailure>,
    ) : PathResolution {
        override val paths: List<String> = emptyList()
        override val failures: List<PathResolutionFailure> = java.util.List.copyOf(failures)

        init {
            require(this.failures.isNotEmpty()) { "Failed requires a failure" }
        }

        override fun equals(other: Any?): Boolean = other is Failed && failures == other.failures

        override fun hashCode(): Int = failures.hashCode()

        override fun toString(): String = "Failed(failures=$failures)"
    }
}

/** Snapshot the diagnostic, never the exception object (which may retain native memory). */
public data class PathResolutionCause(
    val type: String,
    val message: String?,
) {
    internal companion object {
        fun from(failure: Throwable): PathResolutionCause = PathResolutionCause(failure.javaClass.name, failure.message)
    }
}

/** [operandIndex] is the zero-based path operand ordinal, not the syscall register index. */
public data class PathResolutionFailure(
    val operandIndex: Int,
    val reason: Reason,
    val detail: String,
    val cause: PathResolutionCause? = null,
) {
    init {
        require(operandIndex >= 0) { "Path operand index must be nonnegative" }
    }

    public sealed interface Reason {
        public data object VmReadDenied : Reason

        public data object Missing : Reason

        public data object DirFdUnresolved : Reason

        public data object Truncated : Reason
    }
}

/** Register metadata is shared by resolution and the explicitly lossy legacy-list adapter. */
internal data class PathOperand(
    val register: Int,
    val dirFdRegister: Int? = null,
)

private const val SECOND_AT_PATH_REGISTER = 3

internal fun pathOperands(name: String): List<PathOperand> =
    when (name.uppercase(java.util.Locale.ROOT)) {
    "OPEN", "EXECVE", "MKDIR", "RMDIR", "CHMOD", "CHOWN", "LCHOWN", "UNLINK", "READLINK", "CHROOT", "UTIME", "UTIMES",
    "CHDIR", "TRUNCATE", "ACCESS", "STAT", "CREAT",
    -> listOf(PathOperand(0))
    "SYMLINK", "LINK", "RENAME" -> listOf(PathOperand(0), PathOperand(1))
    "OPENAT", "EXECVEAT", "OPENAT2", "MKDIRAT", "UNLINKAT", "FCHMODAT", "FCHOWNAT", "UTIMENSAT", "FSTATAT", "READLINKAT",
    "FACCESSAT", "FACCESSAT2", "NEWFSTATAT",
    -> listOf(PathOperand(1, 0))
    "RENAMEAT", "RENAMEAT2", "LINKAT" -> listOf(PathOperand(1, 0), PathOperand(SECOND_AT_PATH_REGISTER, 2))
    "SYMLINKAT" -> listOf(PathOperand(0), PathOperand(2, 1))
    else -> emptyList()
}

/**
 * Compatibility boundary for callers that supply only paths. These lists cannot identify a
 * missing operand or prove NUL termination. Only this adapter retains the old conservative
 * assumptions; live collectors carry [PathResolution] directly and coverage never guesses.
 */
internal fun legacyPathResolution(
    name: String,
    paths: List<String>,
    required: Int = pathOperands(name).size,
): PathResolution {
    val failures = (0 until required).mapNotNull { index ->
        val path = paths.getOrNull(index)
        when {
            path == null -> PathResolutionFailure(index, PathResolutionFailure.Reason.Missing, "Legacy $name list has no operand evidence at $index")
            path.length >= 4096 -> PathResolutionFailure(index, PathResolutionFailure.Reason.Truncated, "Legacy $name path has unproven termination")
            else -> null
        }
    }
    return combinePathResolution(paths, failures)
}

internal fun combinePathResolution(
    paths: List<String>,
    failures: List<PathResolutionFailure>,
): PathResolution =
    when {
    failures.isEmpty() && paths.isEmpty() -> PathResolution.NotApplicable
    failures.isEmpty() -> PathResolution.Resolved(paths)
    paths.isEmpty() -> PathResolution.Failed(failures)
    else -> PathResolution.Partial(paths, failures)
}
