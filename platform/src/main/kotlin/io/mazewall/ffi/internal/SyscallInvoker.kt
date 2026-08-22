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
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(
            capturedState.segment,
            nr,
            a1,
            a2,
            a3,
            a4,
            a5,
            a6,
        ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun ioctlAddr(
        handle: MethodHandle,
        fd: Int,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd, request, arg) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun ioctlLong(
        handle: MethodHandle,
        fd: Int,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd, request, arg) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun fcntl(
        handle: MethodHandle,
        fd: Int,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd, cmd, arg) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun poll(
        handle: MethodHandle,
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fds, nfds, timeout) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun open(
        handle: MethodHandle,
        path: MemorySegment,
        flags: Int,
        mode: Int = 0,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, path, flags, mode) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun openat(
        handle: MethodHandle,
        dirfd: Int,
        path: MemorySegment,
        flags: Int,
        mode: Int = 0,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, dirfd, path, flags, mode) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
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
        val ret = handle.invokeExact(
            capturedState.segment,
            addr,
            length,
            prot,
            flags,
            fd,
            offset,
        ) as MemorySegment
        return RealNativeHelper.result(ret.address(), capturedState.getErrno())
    }

    fun close(
        handle: MethodHandle,
        fd: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun readlink(
        handle: MethodHandle,
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, path, buf, bufsiz) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun socketpair(
        handle: MethodHandle,
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, domain, type, protocol, sv) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun accept4(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, addr, addrlen, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun socket(
        handle: MethodHandle,
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, domain, type, protocol) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun bind(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, addr, addrlen) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun listen(
        handle: MethodHandle,
        sockfd: Int,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, backlog) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun accept(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, addr, addrlen) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun connect(
        handle: MethodHandle,
        sockfd: Int,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, addr, addrlen) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun sendmsg(
        handle: MethodHandle,
        sockfd: Int,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, msg, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun recvmsg(
        handle: MethodHandle,
        sockfd: Int,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, msg, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun recv(
        handle: MethodHandle,
        sockfd: Int,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, sockfd, buf, len, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun gettid(
        handle: MethodHandle,
    ): Tid {
        val capturedState = ErrnoSegment.getThreadLocal()
        return Tid(handle.invokeExact(capturedState.segment) as Int)
    }

    fun prctl(
        handle: MethodHandle,
        option: Int,
        arg2: Long,
        arg3: Long,
        arg4: Long,
        arg5: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(
            capturedState.segment,
            option,
            arg2,
            arg3,
            arg4,
            arg5,
        ) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun pidfdOpen(
        handle: MethodHandle,
        pid: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, pid, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun pidfdGetFd(
        handle: MethodHandle,
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, pidfd, targetFd, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
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
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(
            capturedState.segment,
            pid,
            localIov,
            liovcnt,
            remoteIov,
            riovcnt,
            flags,
        ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
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
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(
            capturedState.segment,
            pid,
            localIov,
            liovcnt,
            remoteIov,
            riovcnt,
            flags,
        ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun read(
        handle: MethodHandle,
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd, buf, count) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun write(
        handle: MethodHandle,
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, fd, buf, count) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    fun archPrctlLong(
        handle: MethodHandle,
        code: Int,
        addr: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, code, addr) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    fun archPrctlAddr(
        handle: MethodHandle,
        code: Int,
        addr: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = handle.invokeExact(capturedState.segment, code, addr) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }
}
