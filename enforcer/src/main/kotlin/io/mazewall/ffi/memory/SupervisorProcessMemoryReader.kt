package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Shared utility for reading memory from remote processes/threads using process_vm_readv.
 */
public object SupervisorProcessMemoryReader {
    public fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
        warnOnEperm: Boolean = false
    ): String? {
        if (remoteAddr == 0L) return null
        val bytes = readBytes(tid, remoteAddr, maxLen, warnOnEperm) ?: return null
        var len = 0
        while (len < bytes.size && bytes[len] != 0.toByte()) len++
        return String(bytes, 0, len, StandardCharsets.UTF_8)
    }

    public fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
        warnOnEperm: Boolean = false
    ): ByteArray? {
        if (remoteAddr == 0L) return null
        return Arena.ofConfined().use { arena ->
            val localBuf = arena.allocate(len.toLong())
            localBuf.fill(0)
            val localIov = IovecSegment(arena.allocate(Layouts.IOVEC))
            localIov.setIovBase(localBuf)
            localIov.setIovLen(len.toLong())
            val remoteIov = IovecSegment(arena.allocate(Layouts.IOVEC))
            remoteIov.setIovBase(MemorySegment.ofAddress(remoteAddr))
            remoteIov.setIovLen(len.toLong())

            var res: LinuxNative.SyscallResult<Long, *>
            while (true) {
                res = LinuxNative.withTransaction {
                    LinuxNative.memory.processVmReadv(Pid(tid.value), localIov.segment, 1, remoteIov.segment, 1, 0)
                }
                if (res is LinuxNative.SyscallResult.Error && res.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                    continue
                }
                break
            }
            if (res is LinuxNative.SyscallResult.Success && res.value > 0) {
                val bytesRead = res.value.toInt()
                val dest = ByteArray(bytesRead)
                MemorySegment.copy(localBuf, ValueLayout.JAVA_BYTE, 0L, dest, 0, bytesRead)
                dest
            } else {
                if (res is LinuxNative.SyscallResult.Error && res.errno == 1 && warnOnEperm) { // EPERM = 1
                    System.err.println("[DAEMON] WARN: Permission denied reading memory from TID ${tid.value}. (Yama ptrace_scope?)")
                }
                null
            }
        }
    }
}
