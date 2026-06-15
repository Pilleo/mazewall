package io.mazewall

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class SbobParserCoverageTest {
    @Test
    fun `test escape sequences in JSON`() {
        val json = """{"opens": ["/b/f/n/r/t/\"xyz\""]}"""
        val policy = SbobParser.parseJsonToPolicy(json)
        assertNotNull(policy)
    }

    @Test
    fun `test blacklist base policy in parser`() {
        // Create a base policy with ACT_ALLOW (blacklist)
        val blacklistBase = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.EXECVE)
            .build()

        val json = """{"syscalls": ["execve", "unknown_syscall"]}"""
        val policy = SbobParser.parseJsonToPolicy(json, blacklistBase)

        // In blacklist mode, 'execve' found in SBoB should be unblocked (removed from blacklist)
        assertFalse(policy.syscallActions.containsKey(Syscall.EXECVE))
    }

    @Test
    fun `test parse from InputStream`() {
        val json = """{"opens": ["/etc"]}"""
        val stream = ByteArrayInputStream(json.toByteArray())
        val policy = SbobParser.parseToPolicy(stream)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc" })
    }

    @Test
    fun `test malformed JSON handling`() {
        // Unclosed string
        assertFails { SbobParser.parseJsonToPolicy("""{"key": "val""") }
        // Unclosed object
        assertFails { SbobParser.parseJsonToPolicy("""{"key": ["val"]""") }
        // Missing colon
        assertFails { SbobParser.parseJsonToPolicy("""{"key" ["val"]}""") }
        // Random junk at end
        assertFails { SbobParser.parseJsonToPolicy("""{"key": "val"} junk""") }
        // Unexpected delimiter
        assertFails { SbobParser.parseJsonToPolicy("""{"key": "val", , "key2": "val2"}""") }
    }

    @Test
    fun `test pruneSubpaths logic`() {
        val json = """
            {
                "opens": ["/etc", "/etc/passwd", "/var/log", "/var/log/syslog", "/tmp/../tmp/foo"]
            }
        """.trimIndent()
        val policy = SbobParser.parseJsonToPolicy(json)
        // /etc/passwd should be pruned by /etc
        // /var/log/syslog should be pruned by /var/log
        // /tmp/../tmp/foo should be normalized to /tmp/foo
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc" })
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/var/log" })
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/foo" })
        assertFalse(policy.allowedFsReadPaths.any { it.value == "/etc/passwd" })
    }
}
