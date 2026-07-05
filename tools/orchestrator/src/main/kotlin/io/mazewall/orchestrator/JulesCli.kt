package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

data class JulesSession(
    val id: String,
    val description: String,
    val repo: String,
    val status: String
)

object JulesCli {
    fun triggerSession(repo: String, issueId: String, prompt: String) {
        val sessionDescription = "[$issueId] ${prompt.take(150)}"
        println("🚀 Triggering remote Jules session for issue $issueId...")
        execute("jules", "remote", "new", "--repo", repo, "--session", sessionDescription)
    }

    fun getActiveSession(issueId: String): JulesSession? {
        val sessions = listSessions()
        // Find the session where description contains the issue ID marker, e.g. "[issue-001]"
        return sessions.firstOrNull { it.description.contains("[$issueId]", ignoreCase = true) }
    }

    fun listSessions(): List<JulesSession> {
        val output = executeWithPipe("jules", "remote", "list", "--session")
        val lines = output.lines()
        if (lines.size <= 1) return emptyList()

        val sessions = mutableListOf<JulesSession>()
        // Parse column headers to locate column positions, or parse using token split since IDs are 19-digit numbers
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("ID") || trimmed.startsWith("Status")) {
                continue
            }

            // Split line by multiple spaces
            val tokens = trimmed.split(Regex("\\s{2,}"))
            if (tokens.size >= 5) {
                val id = tokens[0].trim()
                // Make sure first token is a long number (Jules Session ID)
                if (id.toLongOrNull() != null) {
                    val desc = tokens[1].trim()
                    val repo = tokens[2].trim()
                    val status = tokens[4].trim()
                    sessions.add(JulesSession(id, desc, repo, status))
                }
            }
        }
        return sessions
    }

    private fun execute(vararg command: String): String {
        val pb = ProcessBuilder(*command)
        val env = pb.environment()
        val customToken = System.getProperty("GITHUB_TOKEN")
        if (customToken != null) {
            env["GITHUB_TOKEN"] = customToken
        } else {
            env.remove("GITHUB_TOKEN")
        }
        val process = pb.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.MINUTES)
        return output.trim()
    }

    private fun executeWithPipe(vararg command: String): String {
        // Run command piped to cat to disable TUI/terminal truncation
        val pb = ProcessBuilder("bash", "-c", "${command.joinToString(" ")} | cat")
        val env = pb.environment()
        val customToken = System.getProperty("GITHUB_TOKEN")
        if (customToken != null) {
            env["GITHUB_TOKEN"] = customToken
        } else {
            env.remove("GITHUB_TOKEN")
        }
        val process = pb.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.MINUTES)
        return output.trim()
    }
}
