package io.mazewall.orchestrator

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface ChatModel {
    fun complete(system: String, user: String): String
}

class XaiChatModel(
    private val apiKey: String,
    private val model: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    private val endpoint: String = System.getenv("XAI_API_URL") ?: "https://api.x.ai/v1/chat/completions",
) : ChatModel {
    override fun complete(system: String, user: String): String {
        val body = """
            {"model":"$model","messages":[
              {"role":"system","content":${jsonString(system)}},
              {"role":"user","content":${jsonString(user)}}
            ],"temperature":0}
        """.trimIndent()
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "LLM HTTP ${response.statusCode()}: ${response.body().take(400)}"
        }
        return extractAssistantContent(response.body())
    }
}

object IssueClarifier {
    fun clarify(
        request: IssueScaffoldRequest,
        files: List<String>,
        repoRoot: File,
        weak: ChatModel,
        strong: ChatModel,
        maxExcerptChars: Int = 4000,
    ): IssueScaffoldRequest {
        val excerpts = files.take(6).joinToString("\n\n") { path ->
            val file = File(repoRoot, path)
            val body = if (file.isFile) file.readText().take(maxExcerptChars) else "(missing)"
            "### $path\n$body"
        }
        val weakUser = """
            Title: ${request.title}
            Symbols: ${request.symbols.joinToString(", ").ifBlank { "(none)" }}
            Files: ${files.joinToString(", ")}

            Excerpts:
            $excerpts

            Return JSON only: {"questions":["..."]}
            Ask the smallest set of questions that would otherwise leave this backlog item blocked.
            If the excerpts already determine the work, return {"questions":[]}.
        """.trimIndent()
        val weakRaw = weak.complete(
            "You are a mazewall backlog triager. Output JSON only.",
            weakUser,
        )
        val questions = parseStringList(weakRaw, "questions")
        if (questions.isEmpty()) {
            return request.copy(openQuestionItems = emptyList())
        }
        val strongUser = """
            Title: ${request.title}
            Questions:
            ${questions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")}

            Excerpts:
            $excerpts

            Answer every question using the excerpts. Then write the backlog Context and Needed sections.
            Needed must be numbered, testable steps. Do not leave questions open.
            Return JSON only:
            {"context":"...","needed":"..."}
        """.trimIndent()
        val strongRaw = strong.complete(
            "You are a mazewall staff engineer. Output JSON only. Fail closed: never suggest silent EPERM bypasses.",
            strongUser,
        )
        val context = parseJsonStringField(strongRaw, "context")
        val needed = parseJsonStringField(strongRaw, "needed")
        require(context.isNotBlank() && needed.isNotBlank()) {
            "strong model did not return context and needed JSON"
        }
        return request.copy(
            openQuestionItems = emptyList(),
            contextBody = context,
            neededBody = needed,
        )
    }
}

internal fun jsonString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

internal fun extractAssistantContent(body: String): String {
    val marker = "\"content\":"
    val start = body.indexOf(marker)
    require(start >= 0) { "LLM response missing content field" }
    var i = start + marker.length
    while (i < body.length && body[i].isWhitespace()) i++
    require(i < body.length && body[i] == '"') { "LLM content was not a string" }
    i++
    val out = StringBuilder()
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            val n = body[i + 1]
            out.append(
                when (n) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    else -> n
                },
            )
            i += 2
            continue
        }
        if (c == '"') break
        out.append(c)
        i++
    }
    return out.toString()
}

internal fun parseJsonObject(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "expected JSON object in model output" }
    return raw.substring(start, end + 1)
}

internal fun parseStringList(raw: String, key: String): List<String> {
    val obj = parseJsonObject(raw)
    val keyMarker = "\"$key\""
    val keyAt = obj.indexOf(keyMarker)
    if (keyAt < 0) return emptyList()
    val bracket = obj.indexOf('[', keyAt)
    val close = obj.indexOf(']', bracket)
    if (bracket < 0 || close < 0) return emptyList()
    val inner = obj.substring(bracket + 1, close)
    return Regex("\"((?:\\\\.|[^\"])*)\"")
        .findAll(inner)
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n") }
        .filter { it.isNotBlank() }
        .toList()
}

internal fun parseJsonStringField(raw: String, key: String): String {
    val obj = parseJsonObject(raw)
    val keyMarker = "\"$key\""
    val keyAt = obj.indexOf(keyMarker)
    require(keyAt >= 0) { "JSON missing $key" }
    val colon = obj.indexOf(':', keyAt + keyMarker.length)
    var i = colon + 1
    while (i < obj.length && obj[i].isWhitespace()) i++
    require(i < obj.length && obj[i] == '"') { "$key was not a string" }
    i++
    val out = StringBuilder()
    while (i < obj.length) {
        val c = obj[i]
        if (c == '\\' && i + 1 < obj.length) {
            val n = obj[i + 1]
            out.append(
                when (n) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    else -> n
                },
            )
            i += 2
            continue
        }
        if (c == '"') break
        out.append(c)
        i++
    }
    return out.toString().trim()
}
