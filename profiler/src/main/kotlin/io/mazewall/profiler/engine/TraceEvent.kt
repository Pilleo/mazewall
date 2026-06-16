package io.mazewall.profiler.engine

sealed class TraceEvent {
    abstract val pid: Int
    abstract val syscallName: String
    abstract val args: LongArray
    abstract val stackTrace: List<String>?

    val paths: List<String>
        get() = if (this is File) this.filePaths else emptyList()

    companion object {
        operator fun invoke(
            pid: Int,
            syscallName: String,
            args: LongArray,
            paths: List<String> = emptyList(),
            stackTrace: List<String>? = null,
        ): TraceEvent {
            return if (paths.isNotEmpty()) {
                File(pid, syscallName, args, paths, stackTrace)
            } else {
                Generic(pid, syscallName, args, stackTrace)
            }
        }
    }

    data class Generic(
        override val pid: Int,
        override val syscallName: String,
        override val args: LongArray,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Generic) return false
            if (syscallName != other.syscallName) return false
            if (!args.contentEquals(other.args)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + args.contentHashCode()
            return result
        }
    }

    data class File(
        override val pid: Int,
        override val syscallName: String,
        override val args: LongArray,
        val filePaths: List<String>,
        override val stackTrace: List<String>? = null,
    ) : TraceEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is File) return false
            if (syscallName != other.syscallName) return false
            if (!args.contentEquals(other.args)) return false
            if (filePaths != other.filePaths) return false
            return true
        }

        override fun hashCode(): Int {
            var result = syscallName.hashCode()
            result = 31 * result + args.contentHashCode()
            result = 31 * result + filePaths.hashCode()
            return result
        }
    }
}
