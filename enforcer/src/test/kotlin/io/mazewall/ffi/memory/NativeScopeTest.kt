package io.mazewall.ffi.memory

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
