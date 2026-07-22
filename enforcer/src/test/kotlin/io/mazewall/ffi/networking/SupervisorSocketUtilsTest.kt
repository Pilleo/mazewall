package io.mazewall.ffi.networking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

class SupervisorSocketUtilsTest {

    @Test
    fun `test setupSockAddrUn`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path = "/tmp/test.sock"
            val sockaddr = SupervisorSocketUtils.setupSockAddrUn(arena, path)

            assertEquals(SupervisorSocketUtils.AF_UNIX.toShort(), sockaddr.getSunFamily())

            val pathSegment = sockaddr.getSunPath()
            val storedPath = pathSegment.getString(0, StandardCharsets.UTF_8)
            assertEquals(path, storedPath)
        }
    }

    @Test
    fun `test setupSockAddrUn with path of maximum allowed length`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path = "a".repeat(107)
            val sockaddr = SupervisorSocketUtils.setupSockAddrUn(arena, path)

            assertEquals(SupervisorSocketUtils.AF_UNIX.toShort(), sockaddr.getSunFamily())

            val pathSegment = sockaddr.getSunPath()
            val storedPath = pathSegment.getString(0, StandardCharsets.UTF_8)
            assertEquals(path, storedPath)
        }
    }

    @Test
    fun `test setupSockAddrUn with path too long throws IllegalArgumentException`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path108 = "a".repeat(108)
            val exception108 = assertThrows<IllegalArgumentException> {
                SupervisorSocketUtils.setupSockAddrUn(arena, path108)
            }
            assertEquals("Socket path too long: $path108 (length: 108, max: 107)", exception108.message)

            val path109 = "a".repeat(109)
            val exception109 = assertThrows<IllegalArgumentException> {
                SupervisorSocketUtils.setupSockAddrUn(arena, path109)
            }
            assertEquals("Socket path too long: $path109 (length: 109, max: 107)", exception109.message)
        }
    }
}
