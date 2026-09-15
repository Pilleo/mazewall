package io.mazewall.platform.native

import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.MmapFlags
import io.mazewall.platform.lifecycle.MmapProt
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall

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
        val nr = Syscall.MUNMAP.numberFor(Arch.current()).toLong()
        when (val result = native.syscall(nr, NativeArg.LongArg(address), NativeArg.LongArg(length))) {
            is SyscallResult.Success -> unmapped = true
            is SyscallResult.Error -> result.throwErrno("munmap")
        }
    }

    internal companion object {
        fun map(
            native: RawSyscallOperations,
            fd: Int,
            length: Long,
            protection: Int,
            offset: Long,
            flags: Int = MAP_SHARED,
        ): NativeMapping {
            val nr = Syscall.MMAP.numberFor(Arch.current()).toLong()
            val result = native.syscall(
                nr,
                NativeArg.LongArg(0),
                NativeArg.LongArg(length),
                NativeArg.LongArg(protection.toLong()),
                NativeArg.LongArg(flags.toLong()),
                NativeArg.LongArg(fd.toLong()),
                NativeArg.LongArg(offset),
            )
            val address = when (result) {
                is SyscallResult.Success -> result.value.also { check(it != 0L) { "mmap returned NULL" } }
                is SyscallResult.Error -> result.throwErrno("mmap")
            }
            return NativeMapping(native, address, length)
        }

        private const val MAP_SHARED = 1
    }
}

internal fun NativeEngine.mapMemory(
    length: Long,
    prot: MmapProt,
    flags: MmapFlags,
    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
    offset: Long = 0L,
    addr: Long = 0L,
): NativeMapping {
    val result = fileSystem.mmap(addr, length, prot, flags, fd, offset)
    val address = when (result) {
        is SyscallResult.Success -> result.value.also { check(it != 0L) { "mmap returned NULL" } }
        is SyscallResult.Error -> result.throwErrno("mmap")
    }
    return NativeMapping(raw, address, length)
}
