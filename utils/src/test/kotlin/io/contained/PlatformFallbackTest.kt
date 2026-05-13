package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith

class PlatformFallbackTest {

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `fails by default on unsupported platform`() {
        // Clear properties to ensure default behavior
        System.clearProperty("io.contained.fallback")
        
        val executor = Executors.newSingleThreadExecutor()
        val wrapped = ContainedExecutors.wrap(executor, Policy.NO_EXEC)
        
        try {
            assertFailsWith<UnsupportedOperationException> {
                wrapped.submit { println("should not run") }.get()
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `respects FAIL property on unsupported platform`() {
        System.setProperty("io.contained.fallback", "FAIL")
        val executor = Executors.newSingleThreadExecutor()
        val wrapped = ContainedExecutors.wrap(executor, Policy.NO_EXEC)
        
        try {
            assertFailsWith<UnsupportedOperationException> {
                wrapped.submit { println("should not run") }.get()
            }
        } finally {
            System.clearProperty("io.contained.fallback")
            executor.shutdown()
        }
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `respects SILENT_BYPASS property on unsupported platform`() {
        System.setProperty("io.contained.fallback", "SILENT_BYPASS")
        val executor = Executors.newSingleThreadExecutor()
        val wrapped = ContainedExecutors.wrap(executor, Policy.NO_EXEC)
        
        try {
            // Should not throw
            wrapped.submit { println("running uncontained") }.get()
        } finally {
            System.clearProperty("io.contained.fallback")
            executor.shutdown()
        }
    }
}
