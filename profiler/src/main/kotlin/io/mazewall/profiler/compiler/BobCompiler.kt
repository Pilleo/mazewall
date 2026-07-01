package io.mazewall.profiler.compiler

import io.mazewall.core.Syscall
import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.engine.TraceEvent
import java.util.*

/**
 * BobCompiler parses semantic trace events produced by the Profiler session
 * and aggregates them into a [BillOfBehavior].
 */
object BobCompiler {
    private const val O_WRONLY = 1L
    private const val O_RDWR = 2L
    private const val O_CREAT = 64L
    private const val O_TRUNC = 512L

    /**
     * Parses the given semantic trace events and returns a [BillOfBehavior].
     */
    fun compile(events: List<TraceEvent>): BillOfBehavior {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<Syscall>()
        val execs = mutableSetOf<String>()

        for (event in events) {
            if (event.paths.contains("<YAMA_ERROR_UNKNOWN_PATH>")) {
                throw IllegalStateException("Profiler path resolution failed due to permission restriction (Yama ptrace_scope). Please invoke prctl(PR_SET_PTRACER, daemonPid) or configure Yama settings.")
            }
            // Map the syscall name to Syscall enum
            val syscall =
                runCatching {
                    Syscall.valueOf(event.syscallName.uppercase(Locale.US))
                }.getOrNull()

            if (syscall != null) {
                syscalls.add(syscall)
            }

            // Categorize paths using polymorphic hierarchy
            when (event) {
                is TraceEvent.Open -> {
                    if (isOpenWrite(event)) {
                        fsWritePaths.add(event.path)
                    } else {
                        opens.add(event.path)
                    }
                }

                is TraceEvent.Exec -> {
                    execs.add(event.path)
                }

                is TraceEvent.FsMutation -> {
                    fsWritePaths.addAll(event.paths)
                }

                is TraceEvent.Mmap -> {
                    // MMAP events could optionally be used to detect executable memory requests
                    // but for BoB generation we currently focus on path-based security.
                }

                is TraceEvent.Socket -> {
                    // Socket domain/type could be used for network BoB generation in the future.
                }

                is TraceEvent.Generic -> {
                    // Conservative fallback: treat any paths in a generic event as reads
                    // unless we know it's a mutation
                    if (isFileSystemMutation(event.syscallName)) {
                        fsWritePaths.addAll(event.paths)
                    } else {
                        opens.addAll(event.paths)
                    }
                }
            }
        }

        return BillOfBehavior(
            opens = opens,
            fsWritePaths = fsWritePaths,
            syscalls = syscalls,
            execs = execs,
        )
    }

    private fun isFileSystemMutation(syscallName: String): Boolean =
        syscallName in
            setOf(
                "MKDIR",
                "MKDIRAT",
                "RMDIR",
                "UNLINK",
                "UNLINKAT",
                "RENAME",
                "RENAMEAT",
                "RENAMEAT2",
                "LINK",
                "LINKAT",
                "SYMLINK",
                "SYMLINKAT",
                "CHMOD",
                "FCHMODAT",
                "CHOWN",
                "LCHOWN",
                "FCHOWNAT",
            )

    private fun isOpenWrite(semanticEvent: TraceEvent): Boolean {
        if (semanticEvent is TraceEvent.Open) {
            if (semanticEvent.syscallName == "OPENAT2") return true // Pointer to struct open_how, conservatively treat as write
            return isOpenWrite(semanticEvent.flags)
        }
        return false
    }

    private fun isOpenWrite(flags: Long): Boolean {
        val accessMode = flags and 3L
        val isWriteMode = accessMode == O_WRONLY || accessMode == O_RDWR
        val hasCreateOrTrunc = (flags and O_CREAT) != 0L || (flags and O_TRUNC) != 0L
        return isWriteMode || hasCreateOrTrunc
    }
}
