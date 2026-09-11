package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SocketHelperPrewarmTest {
    @Test
    fun `prewarm owns every child before returning and shutdown closes each once`() {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        SocketHelperPool(3, 1) {
            starts.incrementAndGet()
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    descriptor: SupervisorOwnedFd,
                ): SocketHelperResult = error("prewarm must not perform an operation")

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }.use { pool ->
            pool.prewarm()
            assertEquals(3, starts.get())
            assertEquals(0, closes.get())
        }
        assertEquals(3, closes.get())
    }
}
