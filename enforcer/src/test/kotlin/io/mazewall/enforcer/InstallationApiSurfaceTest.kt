package io.mazewall.enforcer

import io.mazewall.enforcer.api.ContainedExecutors
import kotlin.test.Test
import kotlin.test.assertTrue

class InstallationApiSurfaceTest {
    @Test
    fun `installation facade exposes no void install overloads`() {
        val voidInstalls =
            ContainedExecutors::class.java.declaredMethods
                .filter { method -> method.name.startsWith("install") && method.returnType == Void.TYPE }

        assertTrue(voidInstalls.isEmpty(), "legacy Unit installs remain: $voidInstalls")
    }
}
