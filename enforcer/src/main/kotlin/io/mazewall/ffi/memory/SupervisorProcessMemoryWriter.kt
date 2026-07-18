package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid

/**
 * Shared utility for writing memory to remote processes/threads using process_vm_writev.
 */
public object SupervisorProcessMemoryWriter {
    context(arena: NativeArena)
    public fun writeBytes(
        tid: Tid,
        remoteAddr: Long,
        bytes: ByteArray
    ): Boolean {
        if (remoteAddr == 0L || bytes.isEmpty()) return false
        val localBuf = arena.arena.allocate(bytes.size.toLong())
        java.lang.foreign.MemorySegment.copy(bytes, 0, localBuf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, bytes.size)

        val localIov = IovecSegment.allocate()
        localIov.setIovBase(ConfinedSegment(localBuf))
        localIov.setIovLen(bytes.size.toLong())

        val remoteIov = IovecSegment.allocate()
        remoteIov.setIovBase(ConfinedSegment(java.lang.foreign.MemorySegment.ofAddress(remoteAddr)))
        remoteIov.setIovLen(bytes.size.toLong())

        var res: LinuxNative.SyscallResult<Long, *>
        while (true) {
            res = LinuxNative.withTransaction {
                LinuxNative.memory.processVmWritev(Pid(tid.value), ConfinedSegment(localIov.segment), 1, ConfinedSegment(remoteIov.segment), 1, 0)
            }
            if (res is LinuxNative.SyscallResult.Error && res.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                continue
            }
            break
        }
        return res is LinuxNative.SyscallResult.Success && res.value == bytes.size.toLong()
    }
}
