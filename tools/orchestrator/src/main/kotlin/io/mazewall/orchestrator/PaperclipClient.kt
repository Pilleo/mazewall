package io.mazewall.orchestrator

import java.net.URI
import java.net.http.HttpRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Board-side state we rely on, verified against the live Paperclip API (2026-08-24):
 * - `metadata` is dropped by the server on POST/PATCH; markdown linkage rides in the
 *   description marker `<!-- mazewall:backlog-file=… -->` written by the ingest script.
 * - Assignment alone does not dispatch; a `status -> in_progress` transition does.
 * - Dependencies surface as `blockedBy[]` entries with their own lifecycle status.
 */
@Serializable
data class PaperclipBlocker(
    val id: String,
    val status: String,
    val identifier: String? = null,
)

@Serializable
data class PaperclipIssue(
    val id: String,
    val identifier: String? = null,
    val title: String? = null,
    val status: String,
    val priority: String = "low",
    val issueNumber: Int? = null,
    val assigneeAgentId: String? = null,
    val description: String? = null,
    val blockedBy: List<PaperclipBlocker> = emptyList(),
) {
    /** Marker written by paperclip_backlog_sync.kts; presence proves markdown provenance. */
    val fromMarkdownBacklog: Boolean
        get() = description?.contains(BACKLOG_MARKER) == true

    companion object {
        const val BACKLOG_MARKER = "mazewall:backlog-file="
    }
}

@Serializable
data class PaperclipAgent(
    val id: String,
    val name: String? = null,
    val adapterType: String,
    val urlKey: String? = null,
)

/** Shape posted by the jules adapter's moveIssueToReview(); PR discovery rides on this. */
@Serializable
data class PaperclipWorkProduct(
    val type: String? = null,
    val provider: String? = null,
    val url: String? = null,
    @SerialName("externalId") val externalId: String? = null,
    val status: String? = null,
)

@Serializable
data class PaperclipComment(
    val id: String? = null,
    val body: String? = null,
)

@Serializable
private data class CompanyRef(val id: String)

@Serializable
private data class AssignRequest(@SerialName("assigneeAgentId") val agentId: String)

@Serializable
private data class StatusRequest(val status: String)

@Serializable
private data class CommentRequest(val body: String)

class PaperclipException(message: String, val statusCode: Int) : RuntimeException(message)

/**
 * Thin HTTP client for the Paperclip control plane. All calls are synchronous and
 * bounded; callers decide retry/sleep policy (orchestrator convention: never crash
 * the loop on external failures).
 */
class PaperclipClient(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val baseUrl: String = "http://127.0.0.1:3100",
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun resolveCompanyId(): String {
        val companies = json.decodeFromString<List<CompanyRef>>(get("/api/companies"))
        return companies.firstOrNull()?.id
            ?: throw PaperclipException("No Paperclip company found", 200)
    }

    fun listAgents(companyId: String): List<PaperclipAgent> =
        json.decodeFromString(get("/api/companies/$companyId/agents"))

    fun listIssues(companyId: String): List<PaperclipIssue> =
        json.decodeFromString(get("/api/companies/$companyId/issues"))

    fun listWorkProducts(issueId: String): List<PaperclipWorkProduct> =
        json.decodeFromString(get("/api/issues/$issueId/work-products"))

    fun listComments(issueId: String): List<PaperclipComment> =
        json.decodeFromString(get("/api/issues/$issueId/comments"))

    fun comment(issueId: String, body: String) =
        post("/api/issues/$issueId/comments", CommentRequest(body))

    fun assignAgent(issueId: String, agentId: String) =
        patch("/api/issues/$issueId", AssignRequest(agentId))

    fun startProgress(issueId: String) =
        patch("/api/issues/$issueId", StatusRequest("in_progress"))

    private fun get(path: String): String = exchange(
        HttpRequest.newBuilder().uri(URI.create("$baseUrl$path")).GET().build(),
    )

    private inline fun <reified T> post(path: String, payload: T) = sendWithBody(path, "POST", payload)

    private inline fun <reified T> patch(path: String, payload: T) = sendWithBody(path, "PATCH", payload)

    private inline fun <reified T> sendWithBody(path: String, method: String, payload: T): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
            .build()
        return exchange(request)
    }

    private fun exchange(request: HttpRequest): String {
        val response = transport.send(request)
        if (response.statusCode() !in 200..299) {
            throw PaperclipException(
                "${request.method()} ${request.uri().path} -> HTTP ${response.statusCode()}: " +
                    response.body().take(300),
                response.statusCode(),
            )
        }
        return response.body()
    }
}
