package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NeedsFreshJvmTest {
    @Test
    fun `tag matches the Gradle includeTags filter`() {
        assertEquals("needs-fresh-jvm", NeedsFreshJvm.TAG)
    }
}
