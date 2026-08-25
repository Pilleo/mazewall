package io.mazewall.orchestrator

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot JSON-RPC ACP client over stdio (newline-delimited JSON).
 * Clarify may read files under the session cwd so the weak investigator can
 * inspect the repo; writes and terminal methods are rejected.
 */
class AcpChatModel(
    private val command: List<String>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val workingDirectory: java.io.File = java.io.File(System.getProperty("java.io.tmpdir")),
    private val processFactory: (List<String>) -> Process = { cmd ->
        ProcessBuilder(cmd).directory(workingDirectory).redirectErrorStream(false).start()
    },
) : ChatModel, AutoCloseable {
    private val lock = Any()
    private var process: Process? = null
    private var session: AcpJsonRpcSession? = null
    private var sessionId: String? = null

    override fun complete(system: String, user: String): String {
        synchronized(lock) {
            val sess = ensureSession()
            val id = sessionId ?: error("ACP session missing sessionId")
            val future = java.util.concurrent.CompletableFuture.supplyAsync {
                sess.prompt(id, "$system\n\n$user")
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                closeQuietly()
                throw IllegalStateException("ACP clarify timed out after ${timeoutMs}ms")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw IllegalStateException(e.cause?.message ?: e.message, e.cause)
            }
        }
    }

    override fun close() {
        synchronized(lock) { closeQuietly() }
    }

    private fun ensureSession(): AcpJsonRpcSession {
        val existing = session
        val proc = process
        if (existing != null && proc != null && proc.isAlive) return existing
        closeQuietly()
        val started = processFactory(command)
        process = started
        val writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8))
        val created = AcpJsonRpcSession(writer, reader, workingDirectory)
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            created.initialize()
            created.newSession()
        }
        val id = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            closeQuietly()
            throw IllegalStateException("ACP clarify timed out after ${timeoutMs}ms")
        } catch (e: java.util.concurrent.ExecutionException) {
            closeQuietly()
            throw IllegalStateException(e.cause?.message ?: e.message, e.cause)
        }
        session = created
        sessionId = id
        return created
    }

    private fun closeQuietly() {
        process?.destroyForcibly()
        process?.waitFor(2, TimeUnit.SECONDS)
        process = null
        session = null
        sessionId = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
    }
}

