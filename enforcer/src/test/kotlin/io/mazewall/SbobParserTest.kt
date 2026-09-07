package io.mazewall

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SbobParserTest {
    @Test
    fun `test parsing simple sbob json`() {
        val json =
            """
            {
              "opens": ["/etc/hostname", "/tmp/foo/bar"],
              "fsWritePaths": ["/tmp/write.txt"],
              "syscalls": ["OPEN", "WRITE", "CONNECT"],
              "execs": []
            }
            """.trimIndent()

        val base = Policy.PURE_COMPUTE_UNSAFE // blocks OPEN, WRITE, CONNECT
        val policy = SbobParser.parseJsonToPolicy(json, base)

        assertEquals(SeccompAction.ACT_ALLOW, policy.defaultAction)
        // Verify syscalls are unblocked
        assertTrue(policy.isSyscallAllowed(Syscall.OPEN))
        assertTrue(policy.isSyscallAllowed(Syscall.WRITE))
        assertTrue(policy.isSyscallAllowed(Syscall.CONNECT))

        // Verify paths are added
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc/hostname" })
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/foo/bar" })
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/write.txt" })
    }

    @Test
    fun `test parsing from file`(
        @TempDir tempDir: Path,
    ) {
        val json =
            """
            {
              "opens": ["/etc/hosts"],
              "fsWritePaths": [],
              "syscalls": ["OPENAT"],
              "execs": []
            }
            """.trimIndent()
        val file = tempDir.resolve("sbob.json")
        Files.writeString(file, json)

        val policy = SbobParser.parseToPolicy(file, Policy.PURE_COMPUTE_UNSAFE)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc/hosts" })
        assertTrue(policy.isSyscallAllowed(Syscall.OPENAT))
    }

    @Test
    fun `test parsing from stream`() {
        val json = "{\"opens\": [\"/etc/hosts\"], \"syscalls\": [\"OPEN\"]}"
        val stream = ByteArrayInputStream(json.toByteArray())
        val policy = SbobParser.parseToPolicy(stream, Policy.PURE_COMPUTE_UNSAFE)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc/hosts" })
        assertTrue(policy.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `test parsing with allow list base`() {
        val json = "{\"syscalls\": [\"OPEN\", \"READ\"]}"
        val base =
            Policy
                .builder()
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.WRITE)
                .build()
        val policy = SbobParser.parseJsonToPolicy(json, base)

        assertEquals(SeccompAction.ACT_ERRNO(), policy.defaultAction)
        assertTrue(policy.isSyscallAllowed(Syscall.OPEN))
        assertTrue(policy.isSyscallAllowed(Syscall.READ))
        assertTrue(policy.isSyscallAllowed(Syscall.WRITE))
    }

    @Test
    fun `test escaped json strings`() {
        val json = "{\"opens\": [\"/tmp/space\\\\path\", \"/tmp/quote\\\"path\"]}"
        val policy = SbobParser.parseJsonToPolicy(json)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/space\\path" })
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/quote\"path" })
    }

    @Test
    fun `test subpath pruning`() {
        val json =
            """
            {
              "opens": ["/tmp", "/tmp/foo", "/var/log", "/var/log/app.log"],
              "fsWritePaths": [],
              "syscalls": [],
              "execs": []
            }
            """.trimIndent()

        val policy = SbobParser.parseJsonToPolicy(json)

        // Should prune /tmp/foo because /tmp covers it, and /var/log/app.log because /var/log covers it
        val readPaths = policy.allowedFsReadPaths.map { it.value }.toSet()
        assertEquals(setOf("/tmp", "/var/log"), readPaths)
    }

    @Test
    fun `test path pruning does not over-match siblings`() {
        // BACKLOG FIX VERIFICATION: /etc/hosts should NOT match /etc/hostname as a parent
        val json =
            """
            {
              "opens": ["/etc/hosts", "/etc/hostname"],
              "fsWritePaths": [],
              "syscalls": [],
              "execs": []
            }
            """.trimIndent()

        val policy = SbobParser.parseJsonToPolicy(json)
        val readPaths = policy.allowedFsReadPaths.map { it.value }.toSet()
        assertEquals(setOf("/etc/hosts", "/etc/hostname"), readPaths)
    }

    @Test
    fun `test parsing empty sbob`() {
        val json = "{}"
        val policy = SbobParser.parseJsonToPolicy(json)
        assertTrue(policy.allowedFsReadPaths.isEmpty())
        assertTrue(policy.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `test invalid syscall name ignored`() {
        val json = "{\"syscalls\": [\"INVALID_SYSCALL\", \"OPEN\"]}"
        val policy = SbobParser.parseJsonToPolicy(json, Policy.PURE_COMPUTE_UNSAFE)
        assertTrue(policy.isSyscallAllowed(Syscall.OPEN))
    }

    @Test
    fun `SBoB removes an observed syscall from an allow-by-default blacklist`() {
        val blacklist = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.EXECVE)
            .build()

        val policy = SbobParser.parseJsonToPolicy("""{"syscalls": ["execve", "unknown_syscall"]}""", blacklist)

        assertFalse(policy.syscallActions.containsKey(Syscall.EXECVE))
    }

    @ParameterizedTest(name = "malformed SBoB variant {index} is rejected")
    @ValueSource(
        strings = [
            """{"key": "val""",
            """{"key": ["val"]""",
            """{"key" ["val"]}""",
            """{"key": "val"} junk""",
            """{"key": "val", , "key2": "val2"}""",
        ],
    )
    fun `malformed SBoB JSON is rejected`(json: String) {
        assertFails { SbobParser.parseJsonToPolicy(json) }
    }

    @Test
    fun `relative SBoB paths require an explicit working directory and resolve beneath it`() {
        assertFailsWith<IllegalArgumentException> {
            SbobParser.parseJsonToPolicy("""{"opens": ["config/settings.json"]}""")
        }

        val policy = SbobParser.parseJsonToPolicy(
            """{"opens": ["config/settings.json"]}""",
            baseCwd = Path.of("/opt/app"),
        )

        assertTrue(policy.allowedFsReadPaths.any { it.value == "/opt/app/config/settings.json" })
    }

    @Test
    fun `SBoB normalizes paths before pruning covered descendants`() {
        val policy = SbobParser.parseJsonToPolicy(
            """{"opens": ["/etc", "/etc/passwd", "/var/log", "/var/log/syslog", "/tmp/../tmp/foo"]}""",
        )

        assertEquals(setOf("/etc", "/var/log", "/tmp/foo"), policy.allowedFsReadPaths.map { it.value }.toSet())
    }

    @Test
    fun `test complex json structures`() {
        val json = """
            {
                "metadata": {
                    "nested_object": { "key": "value" },
                    "nested_array": [ [ "a", "b" ], [ "c" ] ],
                    "boolean_val": true,
                    "null_val": null,
                    "int_val": 12345
                },
                "opens": ["/tmp/app_data_[1]/cache"],
                "fsWritePaths": [
                    "/tmp/\"escaped\"", "/tmp/\\slash", "/tmp/\n\r\t\b\f\/foo"
                ]
            }
        """.trimIndent()

        val policy = SbobParser.parseJsonToPolicy(json)
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/app_data_[1]/cache" })
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/\"escaped\"" })
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/\\slash" })
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/\n\r\t\b\u000C/foo" })
    }

    @Test
    fun `test that SbobParser contains Landlock symlink swapping warning KDoc`() {
        var rootDir = java.io.File(".").absoluteFile
        while (rootDir.parentFile != null && !java.io.File(rootDir, "enforcer").exists()) {
            rootDir = rootDir.parentFile
        }

        val sbobParserFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/SbobParser.kt")
        assertTrue(sbobParserFile.exists(), "SbobParser.kt should be found at ${sbobParserFile.absolutePath}")

        val content = sbobParserFile.readText()
        assertTrue(content.contains("symlink swapping"), "SbobParser.kt should document symlink swapping incompatibility")
        assertTrue(content.contains("Capistrano"), "SbobParser.kt should document Capistrano-style deployments")
        assertTrue(content.contains("umbrella directory"), "SbobParser.kt should recommend profiling the parent umbrella directory")
    }
}
