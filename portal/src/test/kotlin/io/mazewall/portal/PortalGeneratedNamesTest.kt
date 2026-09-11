package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        val first = PortalGeneratedNames.stubBinaryName(First.Service::class.java)
        val second = PortalGeneratedNames.stubBinaryName(Second.Service::class.java)
        assertNotEquals(first, second)
        assertTrue(first.contains("Nested_"))
        assertTrue(first.endsWith("PortalStub"))
    }
}
