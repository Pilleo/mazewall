package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import java.nio.charset.StandardCharsets

/**
 * Shared utility for reading memory from remote processes/threads using process_vm_readv.
 */
public object SupervisorProcessMemoryReader {
    context(arena: NativeArena)
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

    context(arena: NativeArena)
    public fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
        warnOnEperm: Boolean = false
    ): ByteArray? {
        if (remoteAddr == 0L) return null
        val localBuf = arena.arena.allocate(len.toLong())
        localBuf.fill(0)
        val localIov = IovecSegment.allocate()
        localIov.setIovBase(ConfinedSegment(localBuf))
        localIov.setIovLen(len.toLong())
        val remoteIov = IovecSegment.allocate()
        remoteIov.setIovBase(ConfinedSegment(java.lang.foreign.MemorySegment.ofAddress(remoteAddr)))
        remoteIov.setIovLen(len.toLong())

        var res: LinuxNative.SyscallResult<Long, *>
        while (true) {
            res = LinuxNative.withTransaction {
                LinuxNative.memory.processVmReadv(Pid(tid.value), ConfinedSegment(localIov.segment), 1, ConfinedSegment(remoteIov.segment), 1, 0)
            }
            if (res is LinuxNative.SyscallResult.Error && res.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                continue
            }
            break
        }
        return if (res is LinuxNative.SyscallResult.Success && res.value > 0) {
            val bytesRead = res.value.toInt()
            val dest = ByteArray(bytesRead)
            java.lang.foreign.MemorySegment.copy(localBuf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, dest, 0, bytesRead)
            dest
        } else {
            if (res is LinuxNative.SyscallResult.Error && res.errno == 1) { // EPERM = 1
                if (warnOnEperm) {
                    System.err.println("[DAEMON] WARN: Permission denied reading memory from TID ${tid.value}. (Yama ptrace_scope?)")
                }
                "<YAMA_ERROR_UNKNOWN_PATH>".toByteArray(StandardCharsets.UTF_8)
            } else {
                null
            }
        }
    }
}
