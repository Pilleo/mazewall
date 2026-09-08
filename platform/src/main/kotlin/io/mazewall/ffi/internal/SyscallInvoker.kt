package io.mazewall.ffi.internal

import io.mazewall.LinuxNative
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ErrnoSegment
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

/**
 * Dedicated utility to perform FFM downcalls and immediately capture errno,
 * ensuring atomicity and preventing the JVM from overwriting errno.
 */
internal object SyscallInvoker {
    private fun intCall(invoke: (MemorySegment) -> Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = invoke(capturedState.segment)
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    private fun longCall(invoke: (MemorySegment) -> Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = invoke(capturedState.segment)
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun syscall(
        handle: MethodHandle,
        nr: Long,
        a1: Long,
        a2: Long,
        a3: Long,
        a4: Long,
        a5: Long,
        a6: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, nr, a1, a2, a3, a4, a5, a6) as Long }
    }

    fun ioctlAddr(
        handle: MethodHandle,
        fd: Int,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, fd, request, arg) as Int }
    }

    fun ioctlLong(
        handle: MethodHandle,
        fd: Int,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, fd, request, arg) as Int }
    }

    fun fcntl(
        handle: MethodHandle,
        fd: Int,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, fd, cmd, arg) as Int }
    }

    fun poll(
        handle: MethodHandle,
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, fds, nfds, timeout) as Int }
    }

    fun open(
        handle: MethodHandle,
        path: MemorySegment,
        flags: Int,
        mode: Int = 0,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, path, flags, mode) as Int }
    }

    fun openat(
        handle: MethodHandle,
        dirfd: Int,
        path: MemorySegment,
        flags: Int,
        mode: Int = 0,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, dirfd, path, flags, mode) as Int }
    }

    fun mmap(
        handle: MethodHandle,
        addr: MemorySegment,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, addr, length, prot, flags, fd, offset) as MemorySegment
        return RealNativeHelper.result(ret.address(), capturedState.getErrno())
    }

    fun close(
        handle: MethodHandle,
        fd: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, fd) as Int }
    }

    fun readlink(
        handle: MethodHandle,
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, path, buf, bufsiz) as Long }
    }

    fun socketpair(
        handle: MethodHandle,
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, domain, type, protocol, sv) as Int }
    }

    fun accept4(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, sockfd, addr, addrlen, flags) as Int }
    }

    fun socket(
        handle: MethodHandle,
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, domain, type, protocol) as Int }
    }

    fun bind(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, sockfd, addr, addrlen) as Int }
    }

    fun listen(
        handle: MethodHandle,
        sockfd: Int,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, sockfd, backlog) as Int }
    }

    fun accept(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, sockfd, addr, addrlen) as Int }
    }

    fun connect(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, sockfd, addr, addrlen) as Int }
    }

    fun sendmsg(
        handle: MethodHandle,
        sockfd: Int,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, sockfd, msg, flags) as Long }
    }

    fun recvmsg(
        handle: MethodHandle,
        sockfd: Int,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, sockfd, msg, flags) as Long }
    }

    fun recv(
        handle: MethodHandle,
        sockfd: Int,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, sockfd, buf, len, flags) as Long }
    }

    fun gettid(handle: MethodHandle): Tid =
        Tid(
            intCall { handle.invokeExact(it) as Int }
                .getOrThrow("gettid")
                .toInt(),
        )

    fun prctl(
        handle: MethodHandle,
        option: Int,
        arg2: Long,
        arg3: Long,
        arg4: Long,
        arg5: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, option, arg2, arg3, arg4, arg5) as Int }
    }

    fun pidfdOpen(
        handle: MethodHandle,
        pid: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, pid, flags) as Int }
    }

    fun pidfdGetFd(
        handle: MethodHandle,
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, pidfd, targetFd, flags) as Int }
    }

    fun processVmReadv(
        handle: MethodHandle,
        pid: Int,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, pid, localIov, liovcnt, remoteIov, riovcnt, flags) as Long }
    }

    fun processVmWritev(
        handle: MethodHandle,
        pid: Int,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, pid, localIov, liovcnt, remoteIov, riovcnt, flags) as Long }
    }

    fun read(
        handle: MethodHandle,
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, fd, buf, count) as Long }
    }

    fun write(
        handle: MethodHandle,
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return longCall { handle.invokeExact(it, fd, buf, count) as Long }
    }

    fun archPrctlLong(
        handle: MethodHandle,
        code: Int,
        addr: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, code, addr) as Int }
    }

    fun archPrctlAddr(
        handle: MethodHandle,
        code: Int,
        addr: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return intCall { handle.invokeExact(it, code, addr) as Int }
    }
}
