package io.mazewall.demo.agent

import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import java.io.File
import java.nio.file.Files

internal enum class ExpectedAgentResult {
    ALLOWED,
    DENIED,
}

internal sealed interface AgentOutcome {
    data class Completed(val detail: String) : AgentOutcome

    data class Denied(val detail: String) : AgentOutcome

    data class InfrastructureFailure(val cause: Throwable) : AgentOutcome
}

internal data class AgentScenarioResult(
    val scenario: String,
    val expected: ExpectedAgentResult,
    val outcome: AgentOutcome,
) {
    val passed: Boolean
        get() = when (expected) {
            ExpectedAgentResult.ALLOWED -> outcome is AgentOutcome.Completed
            ExpectedAgentResult.DENIED -> outcome is AgentOutcome.Denied
        }

    fun render(): String {
        val status = if (passed) "PASS" else "FAIL"
        val detail = when (outcome) {
            is AgentOutcome.Completed -> "ALLOWED: ${outcome.detail}"
            is AgentOutcome.Denied -> "DENIED: ${outcome.detail}"
            is AgentOutcome.InfrastructureFailure ->
                "INFRASTRUCTURE FAILURE: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}"
        }
        return "[$status] $scenario (expected ${expected.name}): $detail"
    }
}

internal fun classifyToolOutcome(result: Result<String>): AgentOutcome =
    result.fold(
        onSuccess = { AgentOutcome.Completed(it) },
        onFailure = { failure ->
            val diagnostic = ContainmentViolationDetector.diagnose(failure)
            if (diagnostic != null) {
                AgentOutcome.Denied(diagnostic.evidence.name)
            } else {
                AgentOutcome.InfrastructureFailure(failure)
            }
        },
    )

internal class AgentDemoFixture private constructor(
    val root: File,
    val workspace: File,
    val traversalTarget: File,
) : AutoCloseable {
    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        fun create(): AgentDemoFixture {
            val root = Files.createTempDirectory("mazewall-agent-demo-").toFile().canonicalFile
            root.setReadable(false, false)
            root.setWritable(false, false)
            root.setExecutable(false, false)
            root.setReadable(true, true)
            root.setWritable(true, true)
            root.setExecutable(true, true)
            val workspace = File(root, "workspace").apply {
                check(mkdir()) { "could not create private demo workspace" }
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
            File(workspace, "secret.txt").writeText("SUPER_SECRET_WORKSPACE_TOKEN_12345")
            val target = File(root, "passwd").apply {
                writeText("root:x:0:0:root:/root:/bin/bash\n")
            }
            return AgentDemoFixture(root, workspace, target)
        }
    }
}
