package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenHelperOperationTest {
    @Test
    fun `native open receives original flags mode resolve and imported directory`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onOpenat2 = { fd, _, how, size ->
            assertEquals(52702, fd.value)
            assertEquals(24L, size)
            assertEquals(524353L, how.readLong(0))
            assertEquals(416L, how.readLong(8))
            assertEquals(9L, how.readLong(16))
            SupervisorBoundaryFixture.success(51002)
        }
        SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(52702), fixture.engine.fileSystem).use { directory ->
            NativeArena.ofConfined().use { arena ->
                val result = with(arena) { SocketOperationExecutor(fixture.engine).execute(SocketOperation.OpenAt2("target", 524353, 416, 9), directory) }
                assertInstanceOf(SocketHelperResult.Opened::class.java, result).dispose()
            }
        }
        assertEquals(listOf(51002, 52702), fixture.closed)
    }
}
