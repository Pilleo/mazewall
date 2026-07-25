package io.mazewall.orchestrator

interface JulesClient {
    fun hasUnableToCompleteActivity(sessionId: String): Boolean
    fun getSessionStatusFromActivities(sessionId: String): String?
    fun triggerSession(repo: String, issueId: String, prompt: String)
    fun sendSessionMessage(sessionId: String, prompt: String)
    fun getActiveSession(issueId: String): JulesSession?
    fun listSessions(): List<JulesSession>
}
