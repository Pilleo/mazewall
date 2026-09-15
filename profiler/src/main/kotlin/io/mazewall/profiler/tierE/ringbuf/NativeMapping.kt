package io.mazewall.profiler.tierE.ringbuf

import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.NativeArg

/** Owns one mmap/munmap pair. Failed munmap stays mapped for a later close. */
internal class NativeMapping(
    private val native: RawSyscallOperations,
    val address: Long,
    val length: Long,
) : AutoCloseable {
    private var unmapped = false

    @Synchronized
    override fun close() {
        if (unmapped) return
        when (val result = native.syscall(SYS_MUNMAP, NativeArg.LongArg(address), NativeArg.LongArg(length))) {
            is SyscallResult.Success -> unmapped = true
            is SyscallResult.Error -> result.throwErrno("munmap")
        }
    }

    internal companion object {
        private const val SYS_MMAP = 9L
        private const val SYS_MUNMAP = 11L
        private const val MAP_SHARED = 1

        fun map(
            native: RawSyscallOperations,
            fd: Int,
            length: Long,
            protection: Int,
            offset: Long,
        ): NativeMapping {
            val result = native.syscall(
                SYS_MMAP,
                NativeArg.LongArg(0),
                NativeArg.LongArg(length),
                NativeArg.LongArg(protection.toLong()),
                NativeArg.LongArg(MAP_SHARED.toLong()),
                NativeArg.LongArg(fd.toLong()),
                NativeArg.LongArg(offset),
            )
            val address = when (result) {
                is SyscallResult.Success -> result.value.also { check(it != 0L) { "mmap returned NULL" } }
                is SyscallResult.Error -> result.throwErrno("mmap")
            }
            return NativeMapping(native, address, length)
        }
    }
}
