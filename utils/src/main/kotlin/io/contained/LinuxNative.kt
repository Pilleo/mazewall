package io.contained

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

// Corresponds to struct sock_filter
data class SockFilter(val code: Short, val jt: Short, val jf: Short, val k: Int) {
    init {
        require(jt in 0..255) { "jt offset must be an unsigned 8-bit value (0-255), got $jt" }
        require(jf in 0..255) { "jf offset must be an unsigned 8-bit value (0-255), got $jf" }
    }
}

object LinuxNative {
    private val linker = Linker.nativeLinker()
    private val stdlib = linker.defaultLookup()

    private val PRCTL: MethodHandle
    private val SYSCALL: MethodHandle

    val ERRNO_LAYOUT: StructLayout = Linker.Option.captureStateLayout()
    private val ERRNO_OFFSET = ERRNO_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    val SOCK_FILTER_LAYOUT: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("code"),
        ValueLayout.JAVA_BYTE.withName("jt"),
        ValueLayout.JAVA_BYTE.withName("jf"),
        ValueLayout.JAVA_INT.withName("k")
    )
    private val SOCK_FILTER_SIZE = SOCK_FILTER_LAYOUT.byteSize()

    val SOCK_FPROG_LAYOUT: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("len"),
        MemoryLayout.paddingLayout(6), // Align pointer to 8 bytes
        ValueLayout.ADDRESS.withName("filter")
    )
    private val SOCK_FPROG_LEN_OFFSET = SOCK_FPROG_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("len"))
    private val SOCK_FPROG_FILTER_OFFSET = SOCK_FPROG_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("filter"))

    init {
        PRCTL = downcall("prctl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), Linker.Option.captureCallState("errno"))
        SYSCALL = downcall("syscall", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), Linker.Option.captureCallState("errno"), Linker.Option.firstVariadicArg(1))
    }

    private fun downcall(name: String, desc: FunctionDescriptor, vararg options: Linker.Option): MethodHandle {
        val symbol = stdlib.find(name).orElse(null) ?: return MethodHandles.insertArguments(
            MethodHandles.throwException(desc.returnLayout().get().javaWithFallback(), UnsupportedOperationException::class.java),
            0, UnsupportedOperationException("Symbol $name not found in libc")
        )
        return linker.downcallHandle(symbol, desc, *options)
    }

    private fun MemoryLayout.javaWithFallback(): Class<*> = when (this) {
        is ValueLayout.OfInt -> Int::class.javaPrimitiveType!!
        is ValueLayout.OfLong -> Long::class.javaPrimitiveType!!
        else -> MemorySegment::class.java
    }

    fun prctl(option: Int, arg2: Long, arg3: Long, arg4: Long, arg5: Long): SyscallResult {
        Arena.ofConfined().use { arena ->
            val capturedState = arena.allocate(ERRNO_LAYOUT)
            val ret = PRCTL.invokeExact(capturedState, option, arg2, arg3, arg4, arg5) as Int
            val errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET)
            return SyscallResult(ret.toLong(), errno)
        }
    }

    fun prctl(option: Int, arg2: Long, arg3ptr: MemorySegment, arg4: Long, arg5: Long): SyscallResult =
        prctl(option, arg2, arg3ptr.address(), arg4, arg5)

    fun syscall(number: Long, arg1: Long, arg2: Long, arg3: MemorySegment): SyscallResult {
        Arena.ofConfined().use { arena ->
            val capturedState = arena.allocate(ERRNO_LAYOUT)
            val ret = SYSCALL.invokeExact(capturedState, number, arg1, arg2, arg3) as Long
            val errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET)
            return SyscallResult(ret, errno)
        }
    }

    fun newSockFProg(arena: Arena, filters: Array<SockFilter>): MemorySegment {
        val filterArraySeg = arena.allocate(MemoryLayout.sequenceLayout(filters.size.toLong(), SOCK_FILTER_LAYOUT))
        for (i in filters.indices) {
            val f = filters[i]
            val offset = i * SOCK_FILTER_SIZE
            filterArraySeg.set(ValueLayout.JAVA_SHORT, offset, f.code)
            filterArraySeg.set(ValueLayout.JAVA_BYTE, offset + 2, (f.jt.toInt() and 0xFF).toByte())
            filterArraySeg.set(ValueLayout.JAVA_BYTE, offset + 3, (f.jf.toInt() and 0xFF).toByte())
            filterArraySeg.set(ValueLayout.JAVA_INT, offset + 4, f.k)
        }
        
        val progSeg = arena.allocate(SOCK_FPROG_LAYOUT)
        progSeg.set(ValueLayout.JAVA_SHORT, SOCK_FPROG_LEN_OFFSET, filters.size.toShort())
        progSeg.set(ValueLayout.ADDRESS, SOCK_FPROG_FILTER_OFFSET, filterArraySeg)
        return progSeg
    }

    data class SyscallResult(val returnValue: Long, val errno: Int)

    const val PR_SET_NO_NEW_PRIVS = 38
    const val PR_GET_NO_NEW_PRIVS = 39
    const val PR_SET_SECCOMP = 22
    const val PR_GET_SECCOMP = 21
    const val SECCOMP_MODE_FILTER = 2
    const val SECCOMP_RET_ERRNO = 0x00050000
    const val SECCOMP_RET_ALLOW = 0x7fff0000
    const val SECCOMP_RET_KILL_THREAD = 0x00000000
    const val SECCOMP_RET_TRAP = 0x00030000
    const val SECCOMP_RET_USER_NOTIF = 0x7fc00000
    const val SECCOMP_SET_MODE_FILTER = 1
    const val SECCOMP_FILTER_FLAG_TSYNC = 1

    const val EPERM = 1
}
