package io.mazewall

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class SbobParserCoverageTest {
    @Test
    fun `test escape sequences in JSON tokenizer`() {
        val json = """{"key": ["\b\f\n\r\t\/\\\" \"xyz\""]}"""
        val policy = SbobParser.parseJsonToPolicy(json)
        // We just want to ensure it parses without error and covers the branches
        assertNotNull(policy)
    }

    @Test
    fun `test skip value logic in JSON tokenizer`() {
        // Test skipping nested structures and primitives in SBoB
        val json = """
            {
                "ignored_obj": {"a": 1, "b": [1,2]},
                "ignored_arr": [[1], 2, "str"],
                "ignored_prim": true,
                "ignored_null": null,
                "opens": ["/tmp"]
            }
        """.trimIndent()
        val policy = SbobParser.parseJsonToPolicy(json)
        assertTrue(policy.allowedFsReadPaths.contains("/tmp"))
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
        assertTrue(policy.allowedFsReadPaths.contains("/etc"))
    }

    @Test
    fun `test malformed JSON handling`() {
        // Unclosed string
        assertNotNull(SbobParser.parseJsonToPolicy("""{"key": "val"""))
        // Unclosed object
        assertNotNull(SbobParser.parseJsonToPolicy("""{"key": ["val"]"""))
        // Missing colon
        assertNotNull(SbobParser.parseJsonToPolicy("""{"key" ["val"]}"""))
    }
}
