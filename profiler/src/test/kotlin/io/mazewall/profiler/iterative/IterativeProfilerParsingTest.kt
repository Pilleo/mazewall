package io.mazewall.profiler.iterative

import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException

class IterativeProfilerParsingTest {

    @AfterEach
    fun teardown() {
        IterativeProfiler.resetToDefaults()
    }

    private fun extractViolationPath(t: Throwable): String? {
        val method = IterativeProfiler::class.java.getDeclaredMethod("extractViolationPath", Throwable::class.java)
        method.isAccessible = true
        return method.invoke(IterativeProfiler, t) as String?
    }

    private fun extractViolationPath(t: Throwable, policy: Policy<*, *>): String? {
        val method = IterativeProfiler::class.java.getDeclaredMethod("extractViolationPath", Throwable::class.java, Policy::class.java)
        method.isAccessible = true
        return method.invoke(IterativeProfiler, t, policy) as String?
    }

    @Test
    fun `test extractViolationPath with absolute path containing spaces`() {
        val t = IOException("/var/log/my file.txt (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my file.txt", result)
    }

    @Test
    fun `test extractViolationPath with multiple spaces`() {
        val t = IOException("/var/log/my very long file name with spaces.txt (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my very long file name with spaces.txt", result)
    }

    @Test
    fun `test extractViolationPath with relative path containing spaces`() {
        val relativePath = "build/tmp/custom relative path with spaces.txt"
        val expected = java.nio.file.Paths.get(relativePath).toAbsolutePath().normalize().toString()
        val t = IOException("$relativePath (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals(expected, result)
    }

    @Test
    fun `test extractViolationPath with single quotes around path`() {
        val t = IOException("Cannot open '/var/log/my file.txt' (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my file.txt", result)
    }

    @Test
    fun `test extractViolationPath with double quotes around path`() {
        val t = IOException("Cannot open \"/var/log/my file.txt\" (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my file.txt", result)
    }

    @Test
    fun `test extractViolationPath with a colon and path with spaces`() {
        val t = IOException("An error occurred: /var/log/my file.txt (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my file.txt", result)
    }

    @Test
    fun `test extractViolationPath with preceding path in the message`() {
        val t = IOException("Exception in /app/bin/runner: /var/log/my file.txt (Permission denied)")
        val result = extractViolationPath(t)
        assertEquals("/var/log/my file.txt", result)
    }

    @Test
    fun `test extractViolationPath with policy specific custom phrase`() {
        val policy = Policy.builder()
            .customViolationPhrase("Blocked by sandbox")
            .build()
        val t = IOException("/tmp/custom-file Blocked by sandbox")
        val result = extractViolationPath(t, policy)
        assertEquals("/tmp/custom-file", result)
    }

    @Test
    fun `test extractViolationPath with global registered phrase`() {
        IterativeProfiler.registerPhrase("Access restricted by admin")
        val t = IOException("/tmp/another-file Access restricted by admin")
        val result = extractViolationPath(t)
        assertEquals("/tmp/another-file", result)
    }

    @Test
    fun `test extractViolationPath with policy specific custom regex`() {
        val policy = Policy.builder()
            .customViolationRegex(Regex("""restricted: \d+"""))
            .build()
        val t = IOException("/tmp/regex-file restricted: 999")
        val result = extractViolationPath(t, policy)
        assertEquals("/tmp/regex-file", result)
    }
}
