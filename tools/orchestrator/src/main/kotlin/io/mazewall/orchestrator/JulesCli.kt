package io.mazewall.orchestrator

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class JulesSession(
    val id: String,
    val description: String,
    val repo: String,
    val status: String
)

// ─── Request payloads ────────────────────────────────────────────────────────

@Serializable
private data class CreateSessionRequest(
    val prompt: String,
    val sourceContext: SourceContext,
    val title: String
)

@Serializable
private data class SourceContext(
    val source: String,
    val githubRepoContext: GithubRepoContext
)

@Serializable
private data class GithubRepoContext(
    val startingBranch: String
)

// ─── Sessions list response ───────────────────────────────────────────────────

@Serializable
private data class SessionResponse(
    val name: String,
    val title: String? = null,
    val state: String? = null
)

@Serializable
private data class ListSessionsResponse(
    val sessions: List<SessionResponse> = emptyList()
)

// ─── Typed activity payload types ─────────────────────────────────────────────

@Serializable
private data class PlanStep(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val index: Int = 0
)

@Serializable
private data class Plan(
    val id: String = "",
    val steps: List<PlanStep> = emptyList()
)

@Serializable
private data class PlanGeneratedPayload(val plan: Plan = Plan())

@Serializable
private data class PlanApprovedPayload(val planId: String = "")

@Serializable
private data class ProgressUpdatedPayload(
    val title: String = "",
    val description: String = ""
)

@Serializable
private data class GitPatch(
    val unidiffPatch: String = "",
    val baseCommitId: String = ""
)

@Serializable
private data class ChangeSet(
    val source: String = "",
    val gitPatch: GitPatch = GitPatch()
)

@Serializable
private data class Artifact(val changeSet: ChangeSet = ChangeSet())

@Serializable
private data class SessionFailedPayload(val reason: String = "")

@Serializable
private data class UserMessagedPayload(val userMessage: String = "")

// ─── Activity ─────────────────────────────────────────────────────────────────

@Serializable
private data class Activity(
    val name: String = "",
    val createTime: String = "",
    val originator: String = "",
    val id: String = "",
    val planGenerated: PlanGeneratedPayload? = null,
    val planApproved: PlanApprovedPayload? = null,
    val progressUpdated: ProgressUpdatedPayload? = null,
    val artifacts: List<Artifact>? = null,
    // sessionCompleted is an empty JSON object {}; JsonElement allows it without a custom class
    val sessionCompleted: JsonElement? = null,
    val sessionFailed: SessionFailedPayload? = null,
    val userMessaged: UserMessagedPayload? = null
)

@Serializable
private data class ListActivitiesResponse(
    val activities: List<Activity> = emptyList(),
    val nextPageToken: String? = null
)

// ─── JulesCli ─────────────────────────────────────────────────────────────────

object JulesCli {
    private var config: OrchestratorConfig = OrchestratorConfig()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val apiKey: String
        get() = System.getenv("JULES_API_KEY")
            ?: System.getProperty("JULES_API_KEY")
            ?: throw IllegalStateException(
                "JULES_API_KEY is not configured. Please define it in your .ENV file (e.g. JULES_API_KEY=AIzaSy...)."
            )

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun fetchActivities(sessionId: String): List<Activity> {
        val activities = mutableListOf<Activity>()
        var pageToken: String? = null
        do {
            val uriStr = "https://jules.googleapis.com/v1alpha/sessions/$sessionId/activities?pageSize=300" +
                    (if (pageToken != null) "&pageToken=$pageToken" else "")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .header("X-Goog-Api-Key", apiKey)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("Failed to list activities (HTTP ${response.statusCode()}): ${response.body()}")
            }
            val listResponse = json.decodeFromString<ListActivitiesResponse>(response.body())
            activities.addAll(listResponse.activities)
            pageToken = listResponse.nextPageToken
        } while (pageToken != null)
        return activities
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if a `sessionFailed` event is present in the activities
     * *after* the most recent "Retry" user message. Uses the typed API model
     * instead of string-matching on raw text fields.
     */
    fun hasUnableToCompleteActivity(sessionId: String): Boolean {
        return try {
            val activities = fetchActivities(sessionId)
            val lastRetryIndex = activities.indexOfLast {
                it.userMessaged?.userMessage?.equals("Retry", ignoreCase = true) == true
            }
            val activitiesToCheck = if (lastRetryIndex != -1) {
                activities.subList(lastRetryIndex + 1, activities.size)
            } else {
                activities
            }
            activitiesToCheck.any { it.sessionFailed != null }
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error listing activities: ${e.message}")
            false
        }
    }

