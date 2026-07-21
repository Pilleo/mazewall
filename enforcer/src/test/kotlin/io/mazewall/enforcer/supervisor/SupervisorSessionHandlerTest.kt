package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.NativeTransaction
import io.mazewall.ffi.internal.RealNativeEngine
import java.lang.foreign.Arena
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
}
