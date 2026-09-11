package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

private class CollisionOuter {
    interface Service
}

private interface Nested_14_CollisionOuter_7_Service

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

    @Test
    fun `top level names remain stable and reserve colliding nested artifacts`() {
        val nested = PortalGeneratedNames.stubBinaryName(CollisionOuter.Service::class.java)
        val topLevel = PortalGeneratedNames.stubBinaryName(Nested_14_CollisionOuter_7_Service::class.java)

        assertEquals("io.mazewall.portal.Nested_14_CollisionOuter_7_ServicePortalStub", topLevel)
        assertEquals(topLevel, nested)
        val failure = assertFailsWith<IllegalArgumentException> {
            PortalGeneratedNames.requireUnique(
                listOf(CollisionOuter.Service::class.java, Nested_14_CollisionOuter_7_Service::class.java),
            )
        }
        assertTrue(failure.message!!.contains(topLevel), failure.message)
    }
}
