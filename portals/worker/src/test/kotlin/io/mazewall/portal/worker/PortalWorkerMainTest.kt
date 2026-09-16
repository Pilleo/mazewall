package io.mazewall.portal.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalWorkerMainTest {
    @Test
    fun `complete profile path is optional`() {
        assertNull(PortalWorkerMain.completeProfilePath(arrayOf("/tmp/sock")))
        val path = PortalWorkerMain.completeProfilePath(arrayOf("/tmp/sock", "--complete-profile", "/tmp/bob.json"))
        assertEquals("/tmp/bob.json", path.toString())
        assertTrue(
            runCatching { PortalWorkerMain.completeProfilePath(arrayOf("/tmp/sock", "--complete-profile")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
