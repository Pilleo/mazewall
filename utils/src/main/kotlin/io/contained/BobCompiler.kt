package io.contained

import java.util.Locale

/**
 * BobCompiler parses system call trace events produced by the Profiler session
 * and aggregates them into a live Policy object or a beautiful, copy-pasteable Kotlin DSL snippet.
 */
object BobCompiler {

    /**
     * Parses the given profiler trace events and returns a live Policy object.
     * Starts from the given basePolicy, and unblocks the system calls that were intercepted.
     */
    fun compile(events: List<TraceEvent>, basePolicy: Policy = Policy.PURE_COMPUTE): Policy {
        val interceptedSyscalls = mutableSetOf<Syscall>()
        val fsReadPaths = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()

        for (event in events) {
            // Map the syscall name to Syscall enum
            val syscall = try {
                Syscall.valueOf(event.syscallName.uppercase(Locale.US))
            } catch (e: IllegalArgumentException) {
                null
            }

            if (syscall != null) {
                interceptedSyscalls.add(syscall)
            }

            // Categorize paths
            val isWrite = isFileSystemMutation(event.syscallName) || isOpenWrite(event.syscallName, event.args)
            for (path in event.paths) {
                if (isWrite) {
                    fsWritePaths.add(path)
                } else {
                    fsReadPaths.add(path)
                }
            }
        }

        // We only unblock syscalls that were actually blocked by the base policy!
        val builder = Policy.builder().base(basePolicy)

        // Remove intercepted syscalls from blocklist
        builder.unblock(*interceptedSyscalls.toTypedArray())

        // Add file access paths
        for (path in fsReadPaths) {
            builder.allowFsRead(path)
        }
        for (path in fsWritePaths) {
            builder.allowFsWrite(path)
        }

        return builder.build()
    }

    /**
     * Compiles the profiler events into a clean, ready-to-use Kotlin DSL code string.
     */
    fun compileToDsl(events: List<TraceEvent>, basePolicyName: String = "Policy.PURE_COMPUTE"): String {
        val interceptedSyscalls = mutableSetOf<Syscall>()
        val fsReadPaths = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()

        for (event in events) {
            val syscall = try {
                Syscall.valueOf(event.syscallName.uppercase(Locale.US))
            } catch (e: IllegalArgumentException) {
                null
            }
            if (syscall != null) {
                interceptedSyscalls.add(syscall)
            }
            val isWrite = isFileSystemMutation(event.syscallName) || isOpenWrite(event.syscallName, event.args)
            for (path in event.paths) {
                if (isWrite) {
                    fsWritePaths.add(path)
                } else {
                    fsReadPaths.add(path)
                }
            }
        }

        val sb = StringBuilder()
        sb.append("val policy = Policy.builder()\n")
        sb.append("    .base($basePolicyName)\n")

        if (interceptedSyscalls.isNotEmpty()) {
            sb.append("    .unblock(\n")
            val sortedSyscalls = interceptedSyscalls.sortedBy { it.name }
            for (i in sortedSyscalls.indices) {
                sb.append("        Syscall.${sortedSyscalls[i].name}")
                if (i < sortedSyscalls.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("    )\n")
        }

        if (fsReadPaths.isNotEmpty()) {
            for (path in fsReadPaths.sorted()) {
                sb.append("    .allowFsRead(\"$path\")\n")
            }
        }

        if (fsWritePaths.isNotEmpty()) {
            for (path in fsWritePaths.sorted()) {
                sb.append("    .allowFsWrite(\"$path\")\n")
            }
        }

        sb.append("    .build()")
        return sb.toString()
    }

    private fun isFileSystemMutation(syscallName: String): Boolean {
        return syscallName in setOf("MKDIR", "RMDIR", "UNLINK", "RENAME", "LINK", "SYMLINK", "CHMOD", "CHOWN", "LCHOWN")
    }

    private fun isOpenWrite(syscallName: String, args: LongArray): Boolean {
        val flags = when (syscallName) {
            "OPEN" -> if (args.size > 1) args[1] else 0L
            "OPENAT", "OPENAT2" -> if (args.size > 2) args[2] else 0L
            else -> 0L
        }
        val accessMode = flags and 3L
        val isWriteMode = accessMode == 1L || accessMode == 2L // O_WRONLY = 1, O_RDWR = 2
        val hasCreateOrTrunc = (flags and 64L) != 0L || (flags and 512L) != 0L // O_CREAT = 64, O_TRUNC = 512
        return isWriteMode || hasCreateOrTrunc
    }
}
