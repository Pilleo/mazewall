package io.mazewall

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SbobUnicodeTest {

    @Test
    fun `should parse unicode escape sequences in paths`() {
        // Skip if the system file encoding cannot handle non-ASCII characters
        try {
            java.nio.file.Paths.get("/opt/caf\u00e9")
        } catch (e: java.nio.file.InvalidPathException) {
            assumeTrue(false, "System filesystem encoding does not support non-ASCII characters")
        }

        // /opt/café in JSON with unicode escape
        val json = """
            {
                "opens": ["/opt/caf\u00e9"],
                "fsWritePaths": [],
                "syscalls": []
            }
        """.trimIndent()

        val policy = SbobParser.parseJsonToPolicy(json)
        val allowedPaths = policy.allowedFsReadPaths.map { it.value }
        
        assertTrue(allowedPaths.any { it.endsWith("/opt/café") }, "Path should contain café, but was: ${allowedPaths}")
    }
}
