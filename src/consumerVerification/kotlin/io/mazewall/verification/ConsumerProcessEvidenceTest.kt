package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class ConsumerProcessEvidenceTest {
    @TempDir
    private lateinit var root: Path

    @Test
    fun `actual nonzero child retains full separate streams and command identity`() {
        val args = listOf("sh", "-c", "awk 'BEGIN { for(i=0;i<20000;i++) printf \"out\"; print \"\" }'; awk 'BEGIN { for(i=0;i<20000;i++) printf \"err\"; print \"\" }' >&2; exit 37")
        val result = ConsumerProcessEvidence.run(args, root, "nonzero")
        assertEquals(37, result.exitCode)
        assertEquals("out".repeat(20000) + "\n", result.stdout)
        assertEquals("err".repeat(20000) + "\n", result.stderr)
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("nonzero.command.json"))).jsonObject
        assertEquals(args, receipt.getValue("argv").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("37", receipt.getValue("exitCode").jsonPrimitive.content)
        assertEquals("false", receipt.getValue("timedOut").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
    }

    @Test
    fun `timeout is persisted before signalling and retiring only acquired ancestry`() {
        val unrelated = ProcessBuilder("sleep", "30").start()
        val args = listOf("sh", "-c", "trap '' TERM; printf 'partial stdout\\n'; printf 'partial stderr\\n' >&2; sleep 30 & wait")
        try {
            val failure = assertThrows<ConsumerCommandTimeout> { ConsumerProcessEvidence.run(args, root, "timeout", Duration.ofSeconds(1)) }
            assertEquals(args, failure.arguments)
            val receipt = Json.parseToJsonElement(Files.readString(root.resolve("timeout.command.json"))).jsonObject
            val transitions = receipt.getValue("transitions").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(transitions.indexOf("timed-out") < transitions.indexOf("reaped"))
            assertEquals("true", receipt.getValue("timedOut").jsonPrimitive.content)
            assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
            assertEquals("partial stdout\n", Files.readString(root.resolve("timeout.stdout.log")))
            assertEquals("partial stderr\n", Files.readString(root.resolve("timeout.stderr.log")))
            assertTrue(unrelated.isAlive, "Unrelated process was signalled")
        } finally {
            unrelated.destroyForcibly()
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `launch failure retains argv cwd and original failure identity`() {
        val args = listOf(root.resolve("no-such-executable").toString())
        assertThrows<java.io.IOException> { ConsumerProcessEvidence.run(args, root, "missing") }
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("missing.command.json"))).jsonObject
        assertEquals("launch-failed", receipt.getValue("state").jsonPrimitive.content)
        assertEquals("null", receipt.getValue("pid").jsonPrimitive.content)
        assertEquals(root.toString(), receipt.getValue("cwd").jsonPrimitive.content)
    }

    @Test
    fun `existing or aliased evidence is preserved and child cannot launch`() {
        for (kind in listOf("file", "symlink", "hardlink")) {
            val directory = Files.createDirectory(root.resolve(kind))
            val operator = Files.writeString(directory.resolve("operator"), "unknown evidence")
            val output = directory.resolve("owned.stdout.log")
            when (kind) {
                "file" -> Files.copy(operator, output)
                "symlink" -> Files.createSymbolicLink(output, operator)
                "hardlink" -> Files.createLink(output, operator)
            }
            assertThrows<java.nio.file.FileAlreadyExistsException> { ConsumerProcessEvidence.run(listOf("touch", "launched"), directory, "owned") }
            assertEquals("unknown evidence", Files.readString(operator))
            assertEquals("unknown evidence", Files.readString(output))
            assertTrue(!Files.exists(directory.resolve("launched")))
        }
    }
}
