package io.mazewall.ffi.internal

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.native
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import java.lang.foreign.ValueLayout

/** Explicit top-level pointer schemas; unknown managed arguments never reach syscall(2). */
internal object RawNativeTransfer {
    // SUPPRESSION-RATIONALE: This UAPI schema table keeps argument indices and native widths adjacent; requireComplete rejects every uncovered pointer.
    fun validate(
        nr: Long,
        args: Array<NativeArg>,
    ) {
        if (args.none { it is NativeArg.MemoryArg }) return // Explicit raw-address escape hatch.
        val a = Arguments(args)
        val arch = Arch.current()
        val x86 = arch.name == "amd64"
        when (nr) {
            arch.read.toLong(), arch.pread64.toLong(), arch.recvfrom.toLong() -> {
                a.bytes(1, a.number(2), writable = true)
                if (nr == arch.recvfrom.toLong()) a.socketAddress(4, 5)
            }
            arch.write.toLong(), arch.pwrite64.toLong(), arch.sendto.toLong() -> {
                a.bytes(1, a.number(2))
                if (nr == arch.sendto.toLong()) a.bytes(4, a.number(5))
            }
            arch.getcwd.toLong(), arch.getrandom.toLong() -> a.bytes(0, a.number(1), writable = true)
            // struct timespec is two native longs on the supported LP64 ABIs.
            arch.clock_gettime.toLong() -> a.bytes(1, 16, writable = true)
            arch.mprotect.toLong(), arch.pkeyMprotect.toLong() -> a.bytes(0, a.number(1))
            arch.readlink.toLong() -> {
                a.path(0)
                a.bytes(1, a.number(2), writable = true)
            }
            arch.readlinkat.toLong() -> {
                a.path(1)
                a.bytes(2, a.number(3), writable = true)
            }
            arch.open.toLong(), arch.memfdCreate.toLong() -> a.path(0)
            arch.openat.toLong() -> a.path(1)
            arch.openat2.toLong() -> {
                a.path(1)
                a.bytes(2, a.number(3))
            }
            arch.ioctl.toLong() -> a.memory(2)?.let { NativeTransfer.ioctl(a.number(1), it) }
            // poll is absent on aarch64; ppoll has a mutable timespec and optional sigset.
            if (x86) 7L else Long.MIN_VALUE -> a.elements(0, a.number(1), 8, writable = true)
            if (x86) 271L else 73L -> {
                a.elements(0, a.number(1), 8, writable = true)
                a.bytes(2, 16, writable = true)
                a.bytes(3, a.number(4))
            }
            arch.processVmReadv.toLong(), arch.processVmWritev.toLong() -> {
                a.elements(1, a.number(2), Layouts.IOVEC.byteSize())
                a.elements(3, a.number(4), Layouts.IOVEC.byteSize())
            }
            arch.bind.toLong(), arch.connect.toLong() -> a.bytes(1, a.number(2))
            arch.accept.toLong(), arch.accept4.toLong(), arch.getsockname.toLong(), arch.getpeername.toLong() -> a.socketAddress(1, 2)
            arch.setsockopt.toLong() -> a.bytes(3, a.number(4))
            arch.getsockopt.toLong() -> a.socketAddress(3, 4)
            arch.sendmsg.toLong() -> a.bytes(1, Layouts.MSGHDR.byteSize())
            if (x86) 47L else 212L -> a.bytes(1, Layouts.MSGHDR.byteSize(), writable = true) // recvmsg
            if (x86) 53L else 199L -> a.bytes(3, 8, writable = true) // socketpair
            arch.bpf.toLong() -> a.bytes(1, a.number(2), writable = true)
            NativeConstants.LANDLOCK_CREATE_RULESET_NR -> a.bytes(0, a.number(1))
            NativeConstants.LANDLOCK_ADD_RULE_NR -> when (a.number(1)) {
                1L -> a.bytes(2, Layouts.LANDLOCK_PATH_BENEATH_ATTR.byteSize())
                2L -> a.bytes(2, 16) // landlock_net_port_attr
            }
            arch.seccompSyscallNumber.toLong() -> when (a.number(0)) {
                1L -> a.bytes(2, Layouts.SOCK_FPROG.byteSize())
                2L -> a.bytes(2, 4)
                3L -> a.bytes(2, 6, writable = true)
            }
            if (x86) 298L else 241L -> a.memory(0)?.let {
                // perf_event_open
                NativeTransfer.bytes(it, 8, writable = true)
                val size = Integer.toUnsignedLong(it.native.get(ValueLayout.JAVA_INT, 4))
                NativeTransfer.bytes(it, if (size == 0L) 64L else size, writable = true)
            }
        }
        a.requireComplete()
    }

    private class Arguments(
        private val args: Array<NativeArg>,
    ) {
        private val checked = BooleanArray(args.size)

        fun number(index: Int): Long {
            val arg = args[index]
            require(arg !is NativeArg.MemoryArg || (arg.value.address() == 0L && arg.value.byteSize() == 0L)) { "Managed pointer supplied as syscall scalar argument ${index + 1}" }
            return arg.asLong
        }

        fun memory(index: Int): ManagedSegment? {
            val arg = args[index]
            if (arg is NativeArg.MemoryArg) {
                checked[index] = true
                NativeTransfer.bytes(arg.value)
                return arg.value
            }
            require(arg.asLong == 0L) { "Raw pointer mixed with managed syscall arguments at ${index + 1}" }
            return null
        }

        fun bytes(
            index: Int,
            count: Long,
            writable: Boolean = false,
        ) {
            memory(index)?.let { NativeTransfer.bytes(it, count, writable) }
        }

        fun elements(
            index: Int,
            count: Long,
            size: Long,
            writable: Boolean = false,
        ) {
            memory(index)?.let { NativeTransfer.elements(it, count, size, writable) }
        }

        fun path(index: Int) {
            memory(index)?.let(NativeTransfer::path)
        }

        fun socketAddress(
            address: Int,
            length: Int,
        ) {
            val buffer = memory(address)
            val size = memory(length)
            if (buffer != null && buffer.address() != 0L) {
                requireNotNull(size) { "Managed socket output requires a managed length" }
                NativeTransfer.socketAddress(buffer, size)
            }
        }

        fun requireComplete() {
            args.forEachIndexed { index, arg ->
                if (arg is NativeArg.MemoryArg && !checked[index]) {
                    // Historical callers also use MemoryArg(NULL) for scalar zero/unused slots.
                    require(arg.value.address() == 0L && arg.value.byteSize() == 0L) { "No managed pointer schema for syscall argument ${index + 1}" }
                    NativeTransfer.bytes(arg.value, 0)
                }
            }
        }
    }
}
