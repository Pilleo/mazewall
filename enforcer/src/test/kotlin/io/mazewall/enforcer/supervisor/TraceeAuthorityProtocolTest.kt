package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Deadline
import io.mazewall.platform.seccomp.SeccompNotification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class TraceeAuthorityProtocolTest {
    @Test
    fun `request owns six registers independently of mutable notification storage`() {
        val args = longArrayOf(-100, 4096, 524288, 0, 0, 0)
        val job = TraceeAuthorityJob(SeccompNotification(42, 123, Arch.AMD64.audit, Arch.AMD64.openat, args), Deadline.afterMillis(30000), listOf("/jdk"))
        args[1] = 1
        job.notification.args[1] = 2
        assertEquals(4096, job.notification.args[1])
    }

    @Test
    fun `bounded private request round trips identity registers and daemon bypass roots`() {
        val notification = SeccompNotification(42, 123, Arch.AMD64.audit, Arch.AMD64.openat, longArrayOf(-100, 4096, 0, 0, 0, 0))
        val output = ByteArrayOutputStream()
        TraceeAuthorityProtocol.writeRequest(DataOutputStream(output), notification, listOf("/jdk", "/agent.jar"))
        val request = TraceeAuthorityProtocol.readRequest(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals(notification, request.first)
        assertEquals(listOf("/jdk", "/agent.jar"), request.second)
    }

    @Test
    fun `oversized path malformed utf8 and invalid route never allocate unchecked frames`() {
        for (frame in listOf(byteArrayOf(0, 0, 16, 0), byteArrayOf(0, 0, 0, 2, -61, 40))) {
            assertThrows(IOException::class.java) { TraceeAuthorityProtocol.readPath(DataInputStream(ByteArrayInputStream(frame))) }
        }
        assertThrows(IOException::class.java) {
            TraceeAuthorityProtocol.readDecision(DataInputStream(ByteArrayInputStream(byteArrayOf(99))))
        }
    }
}
