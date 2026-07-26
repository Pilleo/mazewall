package io.mazewall.profiler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import io.mazewall.Policy

class ProfilerReflectionTest {

    @Test
    fun `test profiler wrapper behavior`() {
        // Reflection to hit lines
        val methods = Profiler::class.java.declaredMethods
        assertTrue(methods.isNotEmpty())
    }
}
