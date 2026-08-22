package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalRuntimeClasspathTest {
    @Test
    fun `KotlinPoet is not on the portal runtime classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.squareup.kotlinpoet.FileSpec")
        }
    }
}
