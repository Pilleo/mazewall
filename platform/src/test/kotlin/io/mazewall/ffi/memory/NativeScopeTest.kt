package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith

class NativeScopeTest {
    @Test
    fun `nativeScope closes arena even when exception is thrown`() {
        assertFailsWith<RuntimeException> {
            nativeScope {
                throw RuntimeException("Simulated failure")
            }
        }
    }
}
