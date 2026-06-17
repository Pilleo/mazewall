package io.mazewall.profiler.engine

import io.mazewall.core.Tid

/**
 * A semantic, polymorphic representation of a trapped system call.
 *
 * Instead of raw register arrays, this hierarchy provides named, typed properties
 * for syscall parameters, eliminating brittle index-based access in the analysis engine.
 */
public sealed class TraceEvent {
    public abstract val tid: Tid
    public abstract val syscallName: String
    public abstract val stackTrace: List<String>?

    /**
     * List of filesystem paths associated with this event.
     * Defaults to empty for non-file syscalls.
     */
    public open val paths: List<String> get() = emptyList()

    /**
     * A generic event for syscalls that don't have a specialized type yet.
     */
    public data class Generic(
        override val tid: Tid,
        override val syscallName: String,
        val args: List<Long>,
        override val paths: List<String> = emptyList(),
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Generic) return false
            // INVARIANT: TID is ignored for behavioral equality (required for deduplication)
            if (syscallName != other.syscallName) return false
            if (args != other.args) return false
            if (paths != other.paths) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + paths.hashCode()
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Specialized event for file opening operations (OPEN, OPENAT, OPENAT2).
     */
    public data class Open(
        override val tid: Tid,
        override val syscallName: String,
        val path: String,
        val flags: Long,
        val mode: Int,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override val paths: List<String> = listOf(path)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Open) return false
            if (syscallName != other.syscallName) return false
            if (path != other.path) return false
            if (flags != other.flags) return false
            if (mode != other.mode) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + flags.hashCode()
            result = 31 * result + mode
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Specialized event for process execution (EXECVE, EXECVEAT).
     */
    public data class Exec(
        override val tid: Tid,
        override val syscallName: String,
        val path: String,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override val paths: List<String> = listOf(path)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Exec) return false
            if (syscallName != other.syscallName) return false
            if (path != other.path) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Specialized event for memory mapping (MMAP).
     */
    public data class Mmap(
        override val tid: Tid,
        val addr: Long,
        val len: Long,
        val prot: Int,
        val flags: Int,
        val fd: Int,
        val offset: Long,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override val syscallName: String = "MMAP"

        /** Returns true if the mapping is executable (PROT_EXEC). */
        val isExecutable: Boolean get() = (prot and 0x04) != 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Mmap) return false
            if (addr != other.addr) return false
            if (len != other.len) return false
            if (prot != other.prot) return false
            if (flags != other.flags) return false
            if (fd != other.fd) return false
            if (offset != other.offset) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = addr.hashCode()
            result = 31 * result + len.hashCode()
            result = 31 * result + prot
            result = 31 * result + flags
            result = 31 * result + fd
            result = 31 * result + offset.hashCode()
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Specialized event for network socket operations.
     */
    public data class Socket(
        override val tid: Tid,
        val domain: Int,
        val type: Int,
        val protocol: Int,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override val syscallName: String = "SOCKET"

        /** Returns true if this is an AF_INET or AF_INET6 socket. */
        val isIpSocket: Boolean get() = domain == 2 || domain == 10

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Socket) return false
            if (domain != other.domain) return false
            if (type != other.type) return false
            if (protocol != other.protocol) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = domain
            result = 31 * result + type
            result = 31 * result + protocol
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Specialized event for filesystem mutation (MKDIR, RMDIR, UNLINK, etc.).
     */
    public data class FsMutation(
        override val tid: Tid,
        override val syscallName: String,
        override val paths: List<String>,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FsMutation) return false
            if (syscallName != other.syscallName) return false
            if (paths != other.paths) return false
            if (stackTrace != other.stackTrace) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + paths.hashCode()
            result = 31 * result + (stackTrace?.hashCode() ?: 0)
            return result
        }
    }

    public companion object {
        private const val INDEX_ADDR = 0
        private const val INDEX_LEN = 1
        private const val INDEX_PROT = 2
        private const val INDEX_FLAGS = 3
        private const val INDEX_FD = 4
        private const val INDEX_OFFSET = 5

        private const val INDEX_DOMAIN = 0
        private const val INDEX_TYPE = 1
        private const val INDEX_PROTOCOL = 2

        private const val INDEX_OPEN_FLAGS = 1
        private const val INDEX_OPENAT_FLAGS = 2

        private const val MIN_MMAP_ARGS = 6
        private const val MIN_SOCKET_ARGS = 3

        /**
         * Legacy factory method for creating a [TraceEvent].
         * Maps raw data into the polymorphic hierarchy.
         */
        public operator fun invoke(
            tidValue: Int,
            syscallName: String,
            args: LongArray,
            paths: List<String> = emptyList(),
            stackTrace: List<String>? = null,
        ): TraceEvent {
            val tid = Tid(tidValue)
            return when (syscallName) {
                "OPEN", "OPENAT", "OPENAT2" -> createOpenEvent(tid, syscallName, args, paths, stackTrace)
                "EXECVE", "EXECVEAT" -> createExecEvent(tid, syscallName, args, paths, stackTrace)
                "MMAP" -> createMmapEvent(tid, args, stackTrace)
                "SOCKET" -> createSocketEvent(tid, args, stackTrace)
                "MKDIR", "MKDIRAT", "RMDIR", "UNLINK", "UNLINKAT", "RENAME", "RENAMEAT", "RENAMEAT2", "LINK", "LINKAT", "SYMLINK", "SYMLINKAT", "CHMOD", "FCHMODAT", "CHOWN", "LCHOWN", "FCHOWNAT" ->
                    createFsMutationEvent(tid, syscallName, paths, stackTrace)

                else -> Generic(tid, syscallName, args.toList(), paths, stackTrace)
            }
        }

        private fun createOpenEvent(tid: Tid, syscallName: String, args: LongArray, paths: List<String>, stackTrace: List<String>?): TraceEvent {
            return if (paths.isNotEmpty()) {
                val flags = when (syscallName) {
                    "OPEN" -> if (args.size > INDEX_OPEN_FLAGS) args[INDEX_OPEN_FLAGS] else 0L
                    "OPENAT" -> if (args.size > INDEX_OPENAT_FLAGS) args[INDEX_OPENAT_FLAGS] else 0L
                    "OPENAT2" -> 0L // flags are inside a struct pointer in args[2]
                    else -> 0L
                }
                Open(tid, syscallName, paths[0], flags, 0, stackTrace)
            } else {
                Generic(tid, syscallName, args.toList(), paths, stackTrace)
            }
        }

        private fun createExecEvent(tid: Tid, syscallName: String, args: LongArray, paths: List<String>, stackTrace: List<String>?): TraceEvent {
            return if (paths.isNotEmpty()) Exec(tid, syscallName, paths[0], stackTrace)
            else Generic(tid, syscallName, args.toList(), paths, stackTrace)
        }

        private fun createMmapEvent(tid: Tid, args: LongArray, stackTrace: List<String>?): TraceEvent {
            return if (args.size >= MIN_MMAP_ARGS) {
                Mmap(tid, args[INDEX_ADDR], args[INDEX_LEN], args[INDEX_PROT].toInt(), args[INDEX_FLAGS].toInt(), args[INDEX_FD].toInt(), args[INDEX_OFFSET], stackTrace)
            } else {
                Generic(tid, "MMAP", args.toList(), emptyList(), stackTrace)
            }
        }

        private fun createSocketEvent(tid: Tid, args: LongArray, stackTrace: List<String>?): TraceEvent {
            return if (args.size >= MIN_SOCKET_ARGS) {
                Socket(tid, args[INDEX_DOMAIN].toInt(), args[INDEX_TYPE].toInt(), args[INDEX_PROTOCOL].toInt(), stackTrace)
            } else {
                Generic(tid, "SOCKET", args.toList(), emptyList(), stackTrace)
            }
        }

        private fun createFsMutationEvent(tid: Tid, syscallName: String, paths: List<String>, stackTrace: List<String>?): TraceEvent {
            return if (paths.isNotEmpty()) FsMutation(tid, syscallName, paths, stackTrace)
            else Generic(tid, syscallName, emptyList(), paths, stackTrace)
        }
    }
}
