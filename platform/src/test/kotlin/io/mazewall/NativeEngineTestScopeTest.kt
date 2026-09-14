package io.mazewall

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
