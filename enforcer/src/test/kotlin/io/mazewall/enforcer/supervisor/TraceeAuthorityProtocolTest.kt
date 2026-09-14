package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
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

    @Test
    fun `all authority routes and bounded snapshots round trip without aliasing`() {
        val routes = listOf(SupervisorRoute.Continue, SupervisorRoute.AskJvm, SupervisorRoute.InjectFd, SupervisorRoute.SecureExec, SupervisorRoute.Abort(13, "denied"))
        for (route in routes) {
            val address = byteArrayOf(2, 0, 1, 127)
            val snapshot = TraceeAuthoritySnapshot(17, "/tmp/λ", "/tmp/λ", route, address)
            address[0] = 99
            val output = ByteArrayOutputStream()
            TraceeAuthorityProtocol.writeSnapshot(DataOutputStream(output), snapshot)
            val received = TraceeAuthorityProtocol.readSnapshot(DataInputStream(ByteArrayInputStream(output.toByteArray())))
            assertEquals(17, received.ppid)
            assertEquals("/tmp/λ", received.path)
            assertEquals("/tmp/λ", received.resolvedPath)
            assertArrayEquals(byteArrayOf(2, 0, 1, 127), received.address)
            received.address!![0] = 3
            assertEquals(2.toByte(), received.address!![0])
            if (route is SupervisorRoute.Abort) {
                assertEquals(route.errno, (received.route as SupervisorRoute.Abort).errno)
            } else {
                assertEquals(route, received.route)
            }
            output.reset()
            TraceeAuthorityProtocol.writeDecision(DataOutputStream(output), TraceeAuthorityDecision(route, null))
            val decision = TraceeAuthorityProtocol.readDecision(DataInputStream(ByteArrayInputStream(output.toByteArray())))
            assertNull(decision.path)
            if (route is SupervisorRoute.Abort) {
                assertEquals(route.errno, (decision.route as SupervisorRoute.Abort).errno)
            } else {
                assertEquals(route, decision.route)
            }
        }
    }

    @Test
    fun `terminal effects transfer one descriptor only for descriptor carrying variants`() {
        val fixture = SupervisorBoundaryFixture()
        val descriptor = SupervisorOwnedFd(
            io.mazewall.platform.lifecycle.TestFileDescriptors
            .replace<io.mazewall.platform.fd.FileDescriptorRole.Generic>(52921),
                fixture.engine.fileSystem,
        )
        val exec = SecureExecRequest(
            io.mazewall.platform.lifecycle
            .Tid(123),
                12,
            13,
            Arch.AMD64.execveat.toLong(),
        )
        val effects = listOf(
            SupervisorReplyEffect.Continue,
            SupervisorReplyEffect.Error(13),
            SupervisorReplyEffect.Value(7),
            SupervisorReplyEffect.AddFdAndReply(
                descriptor,
                io.mazewall.platform.lifecycle
                .NewFdFlags(524288),
            ),
            SupervisorReplyEffect.SecureExecAndReply(exec, SupervisorDescriptorResult.Opened(descriptor), 1234),
            SupervisorReplyEffect.SecureExecAndReply(exec, SupervisorDescriptorResult.Denied(1), 0),
            SupervisorReplyEffect.UnsupportedAuthority(UnsupportedTraceeAuthority.AcceptPeerMemory),
        )
        descriptor.use {
            for (effect in effects) {
                val output = ByteArrayOutputStream()
                var sent = 0
                var received = 0
                TraceeAuthorityProtocol.writeEffect(DataOutputStream(output), effect) { actual ->
                    assertSame(descriptor, actual)
                    sent++
                }
                val result = TraceeAuthorityProtocol.readEffect(DataInputStream(ByteArrayInputStream(output.toByteArray()))) {
                    received++
                    descriptor
                }
                assertEquals(effect, result)
                assertEquals(
                    if (effect is SupervisorReplyEffect.AddFdAndReply ||
                    effect is SupervisorReplyEffect.SecureExecAndReply &&
                    effect.target is SupervisorDescriptorResult.Opened
                    ) {
                        1
                    } else {
                        0
                    },
                        sent,
                )
                assertEquals(sent, received)
            }
        }
        assertEquals(1, fixture.closed.count { it == 52921 })
    }

    @Test
    fun `invalid effect headers never consume a descriptor`() {
        val frames = listOf(byteArrayOf(99), byteArrayOf(3, 0, 0, 0, 1), byteArrayOf(1, 0, 0, 0, 0))
        for (frame in frames) {
            assertThrows(IOException::class.java) {
            TraceeAuthorityProtocol.readEffect(DataInputStream(ByteArrayInputStream(frame))) { error("descriptor receipt must follow complete validation") }
        }
        }
        for (path in listOf("a\u0000b", "a".repeat(4096))) {
            assertThrows(IllegalArgumentException::class.java) {
            TraceeAuthorityProtocol.writePath(DataOutputStream(ByteArrayOutputStream()), path)
        }
        }
    }
}
