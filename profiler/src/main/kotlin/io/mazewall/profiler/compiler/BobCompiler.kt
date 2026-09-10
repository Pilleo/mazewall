package io.mazewall.profiler.compiler

import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.FsEffect
import io.mazewall.profiler.NetworkEndpoint
import io.mazewall.profiler.OpenFlagsEvidence
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.UringOp
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
    fun compile(events: List<TraceEvent>): BillOfBehavior = compileObservations(events.map { ProfileObservation.fromTraceEvent(it) })

    fun compileObservations(observations: List<ProfileObservation>): BillOfBehavior {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<Syscall>()
        val execs = mutableSetOf<String>()
        val connects = mutableSetOf<NetworkEndpoint>()
        val ioUringOps = mutableSetOf<String>()

        for (obs in observations) {
            when (obs) {
                is ProfileObservation.IoUring -> {
                    ioUringOps.add(obs.opcode)
                    when (val effect = FsEffect.ofUring(UringOp.parse(obs.opcode), obs.paths)) {
                        is FsEffect.Write -> fsWritePaths.addAll(effect.paths)
                        is FsEffect.Read, is FsEffect.UnknownOpenMode -> opens.addAll(effect.paths)
                        is FsEffect.Unenforceable -> { }
                    }
                }
                is ProfileObservation.Connect -> {
                    connects.add(obs.endpoint)
                    syscalls.add(Syscall.CONNECT)
                }
                is ProfileObservation.Syscall -> applySyscall(obs, opens, fsWritePaths, syscalls, execs)
            }
        }

        return BillOfBehavior(
            opens = opens,
            fsWritePaths = fsWritePaths,
            syscalls = syscalls,
            execs = execs,
            connects = connects,
            ioUringOps = ioUringOps,
        )
    }

    private fun applySyscall(
        obs: ProfileObservation.Syscall,
        opens: MutableSet<String>,
        fsWritePaths: MutableSet<String>,
        syscalls: MutableSet<Syscall>,
        execs: MutableSet<String>,
    ) {
        val name = obs.name.uppercase(Locale.US)
        Syscall.tryParse(name)?.let { syscalls.add(it) }

        when (classify(name)) {
            SyscallEffect.Exec -> execs.addAll(obs.paths)
            SyscallEffect.Open -> applyOpen(obs, opens, fsWritePaths)
            SyscallEffect.FileSystemMutation -> fsWritePaths.addAll(obs.paths)
            SyscallEffect.NoPathEffect -> Unit
            SyscallEffect.Read -> opens.addAll(obs.paths)
        }
    }

    private fun applyOpen(
        obs: ProfileObservation.Syscall,
        opens: MutableSet<String>,
        fsWritePaths: MutableSet<String>,
    ) {
        when (val evidence = obs.openFlagsEvidence) {
            is OpenFlagsEvidence.Complete -> applyCompleteOpen(obs.paths, evidence.value, opens, fsWritePaths)
            is OpenFlagsEvidence.Incomplete, null -> opens.addAll(obs.paths)
        }
    }

    private fun applyCompleteOpen(
        paths: List<String>,
        flags: Long,
        opens: MutableSet<String>,
        fsWritePaths: MutableSet<String>,
    ) {
        when {
            (flags and NativeConstants.O_PATH.toLong()) != 0L -> Unit
            isOpenWrite(flags) -> fsWritePaths.addAll(paths)
            else -> opens.addAll(paths)
        }
    }

    private fun classify(name: String): SyscallEffect =
        when (name) {
        "EXECVE", "EXECVEAT" -> SyscallEffect.Exec
        "OPEN", "OPENAT", "OPENAT2" -> SyscallEffect.Open
        "SOCKET", "CONNECT", "MMAP" -> SyscallEffect.NoPathEffect
        in FILE_SYSTEM_MUTATIONS -> SyscallEffect.FileSystemMutation
        else -> SyscallEffect.Read
    }

    private enum class SyscallEffect { Exec, Open, FileSystemMutation, NoPathEffect, Read }

    private val FILE_SYSTEM_MUTATIONS: Set<String> =
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
                "CREAT",
                "TRUNCATE",
                "FTRUNCATE",
                "UTIME",
                "UTIMES",
                "UTIMENSAT",
        )

    private fun isOpenWrite(flags: Long): Boolean {
        val accessMode = flags and 3L
        val isWriteMode = accessMode == O_WRONLY || accessMode == O_RDWR
        val hasCreateOrTrunc = (flags and O_CREAT) != 0L || (flags and O_TRUNC) != 0L
        return isWriteMode || hasCreateOrTrunc
    }
}
