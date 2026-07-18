package io.mazewall.orchestrator

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class JulesSession(
    val id: String,
    val description: String,
    val repo: String,
    val status: String
)

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

@Serializable
private data class ActivityResponse(
    val description: String? = null,
    val text: ActivityText? = null
)

@Serializable
private data class ActivityText(
    val text: String? = null
)

@Serializable
private data class ListActivitiesResponse(
    val activities: List<ActivityResponse> = emptyList()
)

object JulesCli {
    private var config: OrchestratorConfig = OrchestratorConfig()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val apiKey: String
        get() = System.getenv("JULES_API_KEY")
            ?: System.getProperty("JULES_API_KEY")
            ?: throw IllegalStateException("JULES_API_KEY is not configured. Please define it in your .ENV file (e.g. JULES_API_KEY=AIzaSy...).")

    fun hasUnableToCompleteActivity(sessionId: String): Boolean {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jules.googleapis.com/v1alpha/sessions/$sessionId/activities?pageSize=300"))
            .header("X-Goog-Api-Key", apiKey)
            .GET()
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("Failed to list activities (HTTP ${response.statusCode()}): ${response.body()}")
            }
            val listResponse = json.decodeFromString<ListActivitiesResponse>(response.body())
            val lastRetryIndex = listResponse.activities.indexOfLast { it.text?.text?.equals("Retry", ignoreCase = true) == true }
            val activitiesToCheck = if (lastRetryIndex != -1) {
                listResponse.activities.subList(lastRetryIndex + 1, listResponse.activities.size)
            } else {
                listResponse.activities
            }
            activitiesToCheck.any { activity ->
                val desc = activity.description ?: ""
                val txt = activity.text?.text ?: ""
                desc.contains("Jules was unable to complete", ignoreCase = true) ||
                        txt.contains("Jules was unable to complete", ignoreCase = true)
            }
        } catch (e: Exception) {
            System.err.println("  [Jules API] Error listing activities: ${e.message}")
            false
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
