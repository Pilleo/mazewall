package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalRuntimeClasspathTest {
    @Test
    fun `KotlinPoet is not on the portal runtime classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.squareup.kotlinpoet.FileSpec")
        }
    }

    @Test
    fun `guest PortalBuiltinDispatch is not on the broker classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.mazewall.portal.worker.PortalBuiltinDispatch")
        }
    }

    @Test
    fun `guest PortalWorkerMain is not on the broker classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.mazewall.portal.worker.PortalWorkerMain")
        }
    }
}