    /**
     * Derives the session status directly from the activities stream, avoiding
     * a separate call to the sessions list endpoint for retry wait loops.
     *
     * Returns one of: `"failed"`, `"completed"`, `"in_progress"`, or `null` on
     * error.
     */
    fun getSessionStatusFromActivities(sessionId: String): String? {
        return try {
            val activities = fetchActivities(sessionId)
            val last = activities.lastOrNull()
            when {
                last == null -> null
                last.sessionFailed != null -> "failed"
                last.sessionCompleted != null -> "completed"
                else -> "in_progress"
            }
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error deriving session status from activities: ${e.message}")
            null
        }
    }

    fun init(config: OrchestratorConfig) {
        this.config = config
    }

    fun triggerSession(repo: String, issueId: String, prompt: String) {
        val sessionDescription = "[$issueId] ${prompt.take(150)}"
        println("🚀 Triggering remote Jules session for issue $issueId via REST API...")

        val requestPayload = CreateSessionRequest(
            prompt = prompt,
            sourceContext = SourceContext(
                source = "sources/github/$repo",
                githubRepoContext = GithubRepoContext(startingBranch = "main")
            ),
            title = sessionDescription
        )

        val requestBody = json.encodeToString(CreateSessionRequest.serializer(), requestPayload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jules.googleapis.com/v1alpha/sessions"))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("Failed to trigger Jules session (HTTP ${response.statusCode()}): ${response.body()}")
            }
            val session = json.decodeFromString<SessionResponse>(response.body())
            println("  [Jules API] Session created successfully: ${session.name}")
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error triggering session: ${e.message}")
            throw e
        }
    }

    fun sendSessionMessage(sessionId: String, prompt: String) {
        println("💬 Sending message to remote Jules session $sessionId via REST API...")

        val requestPayload = mapOf("prompt" to prompt)
        val requestBody = json.encodeToString(requestPayload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jules.googleapis.com/v1alpha/sessions/$sessionId:sendMessage"))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("Failed to send message to Jules session (HTTP ${response.statusCode()}): ${response.body()}")
            }
            println("  [Jules API] Message sent successfully to session $sessionId")
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error sending message to session: ${e.message}")
            throw e
        }
    }

    fun getActiveSession(issueId: String): JulesSession? {
        val sessions = listSessions()
        // Find the session where description contains the issue ID marker, e.g. "[issue-001]"
        return sessions
            .filter { it.description.contains("[$issueId]", ignoreCase = true) }
            .sortedByDescending { it.id }
            .firstOrNull()
    }

    /** Lists active remote Jules sessions by querying the REST API. */
    fun listSessions(): List<JulesSession> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jules.googleapis.com/v1alpha/sessions"))
            .header("X-Goog-Api-Key", apiKey)
            .GET()
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("Failed to list Jules sessions (HTTP ${response.statusCode()}): ${response.body()}")
            }
            val listResponse = json.decodeFromString<ListSessionsResponse>(response.body())
            listResponse.sessions.map { session ->
                val id = session.name.substringAfterLast("/")
                JulesSession(
                    id = id,
                    description = session.title ?: "",
                    repo = "",
                    status = session.state ?: ""
                )
            }
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error listing sessions: ${e.message}")
            emptyList()
        }
    }
}
