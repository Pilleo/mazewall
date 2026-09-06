package io.mazewall.testing

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Platform
import io.mazewall.enforcer.diagnostics.MazewallEvents
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GlobalTestStateExtensionTest {
    @Test
    @Order(1)
    fun `test may mutate reversible global seams`() {
        Platform.isCpuCetSupportedOverride = true
        LinuxNative.setEngine(MockNativeEngine())
        MazewallEvents.register { }
        MazewallEvents.failOnListenerError = true
    }

    @Test
    @Order(2)
    fun `next test starts with default reversible global seams`() {
        assertNull(Platform.isCpuCetSupportedOverride)
        assertTrue(LinuxNative.isRealEngineActive())
        assertEquals(0, MazewallEvents.registeredCount())
        assertFalse(MazewallEvents.failOnListenerError)
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun restoreDefaultsIfTheExtensionIsNotRegistered() {
            Platform.isCpuCetSupportedOverride = null
            Platform.resetToDefault()
            LinuxNative.resetToDefault()
            MazewallEvents.clear()
            MazewallEvents.failOnListenerError = false
        }
    }
}
