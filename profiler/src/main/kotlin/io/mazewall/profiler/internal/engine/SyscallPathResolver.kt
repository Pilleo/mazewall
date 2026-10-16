package io.mazewall.profiler.internal.engine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.combinePathResolution
import io.mazewall.profiler.api.pathOperands
import io.mazewall.profiler.api.resolved

/**
 * Resolves syscall path arguments by reading from the tracee's memory.
 * Transforms [SyscallEvent.Raw] into [SyscallEvent.Resolved].
 */
internal class SyscallPathResolver(
    private val memoryReader: ProfilerMemoryReader,
    private val ledger: SessionEventLedger,
) {
    context(arena: NativeArena) fun resolve(event: SyscallEvent<SyscallEventState.Raw>): SyscallEvent<SyscallEventState.Resolved> =
        SyscallEvent.fromEvidence(
            event.tid,
            event.syscallName,
            event.args,
            resolveEvidence(event.tid, event.syscallName, event.args.toLongArray()),
            ConnectAddressResolver(memoryReader).resolve(event.tid, event.syscallName, event.args),
            event.stackTrace,
        )

    context(arena: NativeArena) fun resolveEvidence(
        tid: Tid,
        syscallName: String,
        args: LongArray,
    ): PathResolution {
        if (syscallName.uppercase(java.util.Locale.ROOT) in setOf("FSTAT", "FCHMOD", "FCHOWN")) {
            return resolveDescriptor(tid, args.getOrNull(0))
        }
        val operands = pathOperands(syscallName)
        val results = operands.mapIndexed { index, operand ->
            val dirfd = operand.dirFdRegister?.let { args.getOrNull(it) } ?: AT_FDCWD_VAL
            readOperand(tid, index, args.getOrNull(operand.register), dirfd, operand.dirFdRegister != null && args.getOrNull(operand.dirFdRegister) == null)
        }
        return combinePathResolution(results.flatMap { it.paths }, results.flatMap { it.failures })
    }

    context(arena: NativeArena) private fun resolveDescriptor(
        tid: Tid,
        fd: Long?,
    ): PathResolution =
        when {
        fd == null -> failed(0, PathResolutionFailure.Reason.Missing, "TID ${tid.value}: missing descriptor")
        fd.toInt() < 0 -> failed(0, PathResolutionFailure.Reason.DirFdUnresolved, "TID ${tid.value}: invalid fd=$fd")
        else -> resolveRelativePath(tid, 0, "", fd)
    }

    /** Legacy list entry points project the evidence; production events keep the full result. */
    context(arena: NativeArena) fun resolvePaths(
        tid: Tid,
        syscallName: String,
        args: LongArray,
    ): List<String> = resolveEvidence(tid, syscallName, args).paths

    context(arena: NativeArena) fun resolvePaths(
        tid: Tid,
        syscallName: String,
        args: List<Long>,
    ): List<String> = resolvePaths(tid, syscallName, args.toLongArray())

    context(arena: NativeArena) private fun readOperand(
        tid: Tid,
        index: Int,
        address: Long?,
        dirfd: Long,
        missingDirfd: Boolean,
    ): PathResolution {
        val context = "TID ${tid.value} address=${address?.let { "0x${it.toString(16)}" }} dirfd=$dirfd"
        if (address == null || address == 0L) {
            return failed(index, PathResolutionFailure.Reason.Missing, "$context: missing path pointer")
        }
        val read = memoryReader.readPathString(tid, address)
        ledger.record(SessionEvent.VmReadvResolved(System.nanoTime(), tid.value.toLong(), read is PathStringRead.Text))
        return when (read) {
            PathStringRead.Missing -> failed(index, PathResolutionFailure.Reason.Missing, "$context: no readable path")
            is PathStringRead.Denied -> failed(index, PathResolutionFailure.Reason.VmReadDenied, "$context: memory read denied", read.cause)
            is PathStringRead.Truncated -> failed(index, PathResolutionFailure.Reason.Truncated, "$context: no terminator in ${read.bytesRead} bytes; preview=${read.preview}")
            is PathStringRead.Text -> {
                if (read.value.startsWith("/")) {
                    PathResolution.Resolved(listOf(PathNormalizerHelper.normalizePath(read.value)))
                } else if (missingDirfd) {
                    failed(index, PathResolutionFailure.Reason.DirFdUnresolved, "$context: missing dirfd for '${read.value}'")
                } else {
                    resolveRelativePath(tid, index, read.value, dirfd)
                }
            }
        }
    }

    context(arena: NativeArena) private fun resolveRelativePath(
        tid: Tid,
        index: Int,
        path: String,
        dirfd: Long,
    ): PathResolution {
        val context = "TID ${tid.value} relative path '$path' (dirfd=$dirfd)"
        return try {
            val directory = when {
                dirfd.toInt() == AT_FDCWD_INT_VAL -> memoryReader.resolveLink(tid, "cwd")
                dirfd.toInt() >= 0 -> memoryReader.resolveLink(tid, "fd/${dirfd.toInt()}")
                else -> null
            }
            if (directory == null) {
                failed(index, PathResolutionFailure.Reason.DirFdUnresolved, context)
            } else {
                PathResolution.Resolved(listOf(PathNormalizerHelper.normalizePath("$directory/$path")))
            }
        } catch (failure: io.mazewall.enforcer.api.ContainmentViolationException) {
            failed(index, PathResolutionFailure.Reason.DirFdUnresolved, context, PathResolutionCause.from(failure))
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (failure: IllegalStateException) {
            failed(index, PathResolutionFailure.Reason.DirFdUnresolved, context, PathResolutionCause.from(failure))
        }
    }

    private fun failed(
        index: Int,
        reason: PathResolutionFailure.Reason,
        detail: String,
        cause: PathResolutionCause? = null,
    ): PathResolution.Failed = PathResolution.Failed(listOf(PathResolutionFailure(index, reason, detail, cause)))
}

internal object PathNormalizerHelper {
    private val threadLocalCharBuffer = ThreadLocal.withInitial { CharArray(4096) }
    private val threadLocalIntArray = ThreadLocal.withInitial { IntArray(128) }

    fun normalizePath(path: String): String {
        if (path.isEmpty()) return path

        val chars = threadLocalCharBuffer.get()
        val stack = threadLocalIntArray.get()
        var stackSize = 0

        val isAbsolute = path.startsWith('/')
        var i = 0
        val len = path.length

        var outLen = 0
        if (isAbsolute) {
            chars[outLen++] = '/'
        }

        while (i < len) {
            while (i < len && path[i] == '/') {
                i++
            }
            if (i >= len) break

            val start = i
            while (i < len && path[i] != '/') {
                i++
            }
            val compLen = i - start

            if (compLen == 1 && path[start] == '.') {
                continue
            } else if (compLen == 2 && path[start] == '.' && path[start + 1] == '.') {
                val lastStart = if (stackSize > 0) stack[stackSize - 1] else -1
                val lastLen = if (lastStart >= 0) outLen - lastStart else 0
                val isLastDotDot = lastLen == 2 && chars[lastStart] == '.' && chars[lastStart + 1] == '.'

                if (stackSize > 0 && !isLastDotDot) {
                    stackSize--
                    val poppedStart = stack[stackSize]
                    outLen = if (poppedStart > 0 && chars[poppedStart - 1] == '/') {
                        poppedStart - 1
                    } else {
                        poppedStart
                    }
                    if (isAbsolute && outLen == 0) {
                        outLen = 1
                    }
                } else if (!isAbsolute) {
                    if (outLen > 0 && chars[outLen - 1] != '/') {
                        if (outLen + compLen + 1 > chars.size || stackSize >= stack.size) {
                            return java.nio.file.Paths
                                .get(path)
                                .normalize()
                                .toString()
                        }
                        chars[outLen++] = '/'
                    }
                    if (stackSize >= stack.size) {
                        return java.nio.file.Paths
                            .get(path)
                            .normalize()
                            .toString()
                    }
                    stack[stackSize++] = outLen
                    if (outLen + compLen > chars.size) {
                        return java.nio.file.Paths
                            .get(path)
                            .normalize()
                            .toString()
                    }
                    for (k in start until i) {
                        chars[outLen + k - start] = path[k]
                    }
                    outLen += compLen
                }
            } else {
                if (outLen > 0 && chars[outLen - 1] != '/') {
                    if (outLen + compLen + 1 > chars.size || stackSize >= stack.size) {
                        return java.nio.file.Paths
                            .get(path)
                            .normalize()
                            .toString()
                    }
                    chars[outLen++] = '/'
                }
                if (stackSize >= stack.size) {
                    return java.nio.file.Paths
                        .get(path)
                        .normalize()
                        .toString()
                }
                stack[stackSize++] = outLen
                if (outLen + compLen > chars.size) {
                    return java.nio.file.Paths
                        .get(path)
                        .normalize()
                        .toString()
                }
                for (k in start until i) {
                    chars[outLen + k - start] = path[k]
                }
                outLen += compLen
            }
        }

        if (outLen == 0) {
            return if (isAbsolute) "/" else "."
        }

        return String(chars, 0, outLen)
    }

    fun pathStartsWith(
        path: String,
        prefix: String,
    ): Boolean {
        if (path == prefix) return true
        if (prefix == "/") return path.startsWith("/")
        return path.startsWith(prefix) && path.length > prefix.length && path[prefix.length] == '/'
    }
}
