package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEMatrixTerminalTest {
    @Test
    fun `failed matrix setup leaves a policy-ineligible terminal and preserves the failure`() {
        val output = Files.createTempDirectory("tier-e-matrix-terminal-")
        try {
            val original = IllegalStateException("missing image")
            val failure = assertThrows(IllegalStateException::class.java) {
                TierEMatrixTerminal.run(output) { throw original }
            }
            assertEquals(original, failure)
            val terminal = Json.parseToJsonElement(Files.readString(output.resolve("terminal.json"))).jsonObject
            assertEquals("false", terminal.getValue("success").toString())
            assertEquals("false", terminal.getValue("policy_eligibility").toString())
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
