package io.mazewall.profiler.tools

import io.mazewall.enforcer.api.OperationPolicyDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Development-only operation-document comparison; never produces review authority. */
public object PolicyDiff {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: PolicyDiff <old-operation-document.json> <new-operation-document.json>" }
        println(compare(read(Path.of(args[0])), read(Path.of(args[1]))))
    }

    public fun read(path: Path): OperationPolicyDocument {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        require(bytes.size <= MAX_INPUT_BYTES) { "Operation document exceeds 2 MiB" }
        val text = Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return OperationPolicyDocument.fromJson(text.toString())
    }

    public fun compare(
        old: OperationPolicyDocument,
        new: OperationPolicyDocument,
    ): String {
        val before = fields(Json.parseToJsonElement(old.toJson()))
        val after = fields(Json.parseToJsonElement(new.toJson()))
        val changed = (before.keys + after.keys).sorted().filter { before[it] != after[it] }
        return buildString {
            appendLine("Informational only: not approval or a universal safety ordering; deployment must be reviewed separately.")
            if (changed.isEmpty()) appendLine("NO_DOCUMENT_CHANGES (canonical bytes only; not deployment equivalence)")
            changed.forEach { field -> appendLine("$field: ${before[field] ?: "<absent>"} -> ${after[field] ?: "<absent>"}") }
        }
    }

    private fun fields(
        element: JsonElement,
        prefix: String = "",
    ): Map<String, JsonElement> =
        if (element is JsonObject) {
            element.entries.flatMap { (key, value) -> fields(value, if (prefix.isEmpty()) key else "$prefix.$key").entries }.associate { it.toPair() }
        } else {
            mapOf(prefix to element)
        }

    private const val MAX_INPUT_BYTES = 2 * 1024 * 1024
}
