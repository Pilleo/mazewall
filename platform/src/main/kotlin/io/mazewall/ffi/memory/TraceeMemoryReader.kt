package io.mazewall.ffi.memory

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.NativeMemory
import io.mazewall.platform.native.map
import io.mazewall.platform.native.onFailure
import io.mazewall.platform.native.onSuccess
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val PATH_MAX_VAL = 4096L
private const val HEX_PREVIEW_BYTES = 64
private const val ADDRESS_RADIX = 16
private const val PROCESS_VM_READ_TIMEOUT_MS = 100L
private const val PROCESS_VM_READ_MAX_ATTEMPTS = 8

/**
 * Shared utility for reading memory and resolving paths from remote processes/threads using process_vm_readv and readlink.
 */
internal interface TraceeMemoryReader {
    /** Typed diagnostic; the original IllegalStateException entry-point contract is preserved. */
    public class TruncatedStringException(
        public val bytesRead: Int,
        public val preview: String,
        message: String,
    ) : IllegalStateException(message)

    /** The kernel kept interrupting a bounded tracee-memory read until its retry budget expired. */
    public class ReadRetryExhaustedException(
        public val attempts: Int,
        public val errno: Int,
        deadlineExpired: Boolean,
    ) : IllegalStateException(
        "Tracee memory read did not complete after $attempts attempt(s): errno=$errno (EINTR), deadlineExpired=$deadlineExpired",
    )

    /** A caller interruption stopped the read before another native attempt could begin. */
    public class ReadInterruptedException(
        public val attempts: Int,
    ) : IllegalStateException("Tracee memory read interrupted after $attempts attempt(s)")

    context(arena: NativeArena) public fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String?

    context(arena: NativeArena) public fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
    ): ByteArray?

    context(arena: NativeArena) public fun resolveLink(
        tid: Tid,
        link: String,
    ): String?

    private class InjectedReal(
        private val memory: NativeMemory,
        private val fileSystem: NativeFileSystem,
    ) : TraceeMemoryReader {
        context(arena: NativeArena) override fun readString(
            tid: Tid,
            remoteAddr: Long,
            maxLen: Int,
        ): String? {
            if (remoteAddr == 0L) return null
            val bytes = readBytes(tid, remoteAddr, maxLen) ?: return null
            var len = 0
            var hasNullTerminator = false
            while (len < bytes.size) {
                if (bytes[len] == 0.toByte()) {
                    hasNullTerminator = true
                    break
                }
                len++
            }
            if (!hasNullTerminator) {
                val preview = bytes.take(HEX_PREVIEW_BYTES).joinToString("") { String.format(Locale.ROOT, "%02X", it) }
                throw TruncatedStringException(
                    len,
                    preview,
                    "Remote string from TID ${tid.value} at address 0x${remoteAddr.toString(ADDRESS_RADIX)} lacks null terminator within $maxLen bytes. Read: $len bytes. Hex preview: $preview",
                )
            }
            return String(bytes, 0, len, StandardCharsets.UTF_8)
        }

        context(arena: NativeArena) override fun readBytes(
            tid: Tid,
            remoteAddr: Long,
            len: Int,
        ): ByteArray? {
            if (remoteAddr == 0L) return null
            val localBuf = arena.allocate(len.toLong())
            localBuf.fill(0)
            val localIov = IovecSegment(arena.allocate(Layouts.IOVEC).unwrap)
            localIov.setIovBase(localBuf.unwrap)
            localIov.setIovLen(len.toLong())
            val remoteIov = IovecSegment(arena.allocate(Layouts.IOVEC).unwrap)
            remoteIov.setIovBase(MemorySegment.ofAddress(remoteAddr))
            remoteIov.setIovLen(len.toLong())

            val retryBudget = ProcessVmReadRetryBudget.default()
            val res = readProcessVm(
                memory,
                tid,
                localIov,
                remoteIov,
                retryBudget,
            )
            return if (res is LinuxNative.SyscallResult.Success && res.value > 0) {
                val bytesRead = res.value.toInt()
                val dest = ByteArray(bytesRead)
                MemorySegment.copy(localBuf.unwrap, ValueLayout.JAVA_BYTE, 0L, dest, 0, bytesRead)
                dest
            } else {
                permissionFailure(res, tid, remoteAddr)
            }
        }

        private fun readProcessVm(
            memory: NativeMemory,
            tid: Tid,
            localIov: IovecSegment,
            remoteIov: IovecSegment,
            retryBudget: ProcessVmReadRetryBudget,
        ): LinuxNative.SyscallResult<Long, *> {
            var attempts = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw ReadInterruptedException(attempts)
                attempts++
                val result = memory.processVmReadv(
                    Pid(tid.value),
                    ConfinedSegment(localIov.segment),
                    1,
                    ConfinedSegment(remoteIov.segment),
                    1,
                    0,
                )
                if (result !is LinuxNative.SyscallResult.Error<*> || result.errno != NativeConstants.EINTR) return result
                val deadlineExpired = retryBudget.deadline.isExpired()
                if (attempts >= retryBudget.maxAttempts || deadlineExpired) {
                    throw ReadRetryExhaustedException(attempts, result.errno, deadlineExpired)
                }
                Thread.yield()
            }
        }

        private fun permissionFailure(
            result: LinuxNative.SyscallResult<Long, *>,
            tid: Tid,
            remoteAddr: Long,
        ): ByteArray? {
            if (result is LinuxNative.SyscallResult.Error<*> &&
                (result.errno == NativeConstants.EPERM || result.errno == NativeConstants.EACCES)
            ) {
                throw IllegalStateException(
                    "Permission denied reading memory from TID ${tid.value} at address 0x${remoteAddr.toString(ADDRESS_RADIX)} (errno=${result.errno})",
                )
            }
            return null
        }

        private data class ProcessVmReadRetryBudget(
            val deadline: Deadline,
            val maxAttempts: Int,
        ) {
            companion object {
                fun default(): ProcessVmReadRetryBudget =
                    ProcessVmReadRetryBudget(
                        deadline = Deadline.afterMillis(PROCESS_VM_READ_TIMEOUT_MS),
                        maxAttempts = PROCESS_VM_READ_MAX_ATTEMPTS,
                    )
            }
        }

        context(arena: NativeArena) override fun resolveLink(
            tid: Tid,
            link: String,
        ): String? {
            val procPath = "/proc/${tid.value}/$link"
            val pathSeg = arena.allocateFrom(procPath)
            val buf = arena.allocate(PATH_MAX_VAL)
            val res =
                fileSystem.readlink(ConfinedSegment(pathSeg.unwrap), ConfinedSegment(buf.unwrap), PATH_MAX_VAL)

            return res.onSuccess { }.map { buf.unwrap.copyToString(it.toInt()).removeSuffix(" (deleted)") }.getOrNull()
        }

        private fun MemorySegment.copyToString(len: Int): String {
            val bytes = this.asSlice(0L, len.toLong()).toArray(ValueLayout.JAVA_BYTE)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    public companion object {
        /** Construct a reader backed by the process' Linux native adapters. */
        @JvmStatic
        public fun real(): TraceeMemoryReader = InjectedReal(LinuxNative.memory, LinuxNative.fileSystem)
    }

    /** Compatibility singleton retained for existing callers. */
    public object Real : TraceeMemoryReader by InjectedReal(LinuxNative.memory, LinuxNative.fileSystem)
}
