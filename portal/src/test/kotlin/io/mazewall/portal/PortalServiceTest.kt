package io.mazewall.portal

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalServiceTest {
    private class TestClient : PortalClient, AutoCloseable {
        var closes: Int = 0

        override fun invoke(
            methodId: Int,
            payload: ByteArray,
            vararg granted: Capability.ReadFd,
        ): ByteArray = ByteArray(0)

        override fun close() {
            closes++
        }
    }

    @Test
    fun `worker configuration rejects nonpositive concurrency`() {
        assertFailsWith<IllegalArgumentException> {
            PortalWorkerConfig(
                classpath = listOf(Path.of("worker.jar")),
                implementationClassName = "example.Worker",
                concurrency = 0,
                callTimeout = Duration.ofSeconds(1),
                startupTimeout = Duration.ofSeconds(1),
            )
        }
    }

    @Test
    fun `service close closes its worker client once`() {
        val client = TestClient()
        val service = PortalService("api", client)

        service.close()
        service.close()

        assertEquals(1, client.closes)
    }
}
