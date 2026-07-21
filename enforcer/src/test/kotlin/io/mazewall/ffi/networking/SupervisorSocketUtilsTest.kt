package io.mazewall.ffi.networking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
}
