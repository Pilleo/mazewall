package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.map
import io.mazewall.onFailure
import io.mazewall.onSuccess
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Interface for reading memory and resolving paths from a tracee process or thread.
 */
interface ProfilerMemoryReader {
    fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String?

    fun resolveLink(
        tid: Tid,
        link: String,
    ): String?
}

/**
 * Real implementation of [ProfilerMemoryReader] using process_vm_readv and readlink.
 */
object RealMemoryReader : ProfilerMemoryReader {
    private const val PATH_MAX_VAL = 4096L
    private const val IOV_LEN_OFF = 8L

    override fun readStringFromProcess(
        tid: Tid,
        remoteAddress: Long,
        maxLen: Int,
    ): String? {
        Arena.ofConfined().use { arena ->
            val localBuf = arena.allocate(maxLen.toLong())
            localBuf.fill(0)
            val localIov = arena.allocate(Layouts.IOVEC)
            localIov.set(ValueLayout.ADDRESS, 0L, localBuf)
            localIov.set(ValueLayout.JAVA_LONG, IOV_LEN_OFF, maxLen.toLong())
            val remoteIov = arena.allocate(Layouts.IOVEC)
            remoteIov.set(ValueLayout.ADDRESS, 0L, MemorySegment.ofAddress(remoteAddress))
            remoteIov.set(ValueLayout.JAVA_LONG, IOV_LEN_OFF, maxLen.toLong())
            val res = LinuxNative.withTransaction {
                LinuxNative.memory.processVmReadv(Pid(tid.value), localIov, 1, remoteIov, 1, 0)
            }
            var result: String? = null
            res.onSuccess { value ->
                val bytesRead = value.toInt()
                var len = 0
                while (len < bytesRead && localBuf.get(ValueLayout.JAVA_BYTE, len.toLong()) != 0.toByte()) len++

                if (len < bytesRead) {
                    result = localBuf.copyToString(len)
                }
            }.onFailure { errno, _ ->
                if (errno == 1) { // EPERM
                    System.err.println("[DAEMON] WARN: Permission denied reading memory from TID ${tid.value}. (Yama ptrace_scope?)")
                }
            }
            return result
        }
    }

    override fun resolveLink(
        tid: Tid,
        link: String,
    ): String? {
        val procPath = "/proc/${tid.value}/$link"
        Arena.ofConfined().use { arena ->
            val pathSeg = arena.allocateFrom(procPath)
            val buf = arena.allocate(PATH_MAX_VAL)
            val res = LinuxNative.withTransaction {
                LinuxNative.fileSystem.readlink(pathSeg, buf, PATH_MAX_VAL)
            }
            return res.onSuccess { }.map { buf.copyToString(it.toInt()) }.getOrNull()
        }
    }

    private fun MemorySegment.copyToString(len: Int): String {
        val bytes = this.asSlice(0L, len.toLong()).toArray(ValueLayout.JAVA_BYTE)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
