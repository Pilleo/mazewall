package io.mazewall

import io.mazewall.testing.withNativeEngine
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeEngineTestScopeTest {
    @Test
    fun `installs the supplied native engine only for the block`() {
        withNativeEngine(MockNativeEngine()) {
            assertFalse(LinuxNative.isRealEngineActive())
        }

        assertTrue(LinuxNative.isRealEngineActive())
    }

    @Test
    fun `restores the real native engine when the block throws`() {
        assertFailsWith<IllegalStateException> {
            withNativeEngine(MockNativeEngine()) {
                throw IllegalStateException("expected test failure")
            }
        }

        assertTrue(LinuxNative.isRealEngineActive())
    }
}
