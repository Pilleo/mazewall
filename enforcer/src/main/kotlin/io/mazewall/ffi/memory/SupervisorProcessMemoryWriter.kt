package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Shared utility for writing memory to remote processes/threads using process_vm_writev.
 */
public object SupervisorProcessMemoryWriter {
    public fun writeBytes(
        tid: Tid,
        remoteAddr: Long,
        bytes: ByteArray
    ): Boolean {
        if (remoteAddr == 0L || bytes.isEmpty()) return false
        return nativeScope {
            val localBuf = allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, localBuf, ValueLayout.JAVA_BYTE, 0L, bytes.size)

            val localIov = IovecSegment.allocate()
            localIov.setIovBase(localBuf)
            localIov.setIovLen(bytes.size.toLong())

            val remoteIov = IovecSegment.allocate()
            remoteIov.setIovBase(MemorySegment.ofAddress(remoteAddr))
            remoteIov.setIovLen(bytes.size.toLong())

            var res: LinuxNative.SyscallResult<Long, *>
            while (true) {
                res = LinuxNative.withTransaction {
                    LinuxNative.memory.processVmWritev(Pid(tid.value), localIov.segment, 1, remoteIov.segment, 1, 0)
                }
                if (res is LinuxNative.SyscallResult.Error && res.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                    continue
                }
                break
            }
            res is LinuxNative.SyscallResult.Success && res.value == bytes.size.toLong()
        }
    }
}
