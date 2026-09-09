package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.testing.awaitOrFail
import io.mazewall.testing.getOrFail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class AsyncSocketRouteExecutorTest {
    @Test
    fun `async native exception produces a denial effect and releases imported ownership`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.onAccept4 = { _, _, _, _ -> throw IllegalStateException("native accept failed") }
        val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
        val effect = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0)).getOrFail("async failure effect")
        assertEquals(SupervisorReplyEffect.Error(1), effect)
        assertEquals(listOf(51005, 51006), fixture.closed)
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `pending accept returns promptly and publishes a heap-owned effect after completion`() {
        val fixture = SupervisorBoundaryFixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            started.countDown()
            release.awaitOrFail("release pending accept")
            SupervisorBoundaryFixture.success(51004)
        }
        val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
        val future = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0))
        try {
            started.awaitOrFail("accept entered native boundary")
            assertFalse(future.isDone)
            assertEquals(0, fixture.replies.size)
        } finally {
            release.countDown()
        }
        val effect = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, future.getOrFail("accept effect"))
        assertEquals(51004, effect.descriptor.handle.value)
        effect.descriptor.close()
        assertEquals(listOf(51005, 51006, 51004), fixture.closed)
    }
}