internal class AcpJsonRpcSession(
    private val writer: BufferedWriter,
    private val reader: BufferedReader,
    private val workingDirectory: java.io.File = java.io.File(System.getProperty("java.io.tmpdir")),
    private val nextId: AtomicInteger = AtomicInteger(1),
) {
    fun initialize() {
        val id = request(
            "initialize",
            """{"protocolVersion":1,"clientInfo":{"name":"mazewall-issue-clarify","version":"0.0.1"},"clientCapabilities":{}}""",
        )
        waitForResult(id)
        notify("initialized", "{}")
    }

    fun newSession(): String {
        val cwd = workingDirectory.canonicalPath
        val id = request("session/new", """{"cwd":${jsonString(cwd)},"mcpServers":[]}""")
        val result = waitForResult(id)
        val sessionId = parseJsonStringField(result, "sessionId")
        require(sessionId.isNotBlank()) { "session/new returned no sessionId: $result" }
        if (result.contains("\"id\":\"plan\"") || result.contains("\"id\": \"plan\"")) {
            try {
                setMode(sessionId, "plan")
            } catch (_: Exception) {
                // Mode is optional; vibe-acp advertises it, other agents may not.
            }
        }
        return sessionId
    }

    fun setMode(sessionId: String, modeId: String) {
        val id = request(
            "session/set_mode",
            """{"sessionId":${jsonString(sessionId)},"modeId":${jsonString(modeId)}}""",
        )
        waitForResult(id)
    }

    fun prompt(sessionId: String, text: String): String {
        val id = request(
            "session/prompt",
            """{"sessionId":${jsonString(sessionId)},"prompt":[{"type":"text","text":${jsonString(text)}}]}""",
        )
        val chunks = StringBuilder()
        val result = waitForResult(id, onNotification = { method, params ->
            if (method == "session/update") {
                extractUpdateText(params)?.let { chunks.append(it) }
            }
        })
        val fromResult = extractStopText(result)
        val combined = (chunks.toString() + fromResult).trim()
        require(combined.isNotBlank()) { "ACP agent returned empty prompt result" }
        return combined
    }

    private fun request(method: String, params: String): Int {
        val id = nextId.getAndIncrement()
        write("""{"jsonrpc":"2.0","id":$id,"method":${jsonString(method)},"params":$params}""")
        return id
    }

    private fun notify(method: String, params: String) {
        write("""{"jsonrpc":"2.0","method":${jsonString(method)},"params":$params}""")
    }

    private fun write(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private fun waitForResult(
        expectedId: Int,
        onNotification: (String, String) -> Unit = { _, _ -> },
    ): String {
        while (true) {
            val line = reader.readLine() ?: error("ACP agent closed stdout before result $expectedId")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Content-Length:", ignoreCase = true)) continue
            if (!trimmed.startsWith("{")) continue
            val method = optionalJsonStringField(trimmed, "method")
            val id = optionalJsonIntField(trimmed, "id")
            if (id != null && method != null &&
                (method.startsWith("fs/") ||
                    method.startsWith("terminal/") ||
                    method == "session/request_permission")
            ) {
                handleClientRequest(id, method, trimmed)
                continue
            }
            if (method != null && id == null) {
                val params = jsonObjectAfterKey(trimmed, "params") ?: "{}"
                onNotification(method, params)
                continue
            }
            if (id == expectedId) {
                if (trimmed.contains("\"error\"")) {
                    error("ACP error: ${trimmed.take(400)}")
                }
                return jsonObjectAfterKey(trimmed, "result") ?: trimmed
            }
        }
    }

    private fun handleClientRequest(id: Int, method: String, raw: String) {
        when (method) {
            "session/request_permission" -> write(
                """{"jsonrpc":"2.0","id":$id,"result":{"outcome":{"outcome":"selected","optionId":"allow-once"}}}""",
            )

            "fs/read_text_file" -> {
                val params = jsonObjectAfterKey(raw, "params") ?: "{}"
                val path = optionalJsonStringField(params, "path")
                val content = readAllowed(path)
                if (content == null) {
                    write(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"read not allowed"}}""",
                    )
                } else {
                    write(
                        """{"jsonrpc":"2.0","id":$id,"result":{"content":${jsonString(content)}}}""",
                    )
                }
            }

            else -> write(
                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"not supported in clarify"}}""",
            )
        }
    }

    private fun readAllowed(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val root = workingDirectory.canonicalFile.toPath()
        val resolved = workingDirectory.toPath().resolve(path).normalize().toFile().canonicalFile
        if (!resolved.toPath().startsWith(root)) return null
        if (!resolved.isFile) return null
        return resolved.readText().take(8000)
    }
}

internal object AcpCommandResolver {
    private val PRESETS = listOf(
        listOf("agy", "--acp"),
        listOf("vibe", "--acp"),
        listOf("hermes", "acp"),
        listOf("claude", "--acp"),
        listOf("grok", "--acp"),
        listOf("copilot", "--acp"),
    )

    fun resolvePair(env: (String) -> String?): Pair<List<String>, List<String>>? {
        val weak = parseCommand(env("ISSUE_CLARIFY_WEAK_ACP"))
            ?: parseCommand(env("ISSUE_CLARIFY_ACP"))
            ?: discover(env)
        val strong = parseCommand(env("ISSUE_CLARIFY_STRONG_ACP"))
            ?: parseCommand(env("ISSUE_CLARIFY_ACP"))
            ?: weak
        if (weak == null || strong == null) return null
        return weak to strong
    }

    fun parseCommand(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun discover(env: (String) -> String?): List<String>? {
        val path = env("PATH") ?: return null
        val dirs = path.split(':')
        for (preset in PRESETS) {
            val binary = preset.first()
            val found = dirs.any { dir ->
                java.io.File(dir, binary).canExecute()
            }
            if (found) return preset
        }
        return null
    }
}

internal fun extractUpdateText(params: String): String? {
    val content = jsonObjectAfterKey(params, "content") ?: params
    return optionalJsonStringField(content, "text")
}

internal fun extractStopText(result: String): String {
    return optionalJsonStringField(result, "text").orEmpty()
}

internal fun optionalJsonStringField(raw: String, key: String): String? {
    return try {
        parseJsonStringField(raw, key).takeIf { it.isNotBlank() }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun optionalJsonIntField(raw: String, key: String): Int? {
    val marker = "\"$key\""
    val at = raw.indexOf(marker)
    if (at < 0) return null
    val colon = raw.indexOf(':', at + marker.length)
    if (colon < 0) return null
    val digits = raw.substring(colon + 1).takeWhile { it.isWhitespace() || it.isDigit() || it == '-' }.trim()
    return digits.toIntOrNull()
}

internal fun jsonObjectAfterKey(raw: String, key: String): String? {
    val marker = "\"$key\""
    val at = raw.indexOf(marker)
    if (at < 0) return null
    val colon = raw.indexOf(':', at + marker.length)
    if (colon < 0) return null
    var i = colon + 1
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length) return null
    if (raw[i] != '{' && raw[i] != '[') return raw.substring(i).trim().trimEnd(',', '}')
    val open = raw[i]
    val close = if (open == '{') '}' else ']'
    var depth = 0
    val start = i
    while (i < raw.length) {
        val c = raw[i]
        if (c == open) depth++
        if (c == close) {
            depth--
            if (depth == 0) return raw.substring(start, i + 1)
        }
        i++
    }
    return null
}
