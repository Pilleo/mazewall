package demo

import java.io.IOException

internal enum class ExpectedResult {
    ALLOWED,
    DENIED,
}

internal sealed interface ScenarioOutcome {
    data class Completed(val detail: String) : ScenarioOutcome

    data class Denied(val detail: String) : ScenarioOutcome

    data class InfrastructureFailure(val cause: Throwable) : ScenarioOutcome
}

internal data class ScenarioResult(
    val name: String,
    val expected: ExpectedResult,
    val outcome: ScenarioOutcome,
) {
    val passed: Boolean
        get() = when (expected) {
            ExpectedResult.ALLOWED -> outcome is ScenarioOutcome.Completed
            ExpectedResult.DENIED -> outcome is ScenarioOutcome.Denied
        }

    fun render(): String {
        val status = if (passed) "PASS" else "FAIL"
        val observation = when (outcome) {
            is ScenarioOutcome.Completed -> "ALLOWED: ${outcome.detail}"
            is ScenarioOutcome.Denied -> "DENIED: ${outcome.detail}"
            is ScenarioOutcome.InfrastructureFailure ->
                "INFRASTRUCTURE FAILURE: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}"
        }
        return "[$status] $name (expected ${expected.name}): $observation"
    }
}

internal data class DemoReport(val results: List<ScenarioResult>) {
    val passed: Boolean get() = results.isNotEmpty() && results.all(ScenarioResult::passed)
    val exitCode: Int get() = if (passed) 0 else 2

    fun summary(): String = results.joinToString(separator = "\n", transform = ScenarioResult::render)
}

internal fun observeScenario(name: String, expected: ExpectedResult, action: () -> String): ScenarioResult {
    val outcome = try {
        ScenarioOutcome.Completed(action())
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        classifyContainmentFailure(failure)
    }
    return ScenarioResult(name, expected, outcome)
}

internal fun authenticateNativeDenial(errno: Int): ScenarioOutcome =
    when (errno) {
        1, // EPERM
        13, // EACCES
        -> ScenarioOutcome.Denied("native errno=$errno")
        else -> ScenarioOutcome.InfrastructureFailure(IOException("native protection probe failed with errno=$errno"))
    }
