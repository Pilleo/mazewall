package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class PortalWorkerStartupTest {
    @Test
    fun `required parent setup precedes containment and every guest initialization`() {
        val events = mutableListOf<String>()
        PortalWorkerMain.runWorker(
            transport(events),
            authorizeParent = { events += "parent" },
            installContainment = { events += "containment" },
            createDispatcher = {
                events += "guest"
                PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
            },
            publishReady = { events += "ready" },
        )
        assertEquals(listOf("parent", "containment", "guest", "ready", "receive", "close"), events)
    }

    @Test
    fun `failed parent setup retires transport without containment guest or ready`() {
        val events = mutableListOf<String>()
        val rejected = IllegalStateException("parent authorization rejected")
        assertSame(
            rejected,
            assertThrows(IllegalStateException::class.java) {
            PortalWorkerMain.runWorker(
                transport(events),
                authorizeParent = { throw rejected },
                installContainment = { error("must not install") },
                createDispatcher = { error("must not construct") },
                publishReady = { error("must not publish") },
            )
        },
        )
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `readiness follows containment and guest construction before requests`() {
        val events = mutableListOf<String>()
        val transport = transport(events)
        PortalWorkerMain.runWorker(
            transport,
            installContainment = { events += "containment" },
            createDispatcher = {
                events += "guest"
                PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
            },
            publishReady = { events += "ready" },
        )
        assertEquals(listOf("containment", "guest", "ready", "receive", "close"), events)
    }

    @Test
    fun `each startup failure closes transport suppresses cleanup and never serves`() {
        for (failedStage in listOf("containment", "guest", "ready")) {
            val events = mutableListOf<String>()
            val primary = AssertionError("failed $failedStage")
            val cleanup = IOException("failed close")
            val step: (String) -> Unit = { stage ->
                events += stage
                if (stage == failedStage) throw primary
            }
            assertSame(
                primary,
                assertThrows(AssertionError::class.java) {
                PortalWorkerMain.runWorker(
                    transport(events, cleanup),
                    installContainment = { step("containment") },
                    createDispatcher = {
                        step("guest")
                        PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
                    },
                    publishReady = { step("ready") },
                )
            },
            )
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            assertEquals(1, events.count { it == "close" })
            assertFalse("receive" in events)
            if (failedStage != "ready") assertFalse("ready" in events)
        }
    }

    private fun transport(
        events: MutableList<String>,
        failure: IOException? = null,
    ) = object : PortalWorkerTransport {
        override fun receive(): PortalWorkerReceive {
            events += "receive"
            return PortalWorkerReceive.PeerClosed
        }

        override fun send(frame: PortalFrame) = error("no request")

        override fun close() {
            events += "close"
            failure?.let { throw it }
        }
    }
}
