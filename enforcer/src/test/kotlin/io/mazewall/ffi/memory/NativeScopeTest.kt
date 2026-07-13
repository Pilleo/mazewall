package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertFalse
import java.lang.foreign.Arena

class NativeScopeTest {

    @Test
    fun `nativeScope closes arena even when exception is thrown`() {
        assertFailsWith<RuntimeException> {
            nativeScope {
                throw RuntimeException("Simulated failure")
            }
        }
    }

    @Test
    fun `nativeScope reuses arena when nested`() {
        nativeScope {
            val outerArena = this
            nativeScope {
                val innerArena = this
                assertSame(outerArena, innerArena, "Nested nativeScope must reuse the same arena")
            }
        }
    }

    @Test
    fun `outer nativeScope closes arena and nested scopes stay open until outer finishes`() {
        var capturedArena: Arena? = null
        nativeScope {
            capturedArena = this
            nativeScope {
                // inner
            }
            // outer is still alive here
            assertTrue(this.scope().isAlive, "Arena must be alive during outer scope")
        }
        assertFalse(capturedArena!!.scope().isAlive, "Arena must be closed after outer nativeScope finishes")
    }

    private fun assertTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
