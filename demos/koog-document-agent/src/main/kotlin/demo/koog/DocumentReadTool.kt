package demo.koog

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
data class ReadDocumentArguments(val path: String)

internal fun validateReadArguments(arguments: ReadDocumentArguments) {
    require(arguments.path.length <= 4096 && Charsets.UTF_8.newEncoder().canEncode(arguments.path)) { "Document path must be bounded valid Unicode" }
    require(arguments.path.isNotBlank() && '\u0000' !in arguments.path) { "A nonblank document path without NUL is required" }
    require(arguments.path.toByteArray(Charsets.UTF_8).size <= 4096) { "Document path exceeds 4096 UTF-8 bytes" }
}

internal fun readBoundedDocument(path: Path): String {
    val bytes = Files.newInputStream(path).use { it.readNBytes(65_537) }
    require(bytes.size <= 65_536) { "Document exceeds 64 KiB" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun documentReadTool(runtime: ManagedRuntime, domain: InProcessDomain, documents: Path): ThreadToolAdapter<ReadDocumentArguments, String> =
    ThreadToolAdapter(runtime, domain, typeToken<ReadDocumentArguments>(), typeToken<String>(), "read_document", "Read one bounded UTF-8 document",
        ::validateReadArguments, { require(it.toByteArray(Charsets.UTF_8).size <= 65_536) },
        { readBoundedDocument(documents.resolve(it.path)) })
