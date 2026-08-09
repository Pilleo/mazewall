package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.RawSyscallOperations
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.seccomp.daemon.LoopAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorSessionHandlerTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `readAndHandleJvmResponse closes supervisor socket on timeout`() {
        var socketClosed = false
        val mockSocketManager = object : io.mazewall.core.SocketManager {
            override fun createUnixServer(socketPath: String) = TODO()
            override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) = TODO()
            override fun connect(socketPath: String) = TODO()
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) = TODO()
            override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>) = TODO()

            override fun close(fd: FileDescriptor<*, FdState.Open>) {
                if (fd.value == 10) {
                    socketClosed = true
                }
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L) // Simulate Timeout
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine,
                socketManager = mockSocketManager
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L
                argsToPass[2] = arch.open
                argsToPass[3] = LongArray(6)
                argsToPass[4] = pathStr
                argsToPass[5] = null
                argsToPass[6] = dummyResp
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    if (type.name.contains("Tid")) {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    } else if (type.name.contains("Pid")) {
                        argsToPass[i] = io.mazewall.core.Pid(999)
                    } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                        argsToPass[i] = 999
                    }
                }
                argsToPass[8] = arch

                val result = method.invoke(handler, *argsToPass) as Boolean
                assertEquals(false, result)
                assertEquals(true, socketClosed, "Should close the supervisor socket on timeout")
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `connectSocketInSupervisor correctly parses domain without sign-extension`() {
        var capturedDomain: Int? = null

        val mockNetworking = object : MockNativeNetworking() {
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                capturedDomain = domain
                return LinuxNative.SyscallResult.Success(99L) // Dummy socket FD
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val networking = mockNetworking
        }

        LinuxNative.setEngine(mockEngine)

        // Instantiate handler with dummy file descriptors
        val handler = SupervisorSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(-1),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(-1)
        )

        val method = SupervisorSessionHandler::class.java.getDeclaredMethod(
            "connectSocketInSupervisor",
            io.mazewall.ffi.memory.NativeArena::class.java,
            ByteArray::class.java
        )
        method.isAccessible = true

        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            // Test normal domain (AF_INET = 2) -> little endian: [2, 0]
            val normalBytes = byteArrayOf(2, 0)
            method.invoke(handler, arena, normalBytes)
            assertEquals(2, capturedDomain)

            // Test domain >= 128 (e.g. 128) -> little-endian bytes: [0x80, 0]
            // 0x80 is 128. As a signed byte it is -128.
            val highDomainBytes = byteArrayOf(0x80.toByte(), 0)
            method.invoke(handler, arena, highDomainBytes)
            assertEquals(128, capturedDomain)
        }
    }

    @Test
    fun `readAndHandleJvmResponse handles pointer-based syscalls securely without continue`() {
        var lastIoctlRequest: Long? = null
        var lastIoctlArg: io.mazewall.ffi.memory.ManagedSegment? = null
        var vmWritevCalled = false
        var capturedPid: io.mazewall.core.Pid? = null
        var capturedLocalLen: Long? = null
        var capturedRemoteBase: Long? = null
        var capturedRemoteLen: Long? = null

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun processVmWritev(
                pid: io.mazewall.core.Pid,
                localIov: io.mazewall.ffi.memory.ManagedSegment,
                liovcnt: Long,
                remoteIov: io.mazewall.ffi.memory.ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                vmWritevCalled = true
                capturedPid = pid
                capturedLocalLen = localIov.readLong(8)
                capturedRemoteBase = remoteIov.readLong(0)
                capturedRemoteLen = remoteIov.readLong(8)
                return LinuxNative.SyscallResult.Success(capturedLocalLen!!)
            }

            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val respSeg = io.mazewall.ffi.memory.SupervisorResponseSegment.of(buf)
                respSeg.setId(42L)
                respSeg.setDecision(1.toByte()) // Request Allow Continue
                respSeg.setErrorNr(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockFileSystem = object : io.mazewall.MockNativeFileSystem() {
            override fun open(
                path: io.mazewall.ffi.memory.ManagedSegment,
                flags: io.mazewall.core.OpenFlags,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(99L) // Mock opened FD
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory, fileSystem = mockFileSystem) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(1L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    lastIoctlRequest = request
                    lastIoctlArg = arg
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            // Instantiate handler with dummy file descriptors
            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11)
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val invokeReadAndHandleJvmResponse = { nr: Int, argsArray: LongArray ->
                    val paramTypes = method.parameterTypes
                    val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                    argsToPass[0] = arena
                    argsToPass[1] = 42L
                    argsToPass[2] = nr
                    argsToPass[3] = argsArray
                    argsToPass[4] = pathStr
                    argsToPass[5] = null
                    argsToPass[6] = dummyResp
                    for (i in paramTypes.indices) {
                        val type = paramTypes[i]
                        if (type.name.contains("Tid")) {
                            argsToPass[i] = io.mazewall.core.Tid(999)
                        } else if (type.name.contains("Pid")) {
                            argsToPass[i] = io.mazewall.core.Pid(999)
                        } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                            argsToPass[i] = 999
                        }
                    }
                    argsToPass[8] = arch
                    method.invoke(handler, *argsToPass)
                }

                // 1. Test open (should be upgraded to ADDFD/emulation, and call SECCOMP_IOCTL_NOTIF_ADDFD)
                lastIoctlRequest = null
                lastIoctlArg = null
                val argsOpen = LongArray(6)
                argsOpen[0] = 0x12345678L

                invokeReadAndHandleJvmResponse(arch.open, argsOpen)

                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, lastIoctlRequest)
                val addfd = io.mazewall.ffi.memory.SeccompNotifAddFdSegment.of(lastIoctlArg!!)
                assertEquals(42L, addfd.getId())
                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt(), addfd.getFlags())
                assertEquals(99, addfd.getSrcfd())

                // 2. Test execve (cannot be natively emulated, so we write back the validated memory and continue)
                lastIoctlRequest = null
                lastIoctlArg = null
                vmWritevCalled = false

                invokeReadAndHandleJvmResponse(arch.execve, argsOpen)

                // SECCOMP_IOCTL_NOTIF_SEND is 0xc0182101L (since we call sendSeccompContinue)
                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, lastIoctlRequest)
                // Wait, sendSeccompContinue sets flags to SECCOMP_USER_NOTIF_FLAG_CONTINUE (offset 20)
                val flags = lastIoctlArg!!.readInt(20)
                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt(), flags)

                // And it must write back the validated string to prevent TOCTOU!
                assertEquals(true, vmWritevCalled, "process_vm_writev should be called for execve")
                assertEquals(io.mazewall.core.Pid(999), capturedPid)
                assertEquals(pathStr.length + 1L, capturedLocalLen)
                assertEquals(0x12345678L, capturedRemoteBase)
                assertEquals(pathStr.length + 1L, capturedRemoteLen)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleActiveListener retries SECCOMP_IOCTL_NOTIF_RECV on EINTR`() {
        var ioctlCalls = 0

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(count)
            }

            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val respSeg = io.mazewall.ffi.memory.SupervisorResponseSegment.of(buf)
                respSeg.setId(42L)
                respSeg.setDecision(1.toByte()) // Request Allow Continue
                respSeg.setErrorNr(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (request == io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_RECV) {
                        ioctlCalls++
                        if (ioctlCalls == 1) {
                            return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                        }
                        // Write dummy seccomp notification info so processNotification succeeds
                        arg.writeLong(0L, 42L) // id
                        arg.writeInt(8L, 999) // pid
                        val arch = io.mazewall.core.Arch.current()
                        arg.writeInt(16L, arch.execve) // nr
                        arg.writeInt(20L, arch.audit) // arch
                        return LinuxNative.SyscallResult.Success(0L)
                    }
                    if (request == io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_SEND) {
                        return LinuxNative.SyscallResult.Success(0L)
                    }
                    return LinuxNative.SyscallResult.Success(0L)
                }

                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(1L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val pollFds = arena.allocate(io.mazewall.ffi.Layouts.POLLFD, 2)
                val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, io.mazewall.ffi.Layouts.POLLFD_SIZE))
                pfd1.setFd(11)
                pfd1.setEvents(io.mazewall.ffi.NativeConstants.POLLIN)
                pfd1.setRevents(io.mazewall.ffi.NativeConstants.POLLIN)

                val notif = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF)
                val resp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)

                val action = with(arena) {
                    handler.handleActiveListener(pollFds, notif, resp)
                }

                assertEquals(LoopAction.Continue, action)
                assertEquals(2, ioctlCalls)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `readAndHandleJvmResponse retries on EINTR during read and ioctl`() {
        var readCalls = 0
        var ioctlCalls = 0

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                readCalls++
                if (readCalls == 1) {
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                }
                val respSeg = io.mazewall.ffi.memory.SupervisorResponseSegment.of(buf)
                respSeg.setId(42L)
                respSeg.setDecision(1.toByte()) // Request Allow Continue
                respSeg.setErrorNr(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(1L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (request == io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_SEND) {
                        ioctlCalls++
                        if (ioctlCalls == 1) {
                            return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                        }
                        return LinuxNative.SyscallResult.Success(0L)
                    }
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L
                argsToPass[2] = arch.execve
                argsToPass[3] = LongArray(6)
                argsToPass[4] = pathStr
                argsToPass[5] = null
                argsToPass[6] = dummyResp
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    if (type.name.contains("Tid")) {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    } else if (type.name.contains("Pid")) {
                        argsToPass[i] = io.mazewall.core.Pid(999)
                    } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                        argsToPass[i] = 999
                    }
                }
                argsToPass[8] = arch

                val result = method.invoke(handler, *argsToPass) as Boolean
                assertEquals(true, result)
                assertEquals(2, readCalls, "Should retry read on EINTR")
                assertEquals(2, ioctlCalls, "Should retry ioctl on EINTR")
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `sendRequestToJvm retries write on EINTR`() {
        var writeCalls = 0

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writeCalls++
                if (writeCalls == 1) {
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                }
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = MockNativeEngine(memory = mockMemory)
        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("sendRequestToJvm") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L  // id
                argsToPass[2] = 999  // pid
                argsToPass[3] = 1    // arch
                argsToPass[4] = 888  // ppid
                argsToPass[5] = 2    // nr
                argsToPass[6] = LongArray(6) // args
                argsToPass[7] = "/some/path" // pathStr
                argsToPass[8] = null  // sockaddrBytes

                val result = method.invoke(handler, *argsToPass) as Boolean
                assertEquals(true, result)
                assertEquals(2, writeCalls, "Should retry write on EINTR")
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `readAndHandleJvmResponse backs off on repeated EINTR during poll`() {
        var pollCalls = 0

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val respSeg = io.mazewall.ffi.memory.SupervisorResponseSegment.of(buf)
                respSeg.setId(42L)
                respSeg.setDecision(1.toByte()) // Request Allow Continue
                respSeg.setErrorNr(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    pollCalls++
                    if (pollCalls <= 5) {
                        return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                    }
                    return LinuxNative.SyscallResult.Success(1L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L
                argsToPass[2] = arch.execve
                argsToPass[3] = LongArray(6)
                argsToPass[4] = pathStr
                argsToPass[5] = null
                argsToPass[6] = dummyResp
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    if (type.name.contains("Tid")) {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    } else if (type.name.contains("Pid")) {
                        argsToPass[i] = io.mazewall.core.Pid(999)
                    } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                        argsToPass[i] = 999
                    }
                }
                argsToPass[8] = arch

                val result = method.invoke(handler, *argsToPass) as Boolean
                assertEquals(true, result)
                assertEquals(6, pollCalls, "Should retry poll on EINTR until success")
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `readAndHandleJvmResponse terminates on thread interrupt`() {
        var pollCalls = 0

        val mockEngine = object : MockNativeEngine() {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    pollCalls++
                    Thread.currentThread().interrupt() // Interrupt on first call
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L
                argsToPass[2] = arch.execve
                argsToPass[3] = LongArray(6)
                argsToPass[4] = pathStr
                argsToPass[5] = null
                argsToPass[6] = dummyResp
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    if (type.name.contains("Tid")) {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    } else if (type.name.contains("Pid")) {
                        argsToPass[i] = io.mazewall.core.Pid(999)
                    } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                        argsToPass[i] = 999
                    }
                }
                argsToPass[8] = arch

                // Ensure thread is not currently interrupted
                Thread.interrupted()

                val result = method.invoke(handler, *argsToPass) as Boolean
                assertEquals(false, result)
                assertEquals(1, pollCalls, "Should terminate loop immediately after interruption")
                assertEquals(true, Thread.currentThread().isInterrupted, "Thread interrupt status should be preserved")
            }
        } finally {
            Thread.interrupted() // Clean up interrupt status
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleInjectFd performs FD injection for pointer-based system calls`() {
        var ioctlCalled = false
        var capturedRequest: Long? = null
        var capturedArg: io.mazewall.ffi.memory.ManagedSegment? = null

        val mockEngine = object : MockNativeEngine() {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    ioctlCalled = true
                    capturedRequest = request
                    capturedArg = arg
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val handleInjectFdMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("handleInjectFd") && !it.name.contains("$") && it.parameterCount == 9
            }
            handleInjectFdMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)

                val testSyscalls = listOf(arch.open, arch.openat, arch.openat2, arch.connect)
                for (syscall in testSyscalls) {
                    ioctlCalled = false
                    capturedRequest = null
                    capturedArg = null

                    val sockaddrBytes = if (syscall == arch.connect) {
                        byteArrayOf(2, 0) // AF_INET little endian
                    } else {
                        null
                    }

                    val result = handleInjectFdMethod.invoke(
                        handler,
                        arena, // context receiver
                        12345L, // id
                        syscall, // nr
                        LongArray(6), // args
                        "/bin/echo", // pathStr
                        sockaddrBytes, // sockaddrBytes
                        dummyResp, // resp
                        999, // tid (compiled as primitive Int)
                        arch // traceeArch
                    ) as Boolean

                    assertEquals(true, result, "handleInjectFd should return true for pointer-based syscalls")
                    assertEquals(true, ioctlCalled, "ioctl should be called for pointer-based syscalls")
                    assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, capturedRequest)
                    val addfd = io.mazewall.ffi.memory.SeccompNotifAddFdSegment.of(capturedArg!!)
                    assertEquals(12345L, addfd.getId())
                    assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt(), addfd.getFlags())
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleInjectFd falls back to CONTINUE when pathStr or sockaddrBytes is null`() {
        var ioctlCalled = false
        var capturedRequest: Long? = null
        var capturedArg: io.mazewall.ffi.memory.ManagedSegment? = null

        val mockEngine = object : MockNativeEngine() {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    ioctlCalled = true
                    capturedRequest = request
                    capturedArg = arg
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val handleInjectFdMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("handleInjectFd") && !it.name.contains("$") && it.parameterCount == 9
            }
            handleInjectFdMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)

                val testSyscalls = listOf(arch.open, arch.openat, arch.openat2, arch.connect)
                for (syscall in testSyscalls) {
                    ioctlCalled = false
                    capturedRequest = null
                    capturedArg = null

                    val result = handleInjectFdMethod.invoke(
                        handler,
                        arena, // context receiver
                        12345L, // id
                        syscall, // nr
                        LongArray(6), // args
                        null, // pathStr is null
                        null, // sockaddrBytes is null
                        dummyResp, // resp
                        999, // tid (compiled as primitive Int)
                        arch // traceeArch
                    ) as Boolean

                    assertEquals(true, result, "handleInjectFd should return true and succeed")
                    assertEquals(true, ioctlCalled, "ioctl should be called to send seccomp response")
                    assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, capturedRequest)
                    val flags = capturedArg!!.readInt(20) // RESP_FLAGS_OFF
                    assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt(), flags)
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleAcceptAsync cleans up resources on failures`() {
        val closedFds = mutableSetOf<Int>()

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = object : io.mazewall.MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closedFds.add(fd.value)
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }

            override val process = object : io.mazewall.MockNativeProcess() {
                override fun pidfdOpen(tgid: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(100L) // pidfd = 100
                }

                override fun pidfdGetFd(pidfd: Int, targetFd: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Simulate failure
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EBADF, -1L)
                }
            }

            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val handleAcceptAsyncMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("handleAcceptAsync") && !it.name.contains("$") && it.parameterCount == 5
            }
            handleAcceptAsyncMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            val paramTypes = handleAcceptAsyncMethod.parameterTypes
            val argsToPass = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> {
                        argsToPass[i] = 12345L
                    }
                    type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> {
                        if (i == 1) {
                            argsToPass[i] = arch.accept
                        } else {
                            argsToPass[i] = 999
                        }
                    }
                    type == LongArray::class.java -> {
                        argsToPass[i] = LongArray(6) { 55L }
                    }
                    type.name.contains("Tid") -> {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    }
                    type.name.contains("Arch") -> {
                        argsToPass[i] = arch
                    }
                }
            }

            handleAcceptAsyncMethod.invoke(handler, *argsToPass)

            // Since it runs in a daemon thread, let's wait a bit for it to run and finish.
            var attempts = 0
            while (attempts < 20 && !closedFds.contains(100)) {
                Thread.sleep(50)
                attempts++
            }

            // Verify that the opened pidfd (100) was successfully closed even after pidfd_getfd failure!
            assertEquals(true, closedFds.contains(100), "pidfd should have been closed")
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleAcceptAsync cleans up resources on accept4 failure`() {
        val closedFds = mutableSetOf<Int>()

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = object : io.mazewall.MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closedFds.add(fd.value)
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }

            override val process = object : io.mazewall.MockNativeProcess() {
                override fun pidfdOpen(tgid: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(100L) // pidfd = 100
                }

                override fun pidfdGetFd(pidfd: Int, targetFd: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(200L) // dupFd = 200
                }
            }

            override val networking = object : MockNativeNetworking() {
                override fun accept4(
                    fd: FileDescriptor<*, FdState.Open>,
                    addr: io.mazewall.ffi.memory.ManagedSegment,
                    addrlen: io.mazewall.ffi.memory.ManagedSegment,
                    flags: Int
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Simulate accept4 failure
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EBADF, -1L)
                }
            }

            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val handleAcceptAsyncMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("handleAcceptAsync") && !it.name.contains("$") && it.parameterCount == 5
            }
            handleAcceptAsyncMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            val paramTypes = handleAcceptAsyncMethod.parameterTypes
            val argsToPass = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> {
                        argsToPass[i] = 12345L
                    }
                    type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> {
                        if (i == 1) {
                            argsToPass[i] = arch.accept
                        } else {
                            argsToPass[i] = 999
                        }
                    }
                    type == LongArray::class.java -> {
                        argsToPass[i] = LongArray(6) { 55L }
                    }
                    type.name.contains("Tid") -> {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    }
                    type.name.contains("Arch") -> {
                        argsToPass[i] = arch
                    }
                }
            }

            handleAcceptAsyncMethod.invoke(handler, *argsToPass)

            // Wait for daemon thread
            var attempts = 0
            while (attempts < 20 && (!closedFds.contains(100) || !closedFds.contains(200))) {
                Thread.sleep(50)
                attempts++
            }

            // Verify both pidfd and dupFd are closed
            assertEquals(true, closedFds.contains(100), "pidfd (100) should have been closed")
            assertEquals(true, closedFds.contains(200), "dupFd (200) should have been closed")
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `handleAcceptAsync cleans up resources on success`() {
        val closedFds = mutableSetOf<Int>()

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = object : io.mazewall.MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closedFds.add(fd.value)
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }

            override val process = object : io.mazewall.MockNativeProcess() {
                override fun pidfdOpen(tgid: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(100L) // pidfd = 100
                }

                override fun pidfdGetFd(pidfd: Int, targetFd: Int, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(200L) // dupFd = 200
                }
            }

            override val networking = object : MockNativeNetworking() {
                override fun accept4(
                    fd: FileDescriptor<*, FdState.Open>,
                    addr: io.mazewall.ffi.memory.ManagedSegment,
                    addrlen: io.mazewall.ffi.memory.ManagedSegment,
                    flags: Int
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(300L) // clientFd = 300
                }
            }

            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L) // successful injection ioctl
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val handleAcceptAsyncMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("handleAcceptAsync") && !it.name.contains("$") && it.parameterCount == 5
            }
            handleAcceptAsyncMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            val paramTypes = handleAcceptAsyncMethod.parameterTypes
            val argsToPass = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> {
                        argsToPass[i] = 12345L
                    }
                    type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> {
                        if (i == 1) {
                            argsToPass[i] = arch.accept
                        } else {
                            argsToPass[i] = 999
                        }
                    }
                    type == LongArray::class.java -> {
                        argsToPass[i] = LongArray(6) { 55L }
                    }
                    type.name.contains("Tid") -> {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    }
                    type.name.contains("Arch") -> {
                        argsToPass[i] = arch
                    }
                }
            }

            handleAcceptAsyncMethod.invoke(handler, *argsToPass)

            // Wait for daemon thread
            var attempts = 0
            while (attempts < 20 && (!closedFds.contains(100) || !closedFds.contains(200) || !closedFds.contains(300))) {
                Thread.sleep(50)
                attempts++
            }

            // Verify all descriptors are closed
            assertEquals(true, closedFds.contains(100), "pidfd (100) should have been closed")
            assertEquals(true, closedFds.contains(200), "dupFd (200) should have been closed")
            assertEquals(true, closedFds.contains(300), "clientFd (300) should have been closed")
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `testOpenFileInSupervisorWithRelativePath`() {
        var openCalledWithAtFdcwd = false
        var capturedPath: String? = null

        val mockFileSystem = object : io.mazewall.MockNativeFileSystem() {
            override fun open(
                path: io.mazewall.ffi.memory.ManagedSegment,
                flags: io.mazewall.core.OpenFlags,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                openCalledWithAtFdcwd = true
                val bytes = ByteArray(4096)
                var i = 0
                while (i < bytes.size) {
                    val b = path.readByte(i.toLong())
                    if (b == 0.toByte()) break
                    bytes[i] = b
                    i++
                }
                capturedPath = String(bytes, 0, i, java.nio.charset.StandardCharsets.UTF_8)
                return LinuxNative.SyscallResult.Success(99L)
            }

            override fun openat(
                dirfd: Int,
                path: io.mazewall.ffi.memory.ManagedSegment,
                flags: io.mazewall.core.OpenFlags,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(99L)
            }
        }

        val mockEngine = object : MockNativeEngine(fileSystem = mockFileSystem) {}

        try {
            LinuxNative.setEngine(mockEngine)

            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine
            )

            val openFileMethod = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("openFileInSupervisor") && !it.name.contains("$")
            }
            openFileMethod.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val args = LongArray(6)
                args[0] = 5L // non-standard dirfd
                args[2] = 0L // flags

                // Since we resolved the relative path (e.g. relative_file.txt) in processNotification,
                // the path passed to openFileInSupervisor starts with "/" (meaning absolute resolved path)
                val resolvedPath = "/tmp/resolved_relative_file.txt"
                val result = openFileMethod.invoke(
                    handler,
                    arena,
                    arch.openat,
                    args,
                    resolvedPath,
                    arch
                ) as Int

                assertEquals(99, result)
                assertEquals(true, openCalledWithAtFdcwd, "Should have used open with AT_FDCWD for absolute resolved path")
                assertEquals(resolvedPath, capturedPath, "Should have opened the correct resolved path")
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `readAndHandleJvmResponse denies execve when memory writeback fails`() {
        var lastIoctlRequest: Long? = null
        var lastIoctlArg: io.mazewall.ffi.memory.ManagedSegment? = null
        var vmWritevCalled = false

        val mockMemory = object : io.mazewall.MockNativeMemory() {
            override fun processVmWritev(
                pid: io.mazewall.core.Pid,
                localIov: io.mazewall.ffi.memory.ManagedSegment,
                liovcnt: Long,
                remoteIov: io.mazewall.ffi.memory.ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                vmWritevCalled = true
                // Simulate write failure by returning an error instead of number of bytes written
                return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EPERM, -1L)
            }

            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val respSeg = io.mazewall.ffi.memory.SupervisorResponseSegment.of(buf)
                respSeg.setId(42L)
                respSeg.setDecision(1.toByte()) // Request Allow Continue
                respSeg.setErrorNr(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(1L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    lastIoctlRequest = request
                    lastIoctlArg = arg
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        try {
            LinuxNative.setEngine(mockEngine)

            // Instantiate handler with dummy file descriptors
            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11)
            )

            val method = SupervisorSessionHandler::class.java.getDeclaredMethods().first {
                it.name.startsWith("readAndHandleJvmResponse") && !it.name.contains("$") && it.parameterCount == 9
            }
            method.isAccessible = true

            val arch = io.mazewall.core.Arch.current()

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val dummyResp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)
                val pathStr = "/bin/echo"

                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                argsToPass[0] = arena
                argsToPass[1] = 42L
                argsToPass[2] = arch.execve
                argsToPass[3] = LongArray(6).apply { this[0] = 0x12345678L }
                argsToPass[4] = pathStr
                argsToPass[5] = null
                argsToPass[6] = dummyResp
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    if (type.name.contains("Tid")) {
                        argsToPass[i] = io.mazewall.core.Tid(999)
                    } else if (type.name.contains("Pid")) {
                        argsToPass[i] = io.mazewall.core.Pid(999)
                    } else if (i == 7 && (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java)) {
                        argsToPass[i] = 999
                    }
                }
                argsToPass[8] = arch

                val result = method.invoke(handler, *argsToPass) as Boolean

                // Since memory writeback failed, it must deny the system call with EPERM, but continue supervisor notification loop.
                assertEquals(true, result, "Should return true to continue processing notification loop")
                assertEquals(true, vmWritevCalled, "process_vm_writev should be called for execve")

                // SECCOMP_IOCTL_NOTIF_SEND is 0xc0182101L (since we call sendSeccompError)
                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, lastIoctlRequest)

                // And it must deny with EPERM (and flags must be 0)
                // RESP_ERR_OFF is 16, which should be -EPERM (-1)
                val errVal = lastIoctlArg!!.readInt(16)
                assertEquals(-io.mazewall.ffi.NativeConstants.EPERM.toInt(), errVal)

                val flags = lastIoctlArg!!.readInt(20)
                assertEquals(0, flags)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    /**
     * Regression test for: narrowing `catch (t: Throwable)` to `catch (e: Exception)`
     * in `processNotification`.
     *
     * Before the fix, a fatal `OutOfMemoryError` thrown inside the try block would be
     * silently swallowed by the `catch (t: Throwable)` guard, returning `false` and
     * leaving tracee threads permanently hung in the kernel (waiting for an EPERM ACK
     * that would never arrive, since the secondary `sendSeccompError` would also be
     * executed under an already-exhausted heap).
     *
     * After the fix, the `OutOfMemoryError` propagates unimpeded, allowing the JVM to
     * crash the supervisor thread cleanly rather than silently deadlocking tracees.
     */
    @Test
    fun `processNotification propagates OutOfMemoryError instead of swallowing it`() {
        val mockMemory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                throw OutOfMemoryError("Simulated heap exhaustion inside processNotification")
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val memory = mockMemory
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Allow SECCOMP_IOCTL_NOTIF_RECV to succeed (entering processNotification)
                    return LinuxNative.SyscallResult.Success(0L)
                }

                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        }

        LinuxNative.setEngine(mockEngine)
        try {
            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
                engine = mockEngine,
            )

            val processNotificationMethod = SupervisorSessionHandler::class.java
                .getDeclaredMethod(
                    "processNotification",
                    io.mazewall.ffi.memory.ManagedSegment::class.java,
                    io.mazewall.ffi.memory.ManagedSegment::class.java,
                )
            processNotificationMethod.isAccessible = true

            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF)
                val resp = arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP)

                // Write a valid x86_64 audit arch so Arch.fromAudit succeeds
                notif.writeInt(20L, io.mazewall.core.Arch.AMD64.audit) // NOTIF_ARCH_OFF = 20
                // nr = getpid (39) — no path resolution needed, goes straight to sendRequestToJvm
                notif.writeInt(16L, io.mazewall.core.Arch.AMD64.getpid) // NOTIF_NR_OFF = 16
                // pid = 1 (init, always readable from /proc)
                notif.writeInt(8L, 1) // NOTIF_PID_OFF = 8

                val thrownException = org.junit.jupiter.api.Assertions.assertThrows(
                    java.lang.reflect.InvocationTargetException::class.java,
                ) {
                    processNotificationMethod.invoke(handler, notif, resp)
                }

                // The root cause must be the OutOfMemoryError, not wrapped in a RuntimeException
                // (which would indicate the old Throwable catch had re-thrown it or suppressed it)
                val rootCause = thrownException.cause
                assertEquals(
                    OutOfMemoryError::class.java, rootCause?.javaClass,
                    "Fatal JVM errors must propagate out of processNotification unchanged, " +
                        "not be swallowed by catch (e: Exception)",
                )
                assertEquals(
                    "Simulated heap exhaustion inside processNotification",
                    rootCause?.message,
                )
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
