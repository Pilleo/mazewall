package io.mazewall.portal

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparedPortalOperationVisibilityTest {
    @Test
    fun `operation has only a genuine package private JVM constructor and final public submit facade`() {
        val type = PreparedPortalOperation::class.java
        assertTrue(Modifier.isPublic(type.modifiers))
        assertTrue(Modifier.isFinal(type.modifiers))
        assertEquals(0, type.constructors.size)
        val constructor = type.declaredConstructors.single()
        assertFalse(Modifier.isPublic(constructor.modifiers))
        assertFalse(Modifier.isProtected(constructor.modifiers))
        assertFalse(Modifier.isPrivate(constructor.modifiers))
        assertTrue(Modifier.isPublic(type.getMethod("submit", Any::class.java).modifiers))
    }
}
