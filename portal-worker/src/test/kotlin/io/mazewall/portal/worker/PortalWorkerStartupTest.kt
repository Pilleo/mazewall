package io.mazewall.portal.worker

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalWorkerStartupTest {
    @Test
    fun `startup creates restricted worker threads before process containment`() {
        val events = mutableListOf<String>()

        val registered =
            PortalWorkerStartup.prepare(
                installFilesystem = { events += "filesystem" },
                createWorkerThreads = { events += "workers" },
                installProcessContainment = { events += "process" },
                bootstrapDispatchers = {
                    events += "dispatchers"
                    1
                },
            )

        assertEquals(1, registered)
        assertEquals(listOf("filesystem", "workers", "process", "dispatchers"), events)
    }
}
