package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.NativeTransaction
import io.mazewall.RawSyscallOperations
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readInt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorSessionHandlerTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `connectSocketInSupervisor correctly parses domain without sign-extension`() {
        var capturedDomain: Int? = null

        val mockNetworking = object : MockNativeNetworking() {
            context(_: NativeTransaction)
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
            context(_: NativeTransaction)
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

            context(_: NativeTransaction)
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
            context(_: NativeTransaction)
            override fun open(
                path: io.mazewall.ffi.memory.ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(99L) // Mock opened FD
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory, fileSystem = mockFileSystem) {
            override val raw: RawSyscallOperations = object : RawSyscallOperations by this {
                context(_: NativeTransaction)
                override fun poll(
                    fds: io.mazewall.ffi.memory.ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success(1L)
                }

                context(_: NativeTransaction)
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

                // 1. Test open (should be upgraded to SECCOMP_IOCTL_NOTIF_ADDFD / emulation)
                lastIoctlRequest = null
                lastIoctlArg = null
                val argsOpen = LongArray(6)
                argsOpen[0] = 0x12345678L

                invokeReadAndHandleJvmResponse(arch.open, argsOpen)

                // SECCOMP_IOCTL_NOTIF_ADDFD is 0xc0182103L
                assertEquals(io.mazewall.ffi.NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, lastIoctlRequest)

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
}
