package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Durable whole-run incompleteness, including failure before the first arm. */
public object TierEMatrixTerminal {
    public fun run(
        output: Path,
        execute: () -> Unit,
    ) {
        fun write(
            success: Boolean,
            reason: String,
        ) {
            val receipt = Files.createTempFile(output, ".terminal-", ".json")
            try {
                Files.writeString(
                    receipt,
                    buildJsonObject {
                    put("schema_version", 1)
                    put("success", success)
                    put("reason", reason)
                    put("policy_eligibility", false)
                }.toString() +
                    "\n",
                )
                Files.move(receipt, output.resolve("terminal.json"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(receipt)
            }
        }
        write(false, "INCOMPLETE")
        execute()
        write(true, "COMPLETE")
    }
}
