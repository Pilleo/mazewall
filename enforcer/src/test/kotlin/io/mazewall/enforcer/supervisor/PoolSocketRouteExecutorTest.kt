package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class PoolSocketRouteExecutorTest {
    @Test
    fun `invalid accepted helper result closes descriptor when effect construction fails`() {
        val fixture = SupervisorBoundaryFixture()
        val accepted = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<FileDescriptorRole.Generic>(52600),
                fixture.engine.fileSystem,
        )
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = SocketHelperResult.Accepted(accepted, byteArrayOf(2, 0), 3)

                override fun close() = Unit
            }
        }.use { pool ->
            PoolSocketRouteExecutor(TraceeDescriptorImporter(fixture.engine) { it }, pool.session()).use { routes ->
                assertFailsWith<java.util.concurrent.ExecutionException> {
                    routes.accept(AcceptRequest(Tid(Int.MAX_VALUE), 55, 1, 1, 0)).get(5, TimeUnit.SECONDS)
                }
            }
        }
        assertEquals(listOf(51005, 51006, 52600), fixture.closed)
    }

    @Test
    fun `accept completion defers tracee memory writes until terminal reply authority`() {
        val fixture = SupervisorBoundaryFixture()
        val accepted = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<FileDescriptorRole.Generic>(52600),
                fixture.engine.fileSystem,
        )
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
