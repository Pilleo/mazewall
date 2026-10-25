package io.mazewall.demo.koog

import ai.koog.serialization.typeToken
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class ThreadReadArguments(val path: String)

internal fun threadReadTool(
    runtime: ManagedRuntime,
    domain: InProcessDomain,
): ThreadToolAdapter<ThreadReadArguments, String> = ThreadToolAdapter(
    runtime, domain, typeToken<ThreadReadArguments>(), typeToken<String>(),
    "read_note", "Read at most 64 KiB of strict UTF-8 from the approved directory",
    validateArguments = {
        require(it.path.length <= 4096 && it.path.isNotBlank() && '\u0000' !in it.path && it.path.toByteArray(Charsets.UTF_8).size <= 4096) {
            "Path must be nonblank, NUL-free and at most 4096 UTF-8 bytes"
        }
    },
    validateResult = TextBounds::requireBounded,
    action = { readBoundedUtf8(Path.of(it.path)) },
)

internal fun readBoundedUtf8(path: Path): String {
    val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
    require(bytes.size <= 65536) { "File exceeds 65536 UTF-8 bytes" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
