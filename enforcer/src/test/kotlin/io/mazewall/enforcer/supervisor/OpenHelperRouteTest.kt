package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenHelperRouteTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 13, 9])
    fun `failed import preserves errno without submitting a helper or opening a fallback`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.process.onPidfdGetFd = { _, _, _ -> SupervisorBoundaryFixture.failure(errno) }
        fixture.engine.fileSystem.onOpenat2 = { _, _, _, _ -> error("must not open fallback") }
        SocketHelperPool(1, 1) { error("must not start helper") }.use { pool ->
            SupervisorRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }, pool).use { executor ->
                NativeArena.ofConfined().use { arena ->
                    val result = with(arena) { executor.inject(context(fixture, arena)) }.get(5, TimeUnit.SECONDS)
                    assertEquals(SupervisorReplyEffect.Error(errno), result)
                }
            }
        }
        assertEquals(listOf(51005), fixture.closed)
        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun `opened result transfers ownership and requested CLOEXEC only to the reply effect`() {
        val fixture = SupervisorBoundaryFixture()
        val opened = SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(52701), fixture.engine.fileSystem)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ) = SocketHelperResult.Opened(opened)

                override fun close() = Unit
            }
        }.use { pool ->
            SupervisorRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }, pool).use { executor ->
                NativeArena.ofConfined().use { arena ->
                    val result = with(arena) { executor.inject(context(fixture, arena)) }.get(5, TimeUnit.SECONDS)
                    val effect = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, result)
                    assertEquals(524288, effect.flags.value)
                    assertEquals(listOf(51005, 51006), fixture.closed)
                    SupervisorReplyHandoff(effect).use { }
                }
            }
        }
        assertEquals(listOf(51005, 51006, 52701), fixture.closed)
        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun `relative dirfd open yields and cancellation disposes late opened fd once without a reply`() {
        val fixture = SupervisorBoundaryFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.engine.fileSystem.onOpenat2 = { _, _, _, _ -> error("reactor must not execute native open") }
        val opened = SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(52700), fixture.engine.fileSystem)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    val open = assertInstanceOf(SocketOperation.OpenAt2::class.java, operation)
                    assertEquals("target", open.path)
                    assertEquals(524353L, open.flags)
                    assertEquals(416L, open.mode)
                    assertEquals(9L, open.resolve)
                    assertEquals(51006, socket.handle.value)
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Opened(opened)
                }

                override fun close() {
                    release.countDown()
                }
            }
        }.use { pool ->
            SupervisorRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }, pool).use { executor ->
                NativeArena.ofConfined().use { arena ->
                    val future = assertDoesNotThrow<java.util.concurrent.CompletableFuture<SupervisorReplyEffect>> {
                        with(arena) { executor.inject(context(fixture, arena)) }
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertFalse(future.isDone)
                    executor.cancel()
                    executor.close()
                    assertTrue(future.isCompletedExceptionally)
                }
            }
        }
        assertEquals(listOf(51005, 51006, 52700), fixture.closed)
        assertTrue(fixture.replies.isEmpty())
    }

    private fun context(
        fixture: SupervisorBoundaryFixture,
        arena: NativeArena,
    ): SupervisorRouteContext {
        val base = fixture.context(arena, Arch.AMD64.openat2)
        return base.copy(
            request = base.request.copy(header = base.request.header.copy(args = longArrayOf(5, 4096, 8192, 24, 0, 0))),
            extracted = SyscallArguments("target", null, TraceeDirFd(5), OpenHow(OpenFlags(524353), 416, 1)),
        )
    }
}
