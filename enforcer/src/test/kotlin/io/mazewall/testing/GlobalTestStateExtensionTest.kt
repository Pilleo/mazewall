package io.mazewall.testing

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.diagnostics.MazewallEvents
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GlobalTestStateExtensionTest {
    @Execution(ExecutionMode.CONCURRENT)
    private class ConcurrentProbeFixture {
        fun concurrentByClass() = Unit

        @Execution(ExecutionMode.SAME_THREAD)
        fun sameThreadOverride() = Unit
    }

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

    @Test
    @Order(3)
    fun `concurrent execution skips global reset while same-thread override still resets`() {
        val extension = ResetGlobalsExtension()
        LinuxNative.setEngine(MockNativeEngine())
        assertFalse(LinuxNative.isRealEngineActive())

        val concurrentMethod = ConcurrentProbeFixture::class.java.getDeclaredMethod("concurrentByClass")
        extension.afterEach(contextFor(ConcurrentProbeFixture::class.java, concurrentMethod))
        assertFalse(LinuxNative.isRealEngineActive())

        val sameThreadMethod = ConcurrentProbeFixture::class.java.getDeclaredMethod("sameThreadOverride")
        extension.afterEach(contextFor(ConcurrentProbeFixture::class.java, sameThreadMethod))
        assertTrue(LinuxNative.isRealEngineActive())
    }

    private fun contextFor(
        testClass: Class<*>,
        testMethod: Method,
    ): ExtensionContext =
        Proxy.newProxyInstance(
            ExtensionContext::class.java.classLoader,
            arrayOf(ExtensionContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTestClass" -> Optional.of(testClass)
                "getTestMethod" -> Optional.of(testMethod)
                else -> throw UnsupportedOperationException("Unexpected ExtensionContext call: ${method.name}")
            }
        } as ExtensionContext

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
