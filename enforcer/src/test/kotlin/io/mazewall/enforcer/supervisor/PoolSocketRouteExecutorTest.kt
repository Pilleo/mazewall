package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class PoolSocketRouteExecutorTest {
    @Test
    fun `accept completion defers tracee memory writes until terminal reply authority`() {
        val fixture = SupervisorBoundaryFixture()
        val accepted = SupervisorOwnedFd(FileDescriptor.replace<FileDescriptorRole.Generic>(52600), fixture.engine.fileSystem)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = SocketHelperResult.Accepted(accepted, byteArrayOf(2, 0), 2)

                override fun close() = Unit
            }
        }.use { pool ->
            PoolSocketRouteExecutor(TraceeDescriptorImporter(fixture.engine) { it }, pool.session()).use { routes ->
                // Deliberately invalid tracee pointers: preparing a heap effect must not dereference them.
                val effect = routes.accept(AcceptRequest(Tid(Int.MAX_VALUE), 55, 1, 1, 0)).get(5, TimeUnit.SECONDS)
                assertInstanceOf(SupervisorReplyEffect.AcceptedSocket::class.java, effect)
                SupervisorReplyHandoff(effect).close()
            }
        }
        assertEquals(listOf(51005, 51006, 52600), fixture.closed)
    }
}
