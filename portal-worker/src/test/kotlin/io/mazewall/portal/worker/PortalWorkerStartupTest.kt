package io.mazewall.portal.worker

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalWorkerStartupTest {
    @Test
    fun `startup installs filesystem before process containment and dispatch`() {
        val events = mutableListOf<String>()

        val registered =
            PortalWorkerStartup.prepare(
                installFilesystem = { events += "filesystem" },
                installProcessContainment = { events += "process" },
                bootstrapDispatchers = {
                    events += "dispatchers"
                    1
                },
            )

        assertEquals(1, registered)
        assertEquals(listOf("filesystem", "process", "dispatchers"), events)
    }
}
