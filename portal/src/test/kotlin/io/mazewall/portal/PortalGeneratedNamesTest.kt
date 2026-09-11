package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalGeneratedNamesTest {
    private class First {
        interface Service
    }

    private class Second {
        interface Service
    }

    @Test
    fun `nested service binary names retain enclosing identity`() {
        assertEquals(
            "io.mazewall.portal.PortalGeneratedNamesTest_First_ServicePortalStub",
            PortalGeneratedNames.stubBinaryName(First.Service::class.java),
        )
        assertEquals(
            "io.mazewall.portal.PortalGeneratedNamesTest_Second_ServicePortalStub",
            PortalGeneratedNames.stubBinaryName(Second.Service::class.java),
        )
    }
}
