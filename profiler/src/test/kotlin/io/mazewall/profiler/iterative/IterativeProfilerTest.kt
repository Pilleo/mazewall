package io.mazewall.profiler.iterative

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import io.mazewall.enforcer.ContainmentViolationException
import java.nio.file.AccessDeniedException

class IterativeProfilerTest {

    @Test
    fun `extractViolationPath extracts absolute path`() {
        val ex = AccessDeniedException("/tmp/test.txt")
        val path = IterativeProfiler.extractViolationPath(ContainmentViolationException("Violated", ex))
        assertEquals("/tmp/test.txt", path)
    }

    @Test
    fun `extractViolationPath extracts quoted path`() {
        val ex = AccessDeniedException("/etc/passwd")
        val path = IterativeProfiler.extractViolationPath(ContainmentViolationException("Violated", ex))
        assertEquals("/etc/passwd", path)
    }

    @Test
    fun `extractViolationPath handles empty message`() {
        val ex = RuntimeException()
        val path = IterativeProfiler.extractViolationPath(ex)
        assertEquals(null, path)
    }

    @ParameterizedTest
    @ValueSource(chars = [':', '\'', '"', '(', ')', '[', ']', '{', '}', ',', ';'])
    fun `isRestrictedSeparator returns true for restricted chars`(c: Char) {
        assertTrue(IterativeProfiler.isRestrictedSeparator(c))
    }

    @ParameterizedTest
    @ValueSource(chars = ['a', '1', '/', '-', '_', '.'])
    fun `isRestrictedSeparator returns false for normal chars`(c: Char) {
        assertFalse(IterativeProfiler.isRestrictedSeparator(c))
    }
}
