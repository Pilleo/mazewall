package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ValidationListenerPreloadTest {
    @Test
    fun `preload initializes every ACK-path class`() {
        ValidationListenerPreload.ensureLoaded()
        val cl = ValidationListenerPreload::class.java.classLoader
        for (name in ValidationListenerPreload.requiredBinaryNames) {
            val loaded = Class.forName(name, false, cl)
            assertTrue(loaded != null, name)
        }
    }

    @Test
    fun `required set includes listener inspector and scoping policy`() {
        val names = ValidationListenerPreload.requiredBinaryNames
        assertTrue(names.any { it.endsWith("JVMValidationListener") })
        assertTrue(names.any { it.endsWith("JvmStackInspector") })
        assertTrue(names.any { it.endsWith("DefaultStacktraceScopingPolicy") })
        assertTrue(names.any { it.contains("JvmVerdict") })
        assertTrue(names.any { it.contains("SupervisedKind") })
    }
}
